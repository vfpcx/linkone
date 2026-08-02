<script setup lang="ts">
/**
 * WA 入库（P3 FE-W1 代建确认链 + P3b T1-FE 正向申请链）
 *
 * 契约（权威：WholesalerInboundController，据实查证）：
 *  - GET  /wholesaler/inbound-requests?status=&source=&page=&size=
 *      MpPage<InboundRequest>；status=PENDING_WA_CONFIRM 时后端按 72h 倒计时升序；
 *      P3b T1 扩 source=WA_SUBMIT（我的申请）过滤
 *  - POST /wholesaler/inbound-requests             P3b T1 提交申请（多行拆 N 单，零库存）
 *  - POST /{id}/withdraw  R1 撤回（仅待受理；已受理 50350 / 并发 50331 → 刷新回显）
 *  - POST /{id}/confirm   CAS 确认；超窗已自动确认 → 50332
 *  - POST /{id}/dispute   异议（理由≤512 + 附件≤5）→ InboundDisputeResultVo
 *      回显「登记 N / 已冲销 M / 差额 N−M」+ YY- 仲裁单号
 *
 * 产品口径（09 PRD §6 / 11 PRD §1）：
 *  - 队列头部展示全局口径文案；正向链固定文案「提交与受理不影响库存与计费，
 *    货物登记入库后次日开始计费」（11 §1.1-1）；
 *  - 正向链 CONFIRMED 统一「已入库」（D-3，与代建确认页「已确认」话术分开）；
 *  - 已驳回单据提供 [一键复制重建]（R2）；同批标识「同批 N 单」（batchSubmitId）。
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
  Plus,
  Warning as WarningIcon,
  Van,
} from '@element-plus/icons-vue'
import { AppTopbar } from '@cangchu/ui-shared'
import type {
  InboundRequest,
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundSource,
  InboundStatus,
  Sku,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { waInboundApi } from '@/api/waInbound'
import { skuApi } from '@/api/sku'
import { accountApi } from '@/api/account'
import NotificationBell from '@/components/NotificationBell.vue'
import InboundDisputeDialog from './InboundDisputeDialog.vue'
import InboundSubmitDialog from './InboundSubmitDialog.vue'

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
  { key: '/wa/outbound', label: '出库单', icon: Van },
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
const STATUS_META: Record<string, { label: string; type: BadgeType }> = {
  PENDING_WA_CONFIRM: { label: '待确认', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'success' },
  DISPUTED: { label: '争议中', type: 'danger' },
  REVOKED: { label: '已撤销', type: 'info' },
  // P3b T1 正向链（代建确认页共用兜底）
  SUBMITTED: { label: '待受理', type: 'warning' },
  ACCEPTED: { label: '已受理', type: 'primary' },
  REJECTED: { label: '已驳回', type: 'danger' },
  WITHDRAWN: { label: '已撤回', type: 'info' },
} satisfies Record<InboundStatus, { label: string; type: BadgeType }>
const statusMeta = (s: string) => STATUS_META[s] ?? { label: s, type: 'info' as BadgeType }

/** 正向链专用：CONFIRMED 统一「已入库」（D-3，不出现「已确认」话术） */
const mineStatusMeta = (s: string) =>
  s === 'CONFIRMED' ? { label: '已入库', type: 'success' as BadgeType } : statusMeta(s)

/** R2 驳回原因单选枚举 → 中文 */
const REJECT_REASON_LABEL: Record<string, string> = {
  QTY: '数量不符',
  QUALITY: '质量问题',
  BATCH: '批次不符',
  OTHER: '其他',
}
const rejectReasonLabel = (v: string | null | undefined) =>
  v ? (REJECT_REASON_LABEL[v] ?? v) : '—'

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

// ============ P3b T1 · 视图切换（代建确认 / 我的申请） ============
const VIEW_CONFIRM = 'confirm'
const VIEW_MINE = 'mine'
const viewMode = ref<string>(VIEW_CONFIRM)

const onViewChange = () => {
  if (viewMode.value === VIEW_MINE) void fetchMine()
  else void fetchList()
}

/** 本账号绑定商户（WA 或 WE 条目；提交/撤回授权由后端校验，WE 未持位 → 42004） */
const myWholesalerId = computed(() => {
  const entry = auth.roles?.find((r) => (r.role === 'WA' || r.role === 'WE') && r.wholesalerId)
  return entry?.wholesalerId ? String(entry.wholesalerId) : ''
})

// ============ 我的申请列表（source=WA_SUBMIT） ============
const MINE_TABS: Array<{ name: string; label: string }> = [
  { name: 'ALL', label: '全部' },
  { name: 'SUBMITTED', label: '待受理' },
  { name: 'ACCEPTED', label: '已受理' },
  { name: 'CONFIRMED', label: '已入库' },
  { name: 'REJECTED', label: '已驳回' },
  { name: 'WITHDRAWN', label: '已撤回' },
]
const mineTab = ref('ALL')
const mineLoading = ref(false)
const mineRows = ref<InboundRequest[]>([])
const minePage = ref(1)
const mineSize = 20
const mineTotal = ref(0)

const fetchMine = async () => {
  mineLoading.value = true
  try {
    const data = await waInboundApi.list({
      source: 'WA_SUBMIT',
      status: mineTab.value === 'ALL' ? undefined : mineTab.value,
      page: minePage.value,
      size: mineSize,
    })
    mineRows.value = data.records ?? []
    mineTotal.value = Number(data.total) || 0
  } catch {
    // 全局 toast 已提示
  } finally {
    mineLoading.value = false
  }
}

const onMineTabChange = () => {
  minePage.value = 1
  void fetchMine()
}

const onMinePageChange = (p: number) => {
  minePage.value = p
  void fetchMine()
}

/** 「同批 N 单」标识（当前页内同 batchSubmitId 计数；N≥2 才展示） */
const batchCountMap = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const r of mineRows.value) {
    const key = r.batchSubmitId ? String(r.batchSubmitId) : ''
    if (key) map[key] = (map[key] ?? 0) + 1
  }
  return map
})
const batchTag = (row: InboundRequest): string => {
  const key = row.batchSubmitId ? String(row.batchSubmitId) : ''
  const n = key ? (batchCountMap.value[key] ?? 0) : 0
  return n >= 2 ? `同批 ${n} 单` : ''
}

// ============ 本商户 SKU（名称回显 + 提交表单选择器数据源） ============
const skus = ref<Sku[]>([])
const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
  return map
})
const skuLabel = (id: unknown): string => skuNameMap.value[String(id)] || String(id)

const fetchSkus = async () => {
  if (!myWholesalerId.value) return
  try {
    skus.value = await skuApi.list(myWholesalerId.value)
  } catch {
    // 全局 toast 已提示（WE 只读列表放行，异常时回退展示 skuId）
  }
}

// ============ 提交入库申请（含 R2 一键复制重建） ============
const submitVisible = ref(false)
const submitPrefill = ref<{
  skuId: string
  qty: number
  palletQty?: number
  remark?: string
} | null>(null)

const openSubmit = () => {
  submitPrefill.value = null
  submitVisible.value = true
}

/** R2 一键复制重建：带出原单字段生成新表单（11 §1.3） */
const openRebuild = (row: InboundRequest) => {
  submitPrefill.value = {
    skuId: String(row.skuId),
    qty: row.requestedQty ?? row.qty,
    ...(row.palletQty !== null && row.palletQty !== undefined
      ? { palletQty: row.palletQty }
      : {}),
    ...(row.remark ? { remark: row.remark } : {}),
  }
  detailVisible.value = false
  submitVisible.value = true
}

const onSubmitted = async () => {
  viewMode.value = VIEW_MINE
  mineTab.value = 'SUBMITTED'
  minePage.value = 1
  await fetchMine()
}

// ============ R1 撤回 ============
const withdrawVisible = ref(false)
const withdrawTarget = ref<InboundRequest | null>(null)
const withdrawReason = ref('')
const withdrawSubmitting = ref(false)

const openWithdraw = (row: InboundRequest) => {
  withdrawTarget.value = row
  withdrawReason.value = ''
  withdrawVisible.value = true
}

const onWithdrawSubmit = async () => {
  const row = withdrawTarget.value
  if (!row) return
  if (!withdrawReason.value.trim()) {
    ElMessage.warning('请填写撤回理由')
    return
  }
  withdrawSubmitting.value = true
  try {
    const updated = await waInboundApi.withdraw(String(row.id), {
      reason: withdrawReason.value.trim(),
    })
    withdrawVisible.value = false
    ElMessage.success(`申请 ${updated.docNo} 已撤回（不影响库存与计费）`)
    await fetchMine()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_INBOUND_NOT_WITHDRAWABLE ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      // 已被受理/并发撞车：拦截器已 toast「刷新重试」口径，这里刷新回显最新状态
      withdrawVisible.value = false
      await fetchMine()
    }
  } finally {
    withdrawSubmitting.value = false
  }
}

// ============ 申请详情（含驳回原因/双值展示） ============
const detailVisible = ref(false)
const detailTarget = ref<InboundRequest | null>(null)

const openDetail = (row: InboundRequest) => {
  detailTarget.value = row
  detailVisible.value = true
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
  void fetchSkus()
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
              {{
                viewMode === VIEW_MINE
                  ? '我方提交的入库申请在此跟踪与撤回，登记入库后次日开始计费'
                  : '仓库代建的入库单在此确认或提出异议，逾期 72 小时将自动确认'
              }}
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              v-if="viewMode === VIEW_MINE"
              type="primary"
              :icon="Plus"
              data-test="new-submit-btn"
              @click="openSubmit"
            >
              新建入库申请
            </el-button>
            <el-button
              :icon="Refresh"
              :loading="viewMode === VIEW_MINE ? mineLoading : loading"
              @click="viewMode === VIEW_MINE ? fetchMine() : fetchList()"
            >
              刷新
            </el-button>
          </div>
        </header>

        <!-- 视图切换：代建确认 / 我的申请（P3b T1） -->
        <el-radio-group
          v-model="viewMode"
          class="view-switch"
          data-test="view-switch"
          @change="onViewChange"
        >
          <el-radio-button :value="VIEW_CONFIRM">代建入库确认</el-radio-button>
          <el-radio-button :value="VIEW_MINE">我的入库申请</el-radio-button>
        </el-radio-group>

        <!-- 全局口径文案（09 §6.1 / 11 §1.1-1 按视图切换） -->
        <el-alert
          v-if="viewMode === VIEW_CONFIRM"
          type="warning"
          :closable="false"
          class="policy-alert"
          data-test="policy-copy"
        >
          代建入库即视为可售，72 小时内可提异议，异议仅覆盖仍在库部分；已售出部分将进入差额定责。
        </el-alert>
        <el-alert
          v-else
          type="info"
          :closable="false"
          class="policy-alert"
          data-test="mine-policy-copy"
        >
          提交与受理不影响库存与计费，货物登记入库后次日开始计费；仅「待受理」申请可撤回，受理后如需取消请联系仓库。
        </el-alert>

        <section v-if="viewMode === VIEW_CONFIRM" class="card">
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

        <!-- ============ 我的入库申请（P3b T1 正向链，source=WA_SUBMIT） ============ -->
        <section v-else class="card">
          <el-tabs v-model="mineTab" data-test="mine-tabs" @tab-change="onMineTabChange">
            <el-tab-pane v-for="t in MINE_TABS" :key="t.name" :label="t.label" :name="t.name" />
          </el-tabs>

          <el-table
            v-loading="mineLoading"
            :data="mineRows"
            row-key="id"
            class="inbound-table"
            data-test="mine-table"
            empty-text="暂无入库申请，点击右上角「新建入库申请」发起"
          >
            <el-table-column prop="docNo" label="申请单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="申请件数" width="100" align="right">
              <template #default="{ row }">{{ row.requestedQty ?? row.qty }}</template>
            </el-table-column>
            <el-table-column label="实登件数" width="100" align="right">
              <template #default="{ row }">
                <span v-if="row.status === 'CONFIRMED'" class="cell-name">{{ row.qty }}</span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="托盘" width="80" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.palletQty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="180">
              <template #default="{ row }">
                <el-tag :type="mineStatusMeta(row.status).type" effect="light" round>
                  {{ mineStatusMeta(row.status).label }}
                </el-tag>
                <el-tag
                  v-if="row.status === 'REJECTED' && row.rejectReason"
                  type="danger"
                  effect="plain"
                  size="small"
                  class="auto-tag"
                >
                  {{ rejectReasonLabel(row.rejectReason) }}
                </el-tag>
                <el-tag
                  v-if="batchTag(row as InboundRequest)"
                  type="info"
                  effect="plain"
                  size="small"
                  class="auto-tag"
                  data-test="batch-tag"
                >
                  {{ batchTag(row as InboundRequest) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'SUBMITTED'"
                  type="warning"
                  size="small"
                  plain
                  data-test="withdraw-btn"
                  @click="openWithdraw(row as InboundRequest)"
                >
                  撤回
                </el-button>
                <el-button
                  v-if="row.status === 'REJECTED'"
                  type="primary"
                  size="small"
                  plain
                  data-test="rebuild-btn"
                  @click="openRebuild(row as InboundRequest)"
                >
                  复制重建
                </el-button>
                <el-button
                  size="small"
                  text
                  data-test="mine-detail-btn"
                  @click="openDetail(row as InboundRequest)"
                >
                  详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="mineTotal > mineSize"
            class="pager"
            layout="total, prev, pager, next"
            :total="mineTotal"
            :page-size="mineSize"
            :current-page="minePage"
            @current-change="onMinePageChange"
          />
        </section>
      </main>
    </div>

    <!-- 新建入库申请（多行拆单 + R2 复制重建预填） -->
    <InboundSubmitDialog
      v-model="submitVisible"
      :wholesaler-id="myWholesalerId"
      :skus="skus"
      :store-name="storeNameDisplay"
      :prefill="submitPrefill"
      @submitted="onSubmitted"
    />

    <!-- R1 撤回弹窗（理由必填 ≤100） -->
    <el-dialog
      v-model="withdrawVisible"
      title="撤回入库申请"
      width="440px"
      :close-on-click-modal="false"
      data-test="withdraw-dialog"
    >
      <template v-if="withdrawTarget">
        <p class="dlg-doc">
          <span class="cell-name">{{ withdrawTarget.docNo }}</span>
          <span class="cell-muted">
            {{ skuLabel(withdrawTarget.skuId) }} ×
            {{ withdrawTarget.requestedQty ?? withdrawTarget.qty }}
          </span>
        </p>
        <el-input
          v-model="withdrawReason"
          type="textarea"
          :rows="3"
          maxlength="100"
          show-word-limit
          placeholder="撤回理由（必填 ≤100 字）"
          data-test="withdraw-reason"
        />
        <p class="dlg-note">撤回不影响库存与计费；撤回后可另行新建申请。</p>
      </template>
      <template #footer>
        <el-button :disabled="withdrawSubmitting" @click="withdrawVisible = false">取消</el-button>
        <el-button
          type="warning"
          :loading="withdrawSubmitting"
          data-test="withdraw-submit"
          @click="onWithdrawSubmit"
        >
          确认撤回
        </el-button>
      </template>
    </el-dialog>

    <!-- 申请详情（双值展示 + 驳回原因/照片 + 复制重建入口） -->
    <el-dialog
      v-model="detailVisible"
      :title="`申请详情 ${detailTarget?.docNo ?? ''}`"
      width="560px"
      data-test="mine-detail-dialog"
    >
      <template v-if="detailTarget">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="商品">
            {{ skuLabel(detailTarget.skuId) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="mineStatusMeta(detailTarget.status).type" effect="light" round>
              {{ mineStatusMeta(detailTarget.status).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请件数">
            {{ detailTarget.requestedQty ?? detailTarget.qty }} 件
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.status === 'CONFIRMED'" label="实登件数">
            {{ detailTarget.qty }} 件
          </el-descriptions-item>
          <el-descriptions-item label="托盘数">
            {{ detailTarget.palletQty ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.remark" label="备注">
            {{ detailTarget.remark }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.status === 'REJECTED'" label="驳回原因">
            {{ rejectReasonLabel(detailTarget.rejectReason) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.status === 'REJECTED'" label="驳回备注">
            {{ detailTarget.rejectRemark || '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.status === 'WITHDRAWN'" label="撤回理由">
            {{ detailTarget.withdrawReason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.registeredAt" label="登记时间">
            {{ formatTime(detailTarget.registeredAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">
            {{ formatTime(detailTarget.createdAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <div
          v-if="detailTarget.status === 'REJECTED' && detailTarget.rejectAttachments?.length"
          class="detail-attachments"
        >
          <span class="cell-muted">驳回照片：</span>
          <el-image
            v-for="(url, i) in detailTarget.rejectAttachments"
            :key="url"
            :src="url"
            :preview-src-list="detailTarget.rejectAttachments"
            :initial-index="i"
            fit="cover"
            class="detail-attachments__img"
            preview-teleported
          />
        </div>
        <div v-if="detailTarget.attachments?.length" class="detail-attachments">
          <span class="cell-muted">登记照片：</span>
          <el-image
            v-for="(url, i) in detailTarget.attachments"
            :key="url"
            :src="url"
            :preview-src-list="detailTarget.attachments"
            :initial-index="i"
            fit="cover"
            class="detail-attachments__img"
            preview-teleported
          />
        </div>

        <p class="dlg-note">
          {{
            detailTarget.status === 'ACCEPTED'
              ? '仓库已受理，如需取消请联系仓库。'
              : '提交与受理不影响库存与计费，货物登记入库后次日开始计费；登记错误由仓库在 24 小时内发起纠错，超时走盘点调整。'
          }}
        </p>
      </template>
      <template #footer>
        <el-button
          v-if="detailTarget?.status === 'REJECTED'"
          type="primary"
          plain
          data-test="detail-rebuild-btn"
          @click="openRebuild(detailTarget!)"
        >
          一键复制重建
        </el-button>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

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
          已生成仲裁单并通知店长裁决；差额部分将作为线下定责依据，平台不接资金。
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

.page-head__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.view-switch {
  align-self: flex-start;
}

.dlg-doc {
  margin: 0 0 var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.dlg-note {
  margin: var(--space-3) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  line-height: 1.6;
}
.detail-attachments {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.detail-attachments__img {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-1);
  cursor: pointer;
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
