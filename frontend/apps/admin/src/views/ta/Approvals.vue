<script setup lang="ts">
/**
 * TA 审批中心 · 仲裁（P3 FE-W1 · 09 PRD §4.1 两页线框之 TA 页）
 *
 * 契约（权威：TenantArbitrationController，据实查证）：
 *  - GET  /tenant/arbitrations?bizType=&status=&page=&size=（MpPage<Arbitration>）
 *      审批中心角标 = status=PENDING 的 total
 *  - POST /tenant/arbitrations/{id}/decide
 *      入库仲裁：APPROVED=通过·恢复流水 / REJECTED=驳回·保留冲销；
 *      liability 仅 REJECTED∧shortfall>0 必填、其余必空（50342）；REJECTED 缺 remark → 50333
 *
 * 产品口径（09 §2.3/§2.5/§6.3）：
 *  - 结论按钮文案写全「通过·恢复流水」「驳回·保留冲销」防歧义；
 *  - PENDING 超 72h 未裁标「⏰ 超时」（纯展示提醒，不自动流转）；
 *  - 结论备注必填（结论是线下赔偿唯一依据，必须留痕）。
 *
 * 视觉：沿用 ta/Inbound.vue 顶栏（AppTopbar + WarehouseSwitcher）+ 左侧菜单 shell。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Shop,
  User,
  Goods,
  Box,
  Coin,
  Setting,
  TrendCharts,
  Document,
  ChatLineSquare,
  Stamp,
  Refresh,
  PriceTag,
  Van,
} from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge } from '@cangchu/ui-shared'
import type {
  Arbitration,
  ArbitrationDecideRequest,
  ArbitrationLiability,
  InboundArbitrationConclusion,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { arbitrationApi } from '@/api/arbitration'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
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

// ============ 菜单（TA 端，含本页角标） ============
interface MenuItem {
  key: string
  label: string
  icon: typeof Shop
}

const menus: MenuItem[] = [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  { key: '/ta/wholesaler-applications', label: '入驻审批', icon: Stamp },
  { key: '/ta/skus', label: '商品', icon: Goods },
  { key: '/ta/pricing', label: '价格管理', icon: PriceTag },
  { key: '/ta/inbound', label: '入库', icon: Box },
  { key: '/ta/outbound', label: '出库作业', icon: Van },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '审批中心', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const IMPLEMENTED = new Set([
  '/ta/dashboard',
  '/ta/settings',
  '/ta/employees',
  '/ta/wholesalers',
  '/ta/wholesaler-applications',
  '/ta/skus',
  '/ta/pricing',
  '/ta/inbound',
  '/ta/outbound',
])

const activeMenu = ref('/ta/approvals')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/approvals') {
    activeMenu.value = key
    return
  }
  if (IMPLEMENTED.has(key)) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 映射 ============
const CONCLUSION_LABEL: Record<string, string> = {
  APPROVED: '通过 · 恢复流水',
  REJECTED: '驳回 · 保留冲销',
}
/**
 * 差额责任方文案（用户可见文案禁角色码，01 §3 中文对照：WK=库管员、WA=批发商管理员；
 * ui-shared 统一 roleLabel 就绪后可切换复用）
 */
const LIABILITY_LABEL: Record<ArbitrationLiability, string> = {
  WK_LIABLE: '库管员责任',
  WA_LIABLE: '批发商责任',
  NEGOTIATED: '双方协商',
  NO_LIABILITY: '无责',
}
/** 定责单选项（与 LIABILITY_LABEL 同源，避免模板散落硬编码） */
const LIABILITY_OPTIONS = (
  ['WK_LIABLE', 'WA_LIABLE', 'NEGOTIATED', 'NO_LIABILITY'] as ArbitrationLiability[]
).map((v) => ({ value: v, label: LIABILITY_LABEL[v] }))
const liabilityLabel = (v: string | null) =>
  v ? (LIABILITY_LABEL[v as ArbitrationLiability] ?? v) : '—'

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

/** PENDING 超 72h 未裁 → ⏰ 超时（09 §2.5 纯展示提醒） */
const isOverdue = (row: Arbitration): boolean => {
  if (row.status !== 'PENDING') return false
  const t = new Date(String(row.createdAt)).getTime()
  return Number.isFinite(t) && Date.now() - t > 72 * 3600 * 1000
}

// ============ 列表 ============
const STATUS_PENDING = 'PENDING'
const STATUS_DECIDED = 'DECIDED'
const activeTab = ref<string>(STATUS_PENDING)

const loading = ref(false)
const rows = ref<Arbitration[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
/** 审批中心角标 = PENDING total（契约） */
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await arbitrationApi.list({
      bizType: 'INBOUND_DISPUTE',
      status: activeTab.value,
      page: page.value,
      size,
    })
    rows.value = data.records ?? []
    total.value = Number(data.total) || 0
    if (activeTab.value === STATUS_PENDING) {
      pendingCount.value = total.value
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

/** 角标独立拉取（当前 tab 非 PENDING 时也保持角标准确） */
const fetchPendingCount = async () => {
  try {
    const data = await arbitrationApi.list({
      bizType: 'INBOUND_DISPUTE',
      status: STATUS_PENDING,
      page: 1,
      size: 1,
    })
    pendingCount.value = Number(data.total) || 0
  } catch {
    /* 静默 */
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

// ============ 裁决弹窗 ============
const decideVisible = ref(false)
const decideTarget = ref<Arbitration | null>(null)
const decideSubmitting = ref(false)

const conclusion = ref<InboundArbitrationConclusion | ''>('')
const liability = ref<ArbitrationLiability | ''>('')
const remark = ref('')

/** 差额定责仅在「驳回 ∧ 差额>0」时显示并必填（09 §1.2 / 50342 三态） */
const liabilityRequired = computed(
  () =>
    conclusion.value === 'REJECTED' &&
    (decideTarget.value?.shortfallQty ?? 0) > 0,
)

const openDecide = (row: Arbitration) => {
  decideTarget.value = row
  conclusion.value = ''
  liability.value = ''
  remark.value = ''
  decideVisible.value = true
}

const onDecideSubmit = async () => {
  const row = decideTarget.value
  if (!row) return
  if (!conclusion.value) {
    ElMessage.warning('请选择结论')
    return
  }
  if (liabilityRequired.value && !liability.value) {
    ElMessage.warning('驳回且存在已售差额时，必须选择差额责任方')
    return
  }
  // 结论备注必填（09 §1.1：结论是线下赔偿唯一依据，必须留痕）
  if (!remark.value.trim()) {
    ElMessage.warning('请填写结论备注')
    return
  }
  const payload: ArbitrationDecideRequest = {
    conclusion: conclusion.value,
    remark: remark.value.trim(),
    // liability 三态：仅必填场景传值，其余不传（后端 50342 双向校验）
    ...(liabilityRequired.value && liability.value ? { liability: liability.value } : {}),
  }
  decideSubmitting.value = true
  try {
    const updated = await arbitrationApi.decide(String(row.id), payload)
    decideVisible.value = false
    ElMessage.success(
      `仲裁单 ${updated.docNo} 已裁决：${CONCLUSION_LABEL[updated.conclusion ?? ''] ?? updated.conclusion}`,
    )
    await Promise.all([fetchList(), fetchPendingCount()])
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_ARBITRATION_NOT_PENDING) {
      // 并发双裁被抢占：关闭弹窗刷新回显
      decideVisible.value = false
      await Promise.all([fetchList(), fetchPendingCount()])
    }
  } finally {
    decideSubmitting.value = false
  }
}

// ============ 详情弹窗（已裁决只读） ============
const detailVisible = ref(false)
const detailTarget = ref<Arbitration | null>(null)

const openDetail = (row: Arbitration) => {
  detailTarget.value = row
  detailVisible.value = true
}

onMounted(() => {
  void fetchList()
  void fetchPendingCount()
})
</script>

<template>
  <div class="ta-shell">
    <!-- 顶栏 -->
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #store>
        <WarehouseSwitcher />
      </template>
      <template #bell>
        <NotificationBell />
      </template>
    </AppTopbar>

    <div class="ta-body">
      <!-- 左侧菜单 -->
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
            <NavCountBadge
              v-if="m.key === '/ta/approvals'"
              :count="pendingCount"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">审批中心 · 批发商代建入库异议</h2>
            <p class="page-head__sub">
              商户异议的代建入库单在此仲裁：通过则恢复流水，驳回则保留冲销并对差额定责
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="arbitration-tabs" @tab-change="onTabChange">
            <el-tab-pane :name="STATUS_PENDING">
              <template #label>
                <span class="tab-label">
                  待仲裁
                  <NavCountBadge :count="pendingCount" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已裁决" :name="STATUS_DECIDED" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="arb-table"
            data-test="arbitration-table"
            :empty-text="activeTab === STATUS_PENDING ? '暂无待仲裁单据' : '暂无已裁决记录'"
          >
            <el-table-column prop="docNo" label="仲裁单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
                <el-tag
                  v-if="isOverdue(row as Arbitration)"
                  type="danger"
                  size="small"
                  effect="plain"
                  class="overdue-tag"
                  data-test="overdue-tag"
                >
                  ⏰ 超时
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="关联入库单" min-width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.refDocNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="涉事商户" min-width="130">
              <template #default="{ row }">{{ row.wholesalerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="异议理由" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.reason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已冲销 / 差额" width="130" align="right">
              <template #default="{ row }">
                {{ row.reversedQty ?? 0 }} /
                <span :class="{ 'shortfall-warn': (row.shortfallQty ?? 0) > 0 }">
                  {{ row.shortfallQty ?? 0 }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="附件" width="80" align="center">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ row.attachments?.length ? `📷 ${row.attachments.length}` : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="activeTab === STATUS_DECIDED" label="结论" width="150">
              <template #default="{ row }">
                <el-tag
                  :type="row.conclusion === 'APPROVED' ? 'success' : 'danger'"
                  effect="light"
                  round
                >
                  {{ CONCLUSION_LABEL[row.conclusion ?? ''] ?? row.conclusion }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'"
                  type="primary"
                  size="small"
                  data-test="decide-btn"
                  @click="openDecide(row as Arbitration)"
                >
                  仲裁
                </el-button>
                <el-button
                  v-else
                  size="small"
                  data-test="detail-btn"
                  @click="openDetail(row as Arbitration)"
                >
                  详情
                </el-button>
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

    <!-- 裁决弹窗（09 §4.1 线框） -->
    <el-dialog
      v-model="decideVisible"
      :title="`⚖️ 仲裁 · 代建入库异议 ${decideTarget?.docNo ?? ''}`"
      width="600px"
      :close-on-click-modal="false"
      data-test="decide-dialog"
    >
      <template v-if="decideTarget">
        <el-descriptions :column="2" size="small" border class="decide-info">
          <el-descriptions-item label="关联入库单" :span="2">
            {{ decideTarget.refDocNo }}
          </el-descriptions-item>
          <el-descriptions-item label="涉事商户">
            {{ decideTarget.wholesalerName || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="发起时间">
            {{ formatTime(decideTarget.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="异议理由" :span="2">
            {{ decideTarget.reason }}
          </el-descriptions-item>
          <el-descriptions-item label="已冲销（按在库封顶）">
            {{ decideTarget.reversedQty ?? 0 }} 件
          </el-descriptions-item>
          <el-descriptions-item label="已售差额">
            <span :class="{ 'shortfall-warn': (decideTarget.shortfallQty ?? 0) > 0 }">
              {{ decideTarget.shortfallQty ?? 0 }} 件
              <template v-if="(decideTarget.shortfallQty ?? 0) > 0">⚠️ 驳回时需定责</template>
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="decideTarget.attachments?.length" class="decide-attachments">
          <span class="decide-attachments__label">附件：</span>
          <el-image
            v-for="(url, i) in decideTarget.attachments"
            :key="url"
            :src="url"
            :preview-src-list="decideTarget.attachments"
            :initial-index="i"
            fit="cover"
            class="decide-attachments__img"
            preview-teleported
          />
        </div>

        <el-form label-position="top" class="decide-form" @submit.prevent>
          <el-form-item label="结论（必选）" required>
            <el-radio-group v-model="conclusion" data-test="conclusion-radio">
              <el-radio value="APPROVED">通过 · 恢复流水（沿用原入库时间计费）</el-radio>
              <el-radio value="REJECTED">驳回 · 保留冲销</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item
            v-if="liabilityRequired"
            label="差额责任方（驳回且差额>0 时必填）"
            required
          >
            <el-radio-group v-model="liability" data-test="liability-radio">
              <el-radio v-for="opt in LIABILITY_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="结论备注（必填，线下赔偿唯一依据）" required>
            <el-input
              v-model="remark"
              type="textarea"
              :rows="3"
              maxlength="512"
              show-word-limit
              placeholder="裁决理由与线下处理约定，双方站内信将附带此备注"
              data-test="decide-remark"
            />
          </el-form-item>
        </el-form>

        <el-alert type="info" :closable="false" class="decide-hint">
          <template v-if="conclusion === 'APPROVED'">
            通过后联动：库存 +{{ decideTarget.reversedQty ?? 0 }} / 入库单转「已确认」，
            恢复流水沿用原入库时间戳计费。
          </template>
          <template v-else-if="conclusion === 'REJECTED'">
            驳回后联动：冲销保留、库存不变 / 入库单转「已撤销」；差额部分作为线下定责依据，
            平台不接资金。
          </template>
          <template v-else>请选择结论查看联动说明；裁决后不可撤销、不可重开。</template>
        </el-alert>
      </template>

      <template #footer>
        <el-button :disabled="decideSubmitting" @click="decideVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="decideSubmitting"
          data-test="decide-submit"
          @click="onDecideSubmit"
        >
          提交裁决
        </el-button>
      </template>
    </el-dialog>

    <!-- 已裁决详情（只读） -->
    <el-dialog
      v-model="detailVisible"
      :title="`仲裁详情 ${detailTarget?.docNo ?? ''}`"
      width="560px"
      data-test="arbitration-detail-dialog"
    >
      <el-descriptions v-if="detailTarget" :column="1" border>
        <el-descriptions-item label="关联入库单">
          {{ detailTarget.refDocNo }}
        </el-descriptions-item>
        <el-descriptions-item label="涉事商户">
          {{ detailTarget.wholesalerName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="异议理由">{{ detailTarget.reason }}</el-descriptions-item>
        <el-descriptions-item label="已冲销 / 已售差额">
          {{ detailTarget.reversedQty ?? 0 }} 件 / {{ detailTarget.shortfallQty ?? 0 }} 件
        </el-descriptions-item>
        <el-descriptions-item label="结论">
          <span data-test="detail-conclusion">
            {{ CONCLUSION_LABEL[detailTarget.conclusion ?? ''] ?? detailTarget.conclusion ?? '—' }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="差额责任方">
          {{ liabilityLabel(detailTarget.liability) }}
        </el-descriptions-item>
        <el-descriptions-item label="结论备注">
          {{ detailTarget.conclusionRemark || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="裁决时间">
          {{ formatTime(detailTarget.decidedAt) }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ta-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

.ta-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.ta-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.ta-side__menu {
  border-right: none;
}
.ta-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ta-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
.menu-badge {
  margin-left: var(--space-2);
}

/* ===== 主区 ===== */
.ta-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
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

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.arb-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}
.overdue-tag {
  margin-left: var(--space-1);
}
.shortfall-warn {
  color: var(--color-danger);
  font-weight: var(--font-weight-semibold);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

/* ===== 裁决弹窗 ===== */
.decide-info {
  margin-bottom: var(--space-3);
}
.decide-attachments {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.decide-attachments__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.decide-attachments__img {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-1);
  cursor: pointer;
}
.decide-form :deep(.el-radio) {
  margin-right: var(--space-4);
}
.decide-hint {
  margin-top: var(--space-2);
}
.decide-hint :deep(.el-alert__description) {
  margin: 0;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .ta-main {
    padding: var(--space-4);
    min-width: 0;
  }
}
</style>
