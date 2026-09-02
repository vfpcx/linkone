<script setup lang="ts">
/**
 * 批次临期（TA 看板 + WK 预警列表 + 批次登记簿 · P3b T4-FE · PRD 11 §3.6-A/B）
 *
 * 契约（权威：BatchController，据实查证）：
 *  - GET /tenant/batches/expiry-dashboard  TA 临期看板（四卡 + bySku；仅 TA，42101）
 *  - GET /tenant/batches/expiring          预警列表（EXPIRING∪PENDING_CLEARANCE 剩余天数升序；
 *      行含 manualNotifiedAt 供 24h 冷却）
 *  - POST /tenant/batches/{id}/notify-wholesaler  WK 一键通知（24h 限 1 → 50367）
 *  - GET /tenant/batches?wholesalerId=&skuId=&status=  登记簿/下钻（双筛齐时附 unpooledQty）
 *  - PUT /tenant/batches/{id}              默认批次补录（仅 source=DEFAULT 未终态；40205/40206）
 *
 * 展示口径（PRD §3.3）：
 *  - 推算剩余一律标注「推算值 · 截至今日 02:00」，说明气泡含 ≤1 天误差与清库以现场核数为准；
 *  - 无批次在池量可为负（推算滞后窗口），UI 归 0 展示；
 *  - 剩余天数可负=已过期（红显「过期 N 天」）；默认批次未补录到效期 → ⚪ 待补录。
 *  - BatchVo 不含名称（13 v1.4 备注 12），本页以商户/SKU 接口自建映射。
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
  RefreshLeft,
  PriceTag,
  Van,
  Checked,
  AlarmClock,
  Remove,
  QuestionFilled,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  NavCountBadge,
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type {
  Batch,
  BatchLocationLog,
  BatchStatus,
  ExpiryDashboard,
  Sku,
  Wholesaler,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { batchApi } from '@/api/batch'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
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

// ============ 菜单 ============
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
  { key: '/ta/returns', label: '退货受理', icon: RefreshLeft },
  { key: '/ta/stocktake', label: '盘点', icon: Checked },
  { key: '/ta/batches', label: '批次临期', icon: AlarmClock },
  { key: '/ta/clearance', label: '清库', icon: Remove },
  { key: '/ta/approvals', label: '审批中心', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const IMPLEMENTED = new Set([
  '/ta/bills',
  '/ta/dashboard',
  '/ta/settings',
  '/ta/employees',
  '/ta/wholesalers',
  '/ta/wholesaler-applications',
  '/ta/skus',
  '/ta/pricing',
  '/ta/inbound',
  '/ta/outbound',
  '/ta/returns',
  '/ta/stocktake',
  '/ta/clearance',
  '/ta/approvals',
  '/ta/messages',
])

const activeMenu = ref('/ta/batches')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/batches') {
    activeMenu.value = key
    return
  }
  if (IMPLEMENTED.has(key)) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 角色（看板仅 TA 端点、通知仅 WK 端点，前端按登录角色隐藏入口） ============
const isTa = computed(() => auth.roles?.some((r) => r.role === 'TA') ?? false)
const isWk = computed(() => auth.roles?.some((r) => r.role === 'WK') ?? false)

// ============ 映射 ============
type BadgeType = 'warning' | 'primary' | 'success' | 'info' | 'danger'
const STATUS_META: Record<BatchStatus, { label: string; type: BadgeType }> = {
  IN_STOCK: { label: '在库', type: 'success' },
  EXPIRING: { label: '临期', type: 'warning' },
  PENDING_CLEARANCE: { label: '待清理', type: 'danger' },
  SOLD_OUT: { label: '已售罄', type: 'info' },
  CLEARED: { label: '已清库', type: 'info' },
  CLOSED: { label: '已冻结', type: 'info' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as BatchStatus] ?? { label: s, type: 'info' as BadgeType }

const SOURCE_LABELS: Record<string, string> = { INBOUND: '入库登记', DEFAULT: '默认批次' }
const sourceLabel = (s: string): string => SOURCE_LABELS[s] ?? s

const formatDate = (v: string | null): string => (v ? String(v).slice(0, 10) : '—')

/** 剩余天数展示（可负=已过期红显；无到效期=待补录） */
const remainClass = (b: Batch): string => {
  if (b.remainingDays === null || b.remainingDays === undefined) return 'remain-none'
  if (b.remainingDays <= 0) return 'remain-expired'
  return 'remain-warn'
}
const remainText = (b: Batch): string => {
  const d = b.remainingDays
  if (d === null || d === undefined) return '待补录'
  if (d < 0) return `过期 ${-d} 天`
  if (d === 0) return '今日到期'
  return `${d} 天`
}

// ============ 名称映射（BatchVo 免 join，前端自建；13 v1.4 备注 12） ============
const wholesalers = ref<Wholesaler[]>([])
const skuMap = ref<Record<string, string>>({})

const wholesalerNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const w of wholesalers.value) map[String(w.id)] = w.name
  return map
})
const wholesalerLabel = (id: unknown): string => wholesalerNameMap.value[String(id)] || String(id)
const skuLabel = (id: unknown): string => skuMap.value[String(id)] || String(id)

const fetchNames = async () => {
  try {
    wholesalers.value = await wholesalerApi.list()
    const lists = await Promise.all(
      wholesalers.value.map((w) => skuApi.list(String(w.id)).catch(() => [] as Sku[])),
    )
    const map: Record<string, string> = {}
    for (const list of lists) {
      for (const s of list) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
    }
    skuMap.value = map
  } catch {
    // 全局 toast 已提示；名称回退展示 id
  }
}

// ============ TA 临期看板（四卡 + bySku） ============
const dashboard = ref<ExpiryDashboard | null>(null)
const dashboardLoading = ref(false)

const fetchDashboard = async () => {
  if (!isTa.value) return
  dashboardLoading.value = true
  try {
    dashboard.value = await batchApi.expiryDashboard()
  } catch {
    // 全局 toast 已提示
  } finally {
    dashboardLoading.value = false
  }
}

// ============ 预警列表 / 登记簿 ============
const activeTab = ref<'expiring' | 'registry'>('expiring')
const loading = ref(false)
const expiringRows = ref<Batch[]>([])

const fetchExpiring = async () => {
  loading.value = true
  try {
    const res = await batchApi.expiring()
    expiringRows.value = res.list
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// 登记簿筛选
const filter = ref({ wholesalerId: '', skuId: '', status: '' })
const registryRows = ref<Batch[]>([])
const unpooledQty = ref<number | null>(null)
const filterSkus = ref<Sku[]>([])

const STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'IN_STOCK', label: '在库' },
  { value: 'EXPIRING', label: '临期' },
  { value: 'PENDING_CLEARANCE', label: '待清理' },
  { value: 'SOLD_OUT', label: '已售罄' },
  { value: 'CLEARED', label: '已清库' },
  { value: 'CLOSED', label: '已冻结' },
]

const wholesalerPickerColumns: EntityPickerColumn<Wholesaler>[] = [
  { label: '商户名称', prop: 'name', minWidth: 160 },
  { label: '简介', formatter: (w) => w.intro || '—', minWidth: 160 },
]
const fetchWholesalerPage = makeClientPickerFetch<Wholesaler>(
  () => wholesalers.value,
  (w, kw) => w.name.toLowerCase().includes(kw) || (w.intro ?? '').toLowerCase().includes(kw),
)

const skuPickerColumns: EntityPickerColumn<Sku>[] = [
  { label: '商品名称', prop: 'name', minWidth: 160 },
  { label: '规格', formatter: (s) => s.spec || '—', minWidth: 100 },
]
const fetchSkuPage = makeClientPickerFetch<Sku>(
  () => filterSkus.value,
  (s, kw) => s.name.toLowerCase().includes(kw) || (s.spec ?? '').toLowerCase().includes(kw),
)

const onFilterWholesalerChange = async () => {
  filter.value.skuId = ''
  filterSkus.value = []
  if (filter.value.wholesalerId) {
    try {
      filterSkus.value = await skuApi.list(filter.value.wholesalerId)
    } catch {
      // 全局 toast 已提示
    }
  }
  await fetchRegistry()
}

const fetchRegistry = async () => {
  loading.value = true
  try {
    const res = await batchApi.list({
      wholesalerId: filter.value.wholesalerId || undefined,
      skuId: filter.value.skuId || undefined,
      status: filter.value.status || undefined,
    })
    registryRows.value = res.list
    unpooledQty.value = res.unpooledQty ?? null
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

/** 无批次在池量（可为负=推算滞后窗口，UI 归 0 展示，PRD §3.3-3） */
const unpooledDisplay = computed<number | null>(() =>
  unpooledQty.value === null || unpooledQty.value === undefined
    ? null
    : Math.max(0, unpooledQty.value),
)

const onTabChange = () => {
  if (activeTab.value === 'expiring') void fetchExpiring()
  else void fetchRegistry()
}

const refreshAll = () =>
  Promise.all([
    fetchDashboard(),
    activeTab.value === 'expiring' ? fetchExpiring() : fetchRegistry(),
  ])

// ============ 手动一键通知（WK；同批次 24h 限 1 → 50367） ============
const notifyingId = ref('')

/** 24h 冷却剩余小时数（0=可通知；manualNotifiedAt 为后端 LocalDateTime 无时区串，按本地解析） */
const notifyCooldownHours = (b: Batch): number => {
  if (!b.manualNotifiedAt) return 0
  const t = new Date(String(b.manualNotifiedAt).replace('T', ' ').replace(/-/g, '/')).getTime()
  if (Number.isNaN(t)) return 0
  const left = 24 * 3600_000 - (Date.now() - t)
  return left > 0 ? Math.ceil(left / 3600_000) : 0
}

const onNotify = async (b: Batch) => {
  try {
    await ElMessageBox.confirm(
      `将站内信通知「${wholesalerLabel(b.wholesalerId)}」：批次 ${b.batchNo}（${skuLabel(b.skuId)}）` +
        `${(b.remainingDays ?? 1) <= 0 ? '已过期' : '即将到效'}，请尽快处理。同一批次 24 小时内限通知 1 次。`,
      '通知批发商',
      { confirmButtonText: '发送通知', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  notifyingId.value = String(b.id)
  try {
    await batchApi.notifyWholesaler(String(b.id))
    ElMessage.success(`已通知商户（批次 ${b.batchNo}）`)
    await fetchExpiring()
  } catch {
    // 50367 「24 小时内已通知过该批次」：全局 toast 已提示；刷新回显最新冷却态
    await fetchExpiring()
  } finally {
    notifyingId.value = ''
  }
}

// ============ 发起清库（仅 PENDING_CLEARANCE 且推算剩余 >0，跳清库页带批次） ============
const canClear = (b: Batch): boolean =>
  String(b.status) === 'PENDING_CLEARANCE' && (b.remainingQty ?? 0) > 0

const onStartClearance = (b: Batch) => {
  router.push({ path: '/ta/clearance', query: { batch: String(b.id) } })
}

// ============ 默认批次补录（仅 source=DEFAULT 且未终态） ============
const canBackfill = (b: Batch): boolean =>
  String(b.source) === 'DEFAULT' && !['CLEARED', 'CLOSED'].includes(String(b.status))

// ============ P5-D C2 货位（移库 + 变更记录 · US-WK-05，25-p5-c-c2 §4.4） ============
const locationEnabled = ref<boolean | null>(null)

const loadLocationConfig = async () => {
  const id = auth.tenantInfo?.tenantId ?? auth.roles?.find((r) => r.tenantId)?.tenantId
  if (!id) return
  try {
    const cfg = await batchApi.config(String(id))
    locationEnabled.value = cfg.locationEnabled === 1
  } catch {
    // 保持 null（保守显示入口）
  }
}

const canMoveLocation = (b: Batch): boolean =>
  !['CLEARED', 'CLOSED'].includes(String(b.status))

const moveVisible = ref(false)
const moveTarget = ref<Batch | null>(null)
const moveLocation = ref('')
const moveSaving = ref(false)

const openMove = (b: Batch) => {
  moveTarget.value = b
  moveLocation.value = b.location ?? ''
  moveVisible.value = true
}

const doMove = async () => {
  const target = moveTarget.value
  if (!target) return
  const next = moveLocation.value.trim()
  moveSaving.value = true
  try {
    await batchApi.updateLocation(String(target.id), next ? { location: next } : { location: null })
    moveVisible.value = false
    ElMessage.success(
      next ? `批次 ${target.batchNo} 已移库至 ${next}` : `批次 ${target.batchNo} 货位已清空`,
    )
    await refreshAll()
  } catch {
    // 全局 toast 已提示（50363/50823 等）
  } finally {
    moveSaving.value = false
  }
}

const logsVisible = ref(false)
const logsTarget = ref<Batch | null>(null)
const logsRows = ref<BatchLocationLog[]>([])
const logsLoading = ref(false)

const openLogs = async (b: Batch) => {
  logsTarget.value = b
  logsRows.value = []
  logsVisible.value = true
  logsLoading.value = true
  try {
    const page = await batchApi.locationLogs(String(b.id), { page: 1, size: 50 })
    logsRows.value = page.records ?? []
  } catch {
    // 全局 toast 已提示
  } finally {
    logsLoading.value = false
  }
}

const backfillVisible = ref(false)
const backfillTarget = ref<Batch | null>(null)
const backfillSaving = ref(false)
const backfillForm = ref({ productionDate: '', expiryDate: '' })

const openBackfill = (b: Batch) => {
  backfillTarget.value = b
  backfillForm.value = {
    productionDate: b.productionDate ?? '',
    expiryDate: b.expiryDate ?? '',
  }
  backfillVisible.value = true
}

const todayStr = (): string => {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 客户端预检（后端权威 40205/40206） */
const backfillError = computed<string>(() => {
  const { productionDate, expiryDate } = backfillForm.value
  if (!productionDate && !expiryDate) return '请至少补录一项日期'
  if (productionDate && productionDate > todayStr()) return '生产日期不能晚于今天'
  if (productionDate && expiryDate && expiryDate <= productionDate) {
    return '到效期必须晚于生产日期'
  }
  return ''
})

const onBackfillSave = async () => {
  const target = backfillTarget.value
  if (!target || backfillError.value) return
  backfillSaving.value = true
  try {
    await batchApi.backfill(String(target.id), {
      ...(backfillForm.value.productionDate
        ? { productionDate: backfillForm.value.productionDate }
        : {}),
      ...(backfillForm.value.expiryDate ? { expiryDate: backfillForm.value.expiryDate } : {}),
    })
    backfillVisible.value = false
    ElMessage.success(`批次 ${target.batchNo} 效期已补录，将参与下一次临期判定`)
    await refreshAll()
  } catch {
    // 40205/40206/50363/50330 全局 toast 已提示
  } finally {
    backfillSaving.value = false
  }
}

onMounted(() => {
  void fetchNames()
  void fetchDashboard()
  void fetchExpiring()
  void loadLocationConfig()
})
</script>

<template>
  <div class="ta-shell">
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #store>
        <WarehouseSwitcher />
      </template>
      <template #bell>
        <NotificationBell />
      </template>
    </AppTopbar>

    <div class="ta-body">
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
            <NavCountBadge
              v-if="m.key === '/ta/batches'"
              :count="expiringRows.length"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">批次临期</h2>
            <p class="page-head__sub">
              批次剩余为先入先出离线推算值（截至今日 02:00），可能与实物有 ≤1 天误差，清库以现场核数为准
              <el-tooltip
                content="批次是登记簿：记录每批入库量与保质期，每日离线推算剩余，驱动临期预警与强制清库；库存交易仍按商品池，不拆批次行"
                placement="bottom"
              >
                <el-icon class="head-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </p>
          </div>
          <div class="page-head__actions">
            <el-button :icon="Refresh" :loading="loading || dashboardLoading" @click="refreshAll">
              刷新
            </el-button>
          </div>
        </header>

        <!-- TA 临期看板（四卡 + bySku · 线框 §3.6-A） -->
        <section v-if="isTa" v-loading="dashboardLoading" class="card" data-test="expiry-dashboard">
          <div class="dash-head">
            <h3 class="card__title">临期看板</h3>
            <span v-if="dashboard" class="dash-threshold">
              阈值: ≤{{ dashboard.thresholdDays }} 天
              <el-button text type="primary" size="small" @click="handleMenuSelect('/ta/settings')">
                去设置
              </el-button>
            </span>
          </div>

          <div v-if="dashboard" class="kpi-row">
            <div class="kpi-card kpi-card--warning" data-test="card-expiring">
              <div class="kpi-card__value">{{ dashboard.expiringBatchCount }}</div>
              <div class="kpi-card__label">临期批次 · {{ dashboard.expiringQtyTotal }} 件*</div>
            </div>
            <div class="kpi-card kpi-card--danger" data-test="card-expired">
              <div class="kpi-card__value">{{ dashboard.expiredBatchCount }}</div>
              <div class="kpi-card__label">已过期待清理 · {{ dashboard.expiredQtyTotal }} 件*</div>
            </div>
            <div class="kpi-card kpi-card--progress" data-test="card-pending-doc">
              <div class="kpi-card__value">{{ dashboard.pendingClearanceDocCount ?? 0 }}</div>
              <div class="kpi-card__label">
                清库单待审批
                <el-button
                  v-if="(dashboard.pendingClearanceDocCount ?? 0) > 0"
                  text
                  type="primary"
                  size="small"
                  @click="handleMenuSelect('/ta/clearance')"
                >
                  去审批
                </el-button>
              </div>
            </div>
            <div class="kpi-card" data-test="card-cleared">
              <div class="kpi-card__value">{{ dashboard.clearedBatchCount }}</div>
              <div class="kpi-card__label">已清库批次（累计）</div>
            </div>
          </div>
          <p class="dash-note">* 推算值 · 截至今日 02:00</p>

          <el-table
            v-if="dashboard && dashboard.bySku.length"
            :data="dashboard.bySku"
            row-key="skuId"
            size="small"
            data-test="dashboard-by-sku"
          >
            <el-table-column label="商品" min-width="150">
              <template #default="{ row }">{{ row.skuName || skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="商户" min-width="130">
              <template #default="{ row }">{{ wholesalerLabel(row.wholesalerId) }}</template>
            </el-table-column>
            <el-table-column label="临期批次" width="90" align="right" prop="expiringBatchCount" />
            <el-table-column label="待清理批次" width="100" align="right">
              <template #default="{ row }">
                <span :class="{ 'remain-expired': row.expiredBatchCount > 0 }">
                  {{ row.expiredBatchCount }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="推算剩余合计" width="110" align="right">
              <template #default="{ row }">{{ row.remainingQtyTotal }} 件*</template>
            </el-table-column>
            <el-table-column label="最近到效期" width="120">
              <template #default="{ row }">{{ formatDate(row.nearestExpiryDate) }}</template>
            </el-table-column>
          </el-table>
          <p v-else-if="dashboard" class="dash-empty">暂无临期或待清理批次</p>
        </section>

        <!-- 预警列表 / 批次登记簿 -->
        <section class="card">
          <el-tabs v-model="activeTab" data-test="batch-tabs" @tab-change="onTabChange">
            <el-tab-pane name="expiring">
              <template #label>
                <span class="tab-label">
                  预警列表
                  <NavCountBadge :count="expiringRows.length" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="registry" label="批次登记簿" />
          </el-tabs>

          <!-- 预警列表（剩余天数升序 · 线框 §3.6-B） -->
          <el-table
            v-if="activeTab === 'expiring'"
            v-loading="loading"
            :data="expiringRows"
            row-key="id"
            data-test="expiring-table"
            empty-text="暂无临期或待清理批次"
          >
            <el-table-column label="商品" min-width="125">
              <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="商户" min-width="100">
              <template #default="{ row }">{{ wholesalerLabel(row.wholesalerId) }}</template>
            </el-table-column>
            <el-table-column prop="batchNo" label="批次号" min-width="115" />
            <el-table-column label="推算剩余" width="90" align="right">
              <template #default="{ row }">{{ row.remainingQty }} 件*</template>
            </el-table-column>
            <el-table-column label="到效期" width="100">
              <template #default="{ row }">{{ formatDate(row.expiryDate) }}</template>
            </el-table-column>
            <el-table-column label="剩余天数" width="95">
              <template #default="{ row }">
                <span :class="remainClass(row as Batch)" data-test="remaining-days">
                  {{ remainText(row as Batch) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="85">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <!-- 不用 fixed：1280 宽下 fixed 悬浮列会盖住状态列（视觉目检发现），自然滚动即可 -->
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button
                  v-if="isWk && canClear(row as Batch)"
                  type="danger"
                  size="small"
                  plain
                  data-test="start-clearance-btn"
                  @click="onStartClearance(row as Batch)"
                >
                  发起清库
                </el-button>
                <el-button
                  v-if="isWk"
                  size="small"
                  :disabled="notifyCooldownHours(row as Batch) > 0"
                  :loading="notifyingId === String(row.id)"
                  data-test="notify-btn"
                  @click="onNotify(row as Batch)"
                >
                  {{
                    notifyCooldownHours(row as Batch) > 0
                      ? `已通知 ${notifyCooldownHours(row as Batch)}h 后可再发`
                      : '通知批发商'
                  }}
                </el-button>
                <span v-if="!isWk" class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 批次登记簿（筛选 + 下钻） -->
          <template v-else>
            <div class="filter-row">
              <EntityPickerDialog
                v-model="filter.wholesalerId"
                title="选择商户"
                placeholder="全部商户"
                :columns="wholesalerPickerColumns"
                :fetch="fetchWholesalerPage"
                :selected-label="wholesalerNameMap[filter.wholesalerId] || ''"
                clearable
                class="filter-item"
                data-test="registry-wholesaler-picker"
                @change="onFilterWholesalerChange"
              />
              <EntityPickerDialog
                v-model="filter.skuId"
                title="选择商品"
                placeholder="全部商品"
                :columns="skuPickerColumns"
                :fetch="fetchSkuPage"
                :selected-label="skuLabel(filter.skuId) === filter.skuId ? '' : skuLabel(filter.skuId)"
                :disabled="!filter.wholesalerId"
                clearable
                class="filter-item"
                data-test="registry-sku-picker"
                @change="fetchRegistry"
              />
              <el-select
                v-model="filter.status"
                class="filter-item filter-item--status"
                data-test="registry-status-select"
                @change="fetchRegistry"
              >
                <el-option
                  v-for="o in STATUS_OPTIONS"
                  :key="o.value"
                  :value="o.value"
                  :label="o.label"
                />
              </el-select>
            </div>

            <el-alert
              v-if="unpooledDisplay !== null"
              type="info"
              :closable="false"
              show-icon
              class="unpooled-alert"
              data-test="unpooled-row"
              :title="`无批次在池量：${unpooledDisplay} 件（盘盈未摊完/回补时序所致，非异常）`"
            />

            <el-table
              v-loading="loading"
              :data="registryRows"
              row-key="id"
              data-test="registry-table"
              empty-text="暂无批次（开启批次管理并入库后产生）"
            >
              <el-table-column prop="batchNo" label="批次号" min-width="120" />
              <el-table-column label="商品" min-width="110">
                <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
              </el-table-column>
              <el-table-column label="商户" min-width="95">
                <template #default="{ row }">{{ wholesalerLabel(row.wholesalerId) }}</template>
              </el-table-column>
              <el-table-column label="累计入库" width="80" align="right" prop="initialQty" />
              <el-table-column label="推算剩余" width="85" align="right">
                <template #default="{ row }">{{ row.remainingQty }} 件*</template>
              </el-table-column>
              <el-table-column label="生产日期" width="100">
                <template #default="{ row }">{{ formatDate(row.productionDate) }}</template>
              </el-table-column>
              <el-table-column label="到效期" width="100">
                <template #default="{ row }">{{ formatDate(row.expiryDate) }}</template>
              </el-table-column>
              <el-table-column label="剩余天数" width="90">
                <template #default="{ row }">
                  <span :class="remainClass(row as Batch)">{{ remainText(row as Batch) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="85">
                <template #default="{ row }">
                  <el-tag :type="statusMeta(row.status).type" effect="light" round>
                    {{ statusMeta(row.status).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="来源" width="85">
                <template #default="{ row }">
                  <span class="cell-muted">{{ sourceLabel(row.source) }}</span>
                </template>
              </el-table-column>
              <!-- P5-D C2 货位：登记簿字段，任何开关状态均显示（存量空值 '—'）；移库仅 locationEnabled=1 可操作 -->
              <el-table-column label="货位" width="115">
                <template #default="{ row }">
                  <span :class="{ 'cell-muted': !row.location }" data-test="batch-location">
                    {{ row.location || '—' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="215">
                <template #default="{ row }">
                  <el-button
                    v-if="canBackfill(row as Batch)"
                    size="small"
                    type="primary"
                    plain
                    data-test="backfill-btn"
                    @click="openBackfill(row as Batch)"
                  >
                    补录效期
                  </el-button>
                  <template v-if="locationEnabled === true">
                    <el-button
                      v-if="canMoveLocation(row as Batch)"
                      size="small"
                      plain
                      data-test="move-loc-btn"
                      @click="openMove(row as Batch)"
                    >
                      移库
                    </el-button>
                    <el-button
                      size="small"
                      text
                      type="primary"
                      data-test="loc-logs-btn"
                      @click="openLogs(row as Batch)"
                    >
                      记录
                    </el-button>
                  </template>
                  <span
                    v-if="!canBackfill(row as Batch) && locationEnabled !== true"
                    class="cell-muted"
                  >
                    —
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </section>
      </main>
    </div>

    <!-- 默认批次补录弹窗（仅 source=DEFAULT 且未终态；补录后即参与临期判定） -->
    <el-dialog
      v-model="backfillVisible"
      :title="`补录效期 · ${backfillTarget?.batchNo ?? ''}`"
      width="460px"
      data-test="backfill-dialog"
      :close-on-click-modal="false"
    >
      <template v-if="backfillTarget">
        <p class="decide-meta">
          {{ skuLabel(backfillTarget.skuId) }} · {{ wholesalerLabel(backfillTarget.wholesalerId) }}
          · 推算剩余 {{ backfillTarget.remainingQty }} 件*
        </p>
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="生产日期">
            <el-date-picker
              v-model="backfillForm.productionDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="不晚于今天"
              class="full-w"
              data-test="backfill-production"
            />
          </el-form-item>
          <el-form-item label="到效期">
            <el-date-picker
              v-model="backfillForm.expiryDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="晚于生产日期"
              class="full-w"
              data-test="backfill-expiry"
            />
          </el-form-item>
        </el-form>
        <p v-if="backfillError" class="backfill-error" data-test="backfill-error">
          {{ backfillError }}
        </p>
        <p class="backfill-hint">补录后该批次即参与临期判定（02:00 推算 / 到效期归零标记）</p>
      </template>
      <template #footer>
        <el-button @click="backfillVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="Boolean(backfillError)"
          :loading="backfillSaving"
          data-test="backfill-save"
          @click="onBackfillSave"
        >
          保存补录
        </el-button>
      </template>
    </el-dialog>

    <!-- P5-D C2 移库弹窗（改 batches.location + 落 batch_location_logs；location 可清空） -->
    <el-dialog
      v-model="moveVisible"
      :title="`批次移库 · ${moveTarget?.batchNo ?? ''}`"
      width="460px"
      data-test="move-location-dialog"
      :close-on-click-modal="false"
    >
      <template v-if="moveTarget">
        <p class="decide-meta">
          {{ skuLabel(moveTarget.skuId) }} · {{ wholesalerLabel(moveTarget.wholesalerId) }}
          · 推算剩余 {{ moveTarget.remainingQty }} 件*
          · 当前货位：{{ moveTarget.location || '未指定' }}
        </p>
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="新货位">
            <el-input
              v-model="moveLocation"
              maxlength="64"
              placeholder="如 A-02-05；留空并保存=清空货位"
              data-test="move-location-input"
            />
          </el-form-item>
        </el-form>
        <p class="backfill-hint">移库仅登记批次货位与变更记录，不影响库存/批次余量（零记账副作用）</p>
      </template>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="moveSaving"
          data-test="move-location-save"
          @click="doMove"
        >
          保存移库
        </el-button>
      </template>
    </el-dialog>

    <!-- P5-D C2 移库变更记录（时间线 from→to / 操作人 / 时间；倒序最近在前） -->
    <el-drawer
      v-model="logsVisible"
      :title="`货位变更记录 · ${logsTarget?.batchNo ?? ''}`"
      size="420px"
      data-test="location-logs-drawer"
    >
      <div v-loading="logsLoading" class="logs-body">
        <p v-if="!logsLoading && !logsRows.length" class="logs-empty">暂无移库记录</p>
        <el-timeline v-else>
          <el-timeline-item
            v-for="log in logsRows"
            :key="log.id"
            :timestamp="formatDate(log.createdAt)"
            placement="top"
          >
            <p class="logs-item">
              {{ log.fromLocation || '未指定' }} → {{ log.toLocation || '未指定' }}
            </p>
            <p class="logs-item-sub">{{ skuLabel(logsTarget?.skuId ?? '') }}</p>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
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
  gap: var(--space-3);
  flex-wrap: wrap;
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
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.head-help {
  color: var(--color-fg-3);
  cursor: help;
}
.page-head__actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0;
}
.dash-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}
.dash-threshold {
  color: var(--color-fg-2);
  font-size: var(--font-size-body);
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}
.kpi-card {
  background: var(--color-bg-2);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.kpi-card__value {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-display);
  font-weight: var(--font-weight-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--color-fg-1);
}
.kpi-card--warning .kpi-card__value {
  color: var(--color-warning);
}
.kpi-card--danger .kpi-card__value {
  color: var(--color-danger);
}
.kpi-card--progress .kpi-card__value {
  color: var(--color-brand-accent);
}
.kpi-card__label {
  font-size: var(--font-size-caption);
  color: var(--color-fg-2);
}
.dash-note {
  margin: var(--space-2) 0 var(--space-3);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.dash-empty {
  margin: var(--space-3) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-body);
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}
.filter-row {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}
.filter-item {
  min-width: 200px;
}
.filter-item--status {
  min-width: 140px;
}
.unpooled-alert {
  margin-bottom: var(--space-3);
}
.cell-muted {
  color: var(--color-fg-3);
}
.remain-expired {
  color: var(--color-danger);
  font-weight: var(--font-weight-semibold);
}
.remain-warn {
  color: var(--color-warning);
  font-weight: var(--font-weight-medium);
}
.remain-none {
  color: var(--color-fg-3);
}
.decide-meta {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
}
.backfill-error {
  margin: 0 0 var(--space-2);
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}
.backfill-hint {
  margin: 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.full-w {
  width: 100%;
}
.logs-body {
  min-height: 120px;
}
.logs-empty {
  color: var(--color-fg-3);
  text-align: center;
  margin-top: 24px;
}
.logs-item {
  margin: 0 0 4px;
  font-weight: 600;
}
.logs-item-sub {
  margin: 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
@media (max-width: 1024px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .ta-main {
    padding: var(--space-4);
    min-width: 0;
  }
  .kpi-row {
    grid-template-columns: 1fr;
  }
}
</style>
