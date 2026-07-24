<script setup lang="ts">
/**
 * WA 入库确认（P3 FE-W1 · 代建入库 72h 确认/异议链）
 *
 * 契约（权威：WholesalerInboundController，据实查证）：
 *  - GET  /wholesaler/inbound-requests?status=&page=&size=
 *      MpPage<InboundRequest>；status=PENDING_WA_CONFIRM 时后端按 72h 倒计时升序
 *  - POST /{id}/confirm   CAS 确认；超窗已自动确认 → 50332
 *  - POST /{id}/dispute   异议（理由≤512 + 附件≤5）→ InboundDisputeResultVo
 *      回显「登记 N / 已冲销 M / 差额 N−M」+ YY- 仲裁单号
 *
 * 产品口径（09 PRD §6 / 04 §1.1）：
 *  - 队列头部展示全局口径文案（代建即可售 / 72h / 差额定责）；
 *  - source 映射：WK_CREATED=仓库代建、WA_SUBMIT=我方提交；
 *  - autoAccepted=1 标「72h 自动确认」；
 *  - 倒计时秒级刷新，<12h 红色警示，到期显示「已到期（自动确认中）」。
 *
 * 视觉：沿用 wa/Inquiry.vue 顶栏 + 左侧菜单 shell + el-table 风格（AppTopbar 公共组件）。
 */

import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document,
  Refresh,
  Check,
  Shop,
  User,
  Box,
  Warning as WarningIcon,
} from '@element-plus/icons-vue'
import { AppTopbar } from '@cangchu/ui-shared'
import type {
  InboundRequest,
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundSource,
  InboundStatus,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { waInboundApi } from '@/api/waInbound'
import { accountApi } from '@/api/account'
import NotificationBell from '@/components/NotificationBell.vue'
import InboundDisputeDialog from './InboundDisputeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(() => auth.currentStoreName || '我的商户')

const handleSwitchRole = () => auth.showSwitcher()

const handleProfileMenu = async (key: string) => {
  switch (key) {
    case 'profile':
      ElMessage.info('个人资料页留给后续 Agent 实现')
      break
    case 'security':
      ElMessage.info('安全设置页留给后续 Agent 实现')
      break
    case 'logout':
      try {
        await ElMessageBox.confirm('确认退出登录？', '退出确认', {
          confirmButtonText: '退出',
          cancelButtonText: '取消',
          type: 'warning',
        })
        await accountApi.logout().catch(() => undefined)
        auth.clear()
        router.replace('/login')
      } catch {
        /* cancel */
      }
      break
  }
}

// ============ 菜单（WA 端） ============
const activeMenu = ref('/wa/inbound')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/inbound') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 映射 ============
const SOURCE_LABEL: Record<InboundSource, string> = {
  WK_CREATED: '仓库代建',
  WA_SUBMIT: '我方提交',
}
const sourceLabel = (s: string | null) =>
  s ? (SOURCE_LABEL[s as InboundSource] ?? s) : '—'

type BadgeType = 'warning' | 'primary' | 'success' | 'info' | 'danger'
const STATUS_META: Record<InboundStatus, { label: string; type: BadgeType }> = {
  PENDING_WA_CONFIRM: { label: '待确认', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  DISPUTED: { label: '争议中', type: 'danger' },
  REVOKED: { label: '已撤销', type: 'info' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as InboundStatus] ?? { label: s, type: 'info' as BadgeType }

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

// ============ 72h 倒计时（秒级刷新；LocalDateTime 无时区偏移，按本地时间解析） ============
const nowTick = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null

const deadlineMs = (v: string | null): number | null => {
  if (!v) return null
  const t = new Date(String(v)).getTime()
  return Number.isFinite(t) ? t : null
}

/** 剩余毫秒；null=无截止；<=0 已到期 */
const remainMs = (row: InboundRequest): number | null => {
  const d = deadlineMs(row.waConfirmDeadline)
  return d === null ? null : d - nowTick.value
}

const countdownText = (row: InboundRequest): string => {
  const ms = remainMs(row)
  if (ms === null) return '—'
  if (ms <= 0) return '已到期（自动确认中）'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)}`
}

/** <12h 红色警示 */
const countdownDanger = (row: InboundRequest): boolean => {
  const ms = remainMs(row)
  return ms !== null && ms > 0 && ms < 12 * 3600 * 1000
}

// ============ 队列 ============
const TAB_PENDING = 'PENDING_WA_CONFIRM'
const TAB_ALL = 'ALL'
const activeTab = ref<string>(TAB_PENDING)

const loading = ref(false)
const rows = ref<InboundRequest[]>([])
const page = ref(1)
const size = 20
const total = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await waInboundApi.list({
      status: activeTab.value === TAB_ALL ? undefined : activeTab.value,
      page: page.value,
      size,
    })
    rows.value = data.records ?? []
    total.value = Number(data.total) || 0
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onTabChange = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

// ============ 确认 ============
const confirmingId = ref('')

const onConfirm = async (row: InboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `确认接受代建入库 ${row.docNo}（${row.qty} 件）？确认后单据生效，不可再提异议。`,
      '确认代建入库',
      { confirmButtonText: '确认接受', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  confirmingId.value = String(row.id)
  try {
    const updated = await waInboundApi.confirm(String(row.id))
    ElMessage.success(`入库单 ${updated.docNo} 已确认`)
    await fetchList()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_INBOUND_CONFIRM_WINDOW_CLOSED ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      // 超窗已自动确认 / 并发被抢占：拦截器已 toast，这里刷新回显最新状态
      await fetchList()
    }
  } finally {
    confirmingId.value = ''
  }
}

// ============ 异议 ============
const disputeVisible = ref(false)
const disputeTarget = ref<InboundRequest | null>(null)
const disputeSubmitting = ref(false)

/** 异议结果回显（09 §6.2：登记 N / 已冲销 M / 差额 N−M + YY- 单号） */
const resultVisible = ref(false)
const disputeResult = ref<InboundDisputeResult | null>(null)

const onDispute = (row: InboundRequest) => {
  disputeTarget.value = row
  disputeVisible.value = true
}

const onDisputeSubmit = async (payload: InboundDisputeRequest) => {
  const row = disputeTarget.value
  if (!row) return
  disputeSubmitting.value = true
  try {
    const result = await waInboundApi.dispute(String(row.id), payload)
    disputeVisible.value = false
    disputeResult.value = result
    resultVisible.value = true
    await fetchList()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_INBOUND_CONFIRM_WINDOW_CLOSED ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      disputeVisible.value = false
      await fetchList()
    }
  } finally {
    disputeSubmitting.value = false
  }
}

onMounted(() => {
  void fetchList()
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer)
})
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏（公共组件 + 站内信铃铛） -->
    <AppTopbar
      :store-name="storeNameDisplay"
      @switch-role="handleSwitchRole"
      @profile-command="handleProfileMenu"
    >
      <template #bell>
        <NotificationBell />
      </template>
    </AppTopbar>

    <div class="wa-body">
      <!-- 左侧菜单 -->
      <aside class="wa-side">
        <el-menu :default-active="activeMenu" class="wa-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="wa-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">入库确认</h2>
            <p class="page-head__sub">
              仓库代建的入库单在此确认或提出异议，逾期 72 小时将自动确认
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <!-- 全局口径文案（09 §6.1：WA 待确认队列头部必须展示） -->
        <el-alert type="warning" :closable="false" class="policy-alert" data-test="policy-copy">
          代建入库即视为可售，72 小时内可提异议，异议仅覆盖仍在库部分；已售出部分将进入差额定责。
        </el-alert>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="inbound-tabs" @tab-change="onTabChange">
            <el-tab-pane label="待确认" :name="TAB_PENDING" />
            <el-tab-pane label="全部" :name="TAB_ALL" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="inbound-table"
            data-test="inbound-table"
            :empty-text="activeTab === TAB_PENDING ? '暂无待确认入库单' : '暂无入库单'"
          >
            <el-table-column prop="docNo" label="入库单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="SKU" min-width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">{{ row.qty }}</template>
            </el-table-column>
            <el-table-column label="来源" width="110">
              <template #default="{ row }">
                <span :class="row.source === 'WK_CREATED' ? 'cell-name' : 'cell-muted'">
                  {{ sourceLabel(row.source) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
                <el-tag
                  v-if="row.autoAccepted === 1"
                  type="info"
                  effect="plain"
                  size="small"
                  class="auto-tag"
                  data-test="auto-accepted-tag"
                >
                  72h 自动确认
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="剩余确认时间" width="180">
              <template #default="{ row }">
                <span
                  v-if="row.status === 'PENDING_WA_CONFIRM'"
                  class="countdown"
                  :class="{ 'countdown--danger': countdownDanger(row as InboundRequest) }"
                  data-test="countdown"
                >
                  {{ countdownText(row as InboundRequest) }}
                </span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="登记时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING_WA_CONFIRM'">
                  <el-button
                    type="primary"
                    size="small"
                    :icon="Check"
                    :loading="confirmingId === String(row.id)"
                    data-test="confirm-btn"
                    @click="onConfirm(row as InboundRequest)"
                  >
                    确认
                  </el-button>
                  <el-button
                    type="danger"
                    size="small"
                    plain
                    data-test="dispute-btn"
                    @click="onDispute(row as InboundRequest)"
                  >
                    异议
                  </el-button>
                </template>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="total > size"
            class="pager"
            layout="total, prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="onPageChange"
          />
        </section>
      </main>
    </div>

    <!-- 异议表单弹窗 -->
    <InboundDisputeDialog
      v-model="disputeVisible"
      :row="disputeTarget"
      :submitting="disputeSubmitting"
      @submit="onDisputeSubmit"
    />

    <!-- 异议结果回显（09 §6.2 实时数字 + YY- 仲裁单号） -->
    <el-dialog
      v-model="resultVisible"
      title="异议已提交"
      width="480px"
      :close-on-click-modal="false"
      data-test="dispute-result-dialog"
    >
      <template v-if="disputeResult">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="入库单号">{{ disputeResult.docNo }}</el-descriptions-item>
          <el-descriptions-item label="登记件数">
            {{ disputeResult.registeredQty }} 件
          </el-descriptions-item>
          <el-descriptions-item label="已冲销（按在库封顶）">
            <span data-test="result-reversed">{{ disputeResult.reversedQty }} 件</span>
          </el-descriptions-item>
          <el-descriptions-item label="已售差额（进入定责）">
            <span
              data-test="result-shortfall"
              :class="{ 'shortfall-warn': disputeResult.shortfallQty > 0 }"
            >
              {{ disputeResult.shortfallQty }} 件
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="仲裁单号">
            <span class="cell-name" data-test="result-arbitration-doc">
              {{ disputeResult.arbitrationDocNo }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <p class="result-note">
          已生成仲裁单并通知店主（TA）裁决；差额部分将作为线下定责依据，平台不接资金。
        </p>
      </template>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wa-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== body ===== */
.wa-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.wa-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.wa-side__menu {
  border-right: none;
}
.wa-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.wa-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.wa-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.page-head__title {
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
  margin: 0;
}
.page-head__sub {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.policy-alert :deep(.el-alert__description) {
  margin: 0;
}

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.inbound-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}

.auto-tag {
  margin-left: var(--space-1);
}

.countdown {
  font-family: var(--font-family-mono);
  font-variant-numeric: tabular-nums;
  color: var(--color-warning);
  font-weight: var(--font-weight-semibold);
}
.countdown--danger {
  color: var(--color-danger);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

.shortfall-warn {
  color: var(--color-danger);
  font-weight: var(--font-weight-semibold);
}
.result-note {
  margin: var(--space-3) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  line-height: 1.6;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0; /* 表格内部滚动，不撑宽页面 */
  }
}
</style>
