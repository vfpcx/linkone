<script setup lang="ts">
/**
 * WK 出库作业流（TA 端 · P3 FE-W2 · 12 §1.2/§3.1/§3.3）
 *
 * 契约（权威：TenantOutboundController，据实查证）：
 *  - GET  /tenant/outbound-requests?status=&page=&size=（MpPage<OutboundRequest>）
 *  - POST /{id}/print              首打 PENDING_ACCEPT→PRINTED / 补打 count++ 不迁移
 *  - POST /{id}/revert-to-pending  重新核对回退（清撤回申请标记）
 *  - POST /{id}/register           登记出库 PRINTED→COMPLETED（+询价终态联动）
 *  - POST /{id}/confirm-withdraw   R4 确认撤回 PRINTED→CANCELLED + 回补（无申请 50336）
 *  - POST /{id}/reject-withdraw    R4 拒绝撤回（清 flag，单据继续履约）
 *  - POST /tenant/wk/outbound-requests  代建出库（confirmed=true 凭据 + 复述件数，50338）
 *
 * 产品口径：
 *  - 待受理必须先打印纸质单才能登记出库（状态机红线：PENDING_ACCEPT 不能直达 COMPLETED）；
 *  - 打印视图 window.print()（打印样式只输出票面区域）；
 *  - 商户申请撤回的已打印单（withdrawRequested=1）需 WK 现场核对纸质单后确认/拒绝；
 *  - 代建出库直达「已出库」，商户可在出库后 30 天内客诉。
 *
 * 视觉：沿用 ta/Approvals.vue 顶栏（AppTopbar + WarehouseSwitcher）+ 左侧菜单 shell。
 */

import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
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
  Printer,
  Van,
  Plus,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  NavCountBadge,
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type {
  OutboundRequest,
  OutboundStatus,
  OutboundSource,
  Wholesaler,
  Sku,
  InventoryItem,
  WkOutboundCreateRequest,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { tenantOutboundApi } from '@/api/outbound'
import { inventoryApi } from '@/api/inventory'
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
  '/ta/approvals',
])

const activeMenu = ref('/ta/outbound')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/outbound') {
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
type BadgeType = 'warning' | 'primary' | 'success' | 'info' | 'danger'
const STATUS_META: Record<OutboundStatus, { label: string; type: BadgeType }> = {
  PENDING_ACCEPT: { label: '待受理', type: 'warning' },
  PRINTED: { label: '已打印', type: 'primary' },
  COMPLETED: { label: '已出库', type: 'success' },
  WITHDRAWN: { label: '已撤回', type: 'info' },
  CANCELLED: { label: '已取消', type: 'info' },
  COMPLAINED: { label: '客诉处理中', type: 'danger' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as OutboundStatus] ?? { label: s, type: 'info' as BadgeType }

const SOURCE_LABEL: Record<OutboundSource, string> = {
  INQUIRY_AUTO: '询价确认',
  WA_SUBMIT: '商户提交',
  WK_CREATED: '仓库代建',
}
const sourceLabel = (s: string | null) =>
  s ? (SOURCE_LABEL[s as OutboundSource] ?? s) : '—'

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

// ============ 列表 ============
const TAB_PENDING = 'PENDING_ACCEPT'
const TAB_PRINTED = 'PRINTED'
const TAB_COMPLETED = 'COMPLETED'
const TAB_ALL = 'ALL'
const activeTab = ref<string>(TAB_PENDING)

const loading = ref(false)
const rows = ref<OutboundRequest[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
/** 待受理角标（菜单 + 页签） */
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await tenantOutboundApi.list({
      status: activeTab.value === TAB_ALL ? undefined : activeTab.value,
      page: page.value,
      size,
    })
    rows.value = data.records ?? []
    total.value = Number(data.total) || 0
    if (activeTab.value === TAB_PENDING) {
      pendingCount.value = total.value
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

/** 角标独立拉取（当前页签非待受理时保持角标准确） */
const fetchPendingCount = async () => {
  try {
    const data = await tenantOutboundApi.list({ status: TAB_PENDING, page: 1, size: 1 })
    pendingCount.value = Number(data.total) || 0
  } catch {
    /* 静默 */
  }
}

const refreshAll = () => Promise.all([fetchList(), fetchPendingCount()])

const onTabChange = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

// ============ 打印视图（window.print） ============
const printVisible = ref(false)
const printTarget = ref<OutboundRequest | null>(null)
const printingId = ref('')

/** 打印/补打：先走后端迁移/计数，成功后展示可打印票面 */
const onPrint = async (row: OutboundRequest) => {
  printingId.value = String(row.id)
  try {
    const updated = await tenantOutboundApi.print(String(row.id))
    printTarget.value = updated
    printVisible.value = true
    await refreshAll()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      await refreshAll()
    }
  } finally {
    printingId.value = ''
  }
}

const doWindowPrint = () => {
  window.print()
}

// ============ 登记出库 ============
const registeringId = ref('')

const onRegister = async (row: OutboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `确认已核对纸质单并完成实物出库？出库单 ${row.docNo}（${row.qty} 件）登记后完成，商户不可再撤回。`,
      '登记出库',
      { confirmButtonText: '登记出库', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  registeringId.value = String(row.id)
  try {
    const updated = await tenantOutboundApi.register(String(row.id))
    ElMessage.success(`出库单 ${updated.docNo} 已登记出库`)
    await refreshAll()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      await refreshAll()
    }
  } finally {
    registeringId.value = ''
  }
}

// ============ 重新核对回退 ============
const revertingId = ref('')

const onRevert = async (row: OutboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `将出库单 ${row.docNo} 回退到「待受理」重新核对？${
        row.withdrawRequested === 1 ? '商户的撤回申请标记将一并清除。' : ''
      }`,
      '重新核对',
      { confirmButtonText: '回退', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  revertingId.value = String(row.id)
  try {
    await tenantOutboundApi.revertToPending(String(row.id))
    ElMessage.success(`出库单 ${row.docNo} 已回退到待受理`)
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_DOC_CAS_CONFLICT) {
      await refreshAll()
    }
  } finally {
    revertingId.value = ''
  }
}

// ============ R4 撤回二次确认 / 拒绝 ============
const withdrawActingId = ref('')

const onConfirmWithdraw = async (row: OutboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `同意商户撤回出库单 ${row.docNo}？请先收回现场纸质单。确认后单据取消、库存回补 ${row.qty} 件。`,
      '确认撤回',
      { confirmButtonText: '同意撤回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  withdrawActingId.value = String(row.id)
  try {
    const updated = await tenantOutboundApi.confirmWithdraw(String(row.id))
    ElMessage.success(`出库单 ${updated.docNo} 已撤销，库存已回补`)
    await refreshAll()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_OUTBOUND_NO_WITHDRAW_REQUEST ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      await refreshAll()
    }
  } finally {
    withdrawActingId.value = ''
  }
}

const onRejectWithdraw = async (row: OutboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `拒绝出库单 ${row.docNo} 的撤回申请？纸质单继续作业，单据继续履约（商户将收到通知）。`,
      '拒绝撤回',
      { confirmButtonText: '拒绝撤回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  withdrawActingId.value = String(row.id)
  try {
    await tenantOutboundApi.rejectWithdraw(String(row.id))
    ElMessage.success(`已拒绝撤回，出库单 ${row.docNo} 继续履约`)
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_OUTBOUND_NO_WITHDRAW_REQUEST) {
      await refreshAll()
    }
  } finally {
    withdrawActingId.value = ''
  }
}

// ============ 代建出库（US-WK-02b） ============
const proxyVisible = ref(false)
const proxySubmitting = ref(false)
const proxyFormRef = ref<FormInstance>()

const proxyForm = reactive({
  wholesalerId: '' as string,
  skuId: '' as string,
  qty: undefined as number | undefined,
  palletQty: undefined as number | undefined,
})

const proxyRules: FormRules = {
  wholesalerId: [{ required: true, message: '请选择商户', trigger: 'change' }],
  skuId: [{ required: true, message: '请选择商品 SKU', trigger: 'change' }],
  qty: [
    { required: true, message: '请输入出库数量', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || (v as unknown) === '') {
          cb(new Error('请输入出库数量'))
        } else if (!Number.isInteger(Number(v)) || Number(v) <= 0) {
          cb(new Error('出库数量必须为大于 0 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  palletQty: [
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || (v as unknown) === '') {
          cb()
        } else if (!Number.isInteger(Number(v)) || Number(v) < 0) {
          cb(new Error('托盘数须为不小于 0 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}

// 商户选择器（开放实体集 → EntityPickerDialog，UX 规范 2026-07-25）
const wholesalers = ref<Wholesaler[]>([])
const wholesalerNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const w of wholesalers.value) map[String(w.id)] = w.name
  return map
})

const fetchWholesalers = async () => {
  try {
    wholesalers.value = await wholesalerApi.list()
  } catch {
    // 全局 toast 已提示
  }
}

const wholesalerPickerColumns: EntityPickerColumn<Wholesaler>[] = [
  { label: '商户名称', prop: 'name', minWidth: 160 },
  { label: '简介', formatter: (w) => w.intro || '—', minWidth: 160 },
  { label: '创建时间', formatter: (w) => String(w.createdAt ?? '').slice(0, 10), width: 110 },
]

const fetchWholesalerPage = makeClientPickerFetch<Wholesaler>(
  () => wholesalers.value,
  (w, kw) => w.name.toLowerCase().includes(kw) || (w.intro ?? '').toLowerCase().includes(kw),
)

/**
 * SKU 选择器（依赖已选商户）——数据源为库存行（GET /tenant/inventories）+ SKU 名称
 * （GET /tenant/skus，P3b T1-BE 起只读列表已放行库管员 requireWkOrWaOrTa，
 * 上线检查单 §5-4 遗留缺陷 V-3 消除）：名称/规格经 skuNameMap 联查展示。
 */
const invRows = ref<InventoryItem[]>([])
/** 商户 SKU 名称映射（skuId → 名称（规格）） */
const proxySkuNameMap = ref<Record<string, string>>({})

const fetchInvRows = async () => {
  if (!proxyForm.wholesalerId) {
    invRows.value = []
    proxySkuNameMap.value = {}
    return
  }
  try {
    const [inv, skus] = await Promise.all([
      inventoryApi.query({ wholesalerId: proxyForm.wholesalerId }),
      skuApi.list(proxyForm.wholesalerId).catch(() => [] as Sku[]),
    ])
    invRows.value = inv
    const map: Record<string, string> = {}
    for (const s of skus) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
    proxySkuNameMap.value = map
  } catch {
    // 全局 toast 已提示
  }
}

const proxySkuLabel = (skuId: unknown): string =>
  proxySkuNameMap.value[String(skuId)] || String(skuId)

const skuPickerColumns: EntityPickerColumn<InventoryItem>[] = [
  { label: '商品名称', formatter: (r) => proxySkuLabel(r.skuId), minWidth: 200 },
  { label: '在库件数', formatter: (r) => String(r.qty ?? 0), width: 100, align: 'right' },
  { label: '托盘', formatter: (r) => String(r.palletQty ?? 0), width: 80, align: 'right' },
]

const fetchSkuPage = makeClientPickerFetch<InventoryItem>(
  () => invRows.value,
  (r, kw) =>
    String(r.skuId).toLowerCase().includes(kw) ||
    proxySkuLabel(r.skuId).toLowerCase().includes(kw),
)

/** 回显 SKU 名称（名称缺失时回退编号） */
const selectedSkuLabel = computed(() =>
  proxyForm.skuId ? proxySkuLabel(proxyForm.skuId) : '',
)

const onProxyWholesalerChange = async () => {
  proxyForm.skuId = ''
  onhand.value = null
  await fetchInvRows()
}

// 当前在库（大额预警辅助展示；真正校验以后端锁内为准）
const onhand = ref<number | null>(null)

const fetchOnhand = async () => {
  if (!proxyForm.wholesalerId || !proxyForm.skuId) {
    onhand.value = null
    return
  }
  try {
    const list = await inventoryApi.query({
      wholesalerId: proxyForm.wholesalerId,
      skuId: proxyForm.skuId,
    })
    onhand.value = list.length > 0 ? Number(list[0].qty) || 0 : 0
  } catch {
    onhand.value = null
  }
}

watch(() => proxyForm.skuId, fetchOnhand)

/** 大额（qty > 在库×50%，整数口径 qty×2 > onhand）→ 弹窗内红色警示 */
const isLargeQty = computed(
  () =>
    onhand.value !== null &&
    proxyForm.qty !== undefined &&
    Number(proxyForm.qty) * 2 > onhand.value,
)

const openProxyDialog = () => {
  proxyForm.wholesalerId = ''
  proxyForm.skuId = ''
  proxyForm.qty = undefined
  proxyForm.palletQty = undefined
  onhand.value = null
  invRows.value = []
  proxyVisible.value = true
  proxyFormRef.value?.clearValidate()
  if (wholesalers.value.length === 0) void fetchWholesalers()
}

// 显著二次确认（复述件数 → confirmed=true 凭据 + restatedQty）
const restateVisible = ref(false)
const restateQty = ref<number | undefined>(undefined)

const onProxyNext = async () => {
  if (!proxyFormRef.value) return
  const valid = await proxyFormRef.value.validate().catch(() => false)
  if (!valid) return
  restateQty.value = undefined
  restateVisible.value = true
}

const onProxySubmit = async () => {
  if (restateQty.value === undefined || Number(restateQty.value) !== Number(proxyForm.qty)) {
    ElMessage.warning('复述件数与出库件数不一致，请重新核对')
    return
  }
  const payload: WkOutboundCreateRequest = {
    wholesalerId: proxyForm.wholesalerId,
    skuId: proxyForm.skuId,
    qty: Number(proxyForm.qty),
    confirmed: true,
    restatedQty: Number(restateQty.value),
  }
  if (proxyForm.palletQty !== undefined && proxyForm.palletQty !== null) {
    payload.palletQty = Number(proxyForm.palletQty)
  }
  proxySubmitting.value = true
  try {
    const created = await tenantOutboundApi.createByWk(payload)
    restateVisible.value = false
    proxyVisible.value = false
    ElMessage.success(
      `代建出库已登记（单号 ${created.docNo}），商户可在出库后 30 天内发起客诉`,
    )
    activeTab.value = TAB_COMPLETED
    page.value = 1
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_OUTBOUND_LARGE_CONFIRM_REQUIRED) {
      // 50338：复述凭据未过（理论上前端已闸；留兜底提示）
      ElMessage.warning('大额出库需复述件数确认，请核对后重试')
    }
    // 50251 库存不足等由全局拦截器 toast
  } finally {
    proxySubmitting.value = false
  }
}

onMounted(() => {
  void refreshAll()
  void fetchWholesalers()
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
              v-if="m.key === '/ta/outbound'"
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
            <h2 class="page-head__title">出库作业</h2>
            <p class="page-head__sub">
              待受理单先打印纸质单，核对装车后登记出库；商户申请撤回的已打印单需现场核对后确认或拒绝
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              type="primary"
              :icon="Plus"
              data-test="proxy-create-btn"
              @click="openProxyDialog"
            >
              代建出库
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="outbound-tabs" @tab-change="onTabChange">
            <el-tab-pane :name="TAB_PENDING">
              <template #label>
                <span class="tab-label">
                  待受理
                  <NavCountBadge :count="pendingCount" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已打印" :name="TAB_PRINTED" />
            <el-tab-pane label="已完成" :name="TAB_COMPLETED" />
            <el-tab-pane label="全部" :name="TAB_ALL" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="outbound-table"
            data-test="outbound-table"
            :empty-text="activeTab === TAB_PENDING ? '暂无待受理出库单' : '暂无出库单'"
          >
            <el-table-column prop="docNo" label="出库单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
                <el-tag
                  v-if="row.withdrawRequested === 1 && row.status === 'PRINTED'"
                  type="danger"
                  size="small"
                  effect="plain"
                  class="flag-tag"
                  data-test="withdraw-flag-tag"
                >
                  商户申请撤回
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="商户" min-width="130">
              <template #default="{ row }">{{ row.wholesalerName || '—' }}</template>
            </el-table-column>
            <el-table-column label="SKU" min-width="140">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="90" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="110">
              <template #default="{ row }">
                <span :class="row.source === 'WK_CREATED' ? 'cell-name' : 'cell-muted'">
                  {{ sourceLabel(row.source) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="打印" width="100" align="center">
              <template #default="{ row }">
                <el-tooltip
                  v-if="(row.printCount ?? 0) > 0"
                  :content="`首打时间 ${formatTime(row.printedAt)}`"
                  placement="top"
                >
                  <span class="cell-muted">{{ row.printCount }} 次</span>
                </el-tooltip>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <!-- 待受理：打印 -->
                <el-button
                  v-if="row.status === 'PENDING_ACCEPT'"
                  type="primary"
                  size="small"
                  :icon="Printer"
                  :loading="printingId === String(row.id)"
                  data-test="print-btn"
                  @click="onPrint(row as OutboundRequest)"
                >
                  打印
                </el-button>

                <!-- 已打印 + 商户申请撤回：确认 / 拒绝 -->
                <template v-else-if="row.status === 'PRINTED' && row.withdrawRequested === 1">
                  <el-button
                    type="danger"
                    size="small"
                    :loading="withdrawActingId === String(row.id)"
                    data-test="confirm-withdraw-btn"
                    @click="onConfirmWithdraw(row as OutboundRequest)"
                  >
                    确认撤回
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :disabled="withdrawActingId === String(row.id)"
                    data-test="reject-withdraw-btn"
                    @click="onRejectWithdraw(row as OutboundRequest)"
                  >
                    拒绝撤回
                  </el-button>
                </template>

                <!-- 已打印：登记出库 / 补打 / 重新核对 -->
                <template v-else-if="row.status === 'PRINTED'">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="registeringId === String(row.id)"
                    data-test="register-btn"
                    @click="onRegister(row as OutboundRequest)"
                  >
                    登记出库
                  </el-button>
                  <el-button
                    size="small"
                    :loading="printingId === String(row.id)"
                    data-test="reprint-btn"
                    @click="onPrint(row as OutboundRequest)"
                  >
                    补打
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="warning"
                    :loading="revertingId === String(row.id)"
                    data-test="revert-btn"
                    @click="onRevert(row as OutboundRequest)"
                  >
                    重新核对
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

    <!-- 打印视图（票面区域走 @media print 输出） -->
    <el-dialog
      v-model="printVisible"
      title="出库单打印"
      width="640px"
      :close-on-click-modal="false"
      data-test="print-dialog"
    >
      <div v-if="printTarget" class="print-sheet" data-test="print-sheet">
        <h3 class="print-sheet__title">出库单</h3>
        <p class="print-sheet__doc-no">{{ printTarget.docNo }}</p>
        <table class="print-sheet__table">
          <tbody>
            <tr>
              <th>商户</th>
              <td>{{ printTarget.wholesalerName || printTarget.wholesalerId }}</td>
              <th>来源</th>
              <td>{{ sourceLabel(printTarget.source) }}</td>
            </tr>
            <tr>
              <th>SKU 编号</th>
              <td>{{ printTarget.skuId }}</td>
              <th>出库数量</th>
              <td class="print-sheet__qty">{{ printTarget.qty }} 件</td>
            </tr>
            <tr>
              <th>托盘数</th>
              <td>{{ printTarget.palletQty ?? 0 }}</td>
              <th>打印次数</th>
              <td>第 {{ printTarget.printCount ?? 1 }} 次</td>
            </tr>
            <tr>
              <th>创建时间</th>
              <td>{{ formatTime(printTarget.createdAt) }}</td>
              <th>打印时间</th>
              <td>{{ formatTime(printTarget.printedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div class="print-sheet__signs">
          <span>库管员签字：__________________</span>
          <span>提货人签字：__________________</span>
        </div>
        <p class="print-sheet__note">
          请凭本单核对装车；登记出库后单据完成。补打单据以最新打印时间为准。
        </p>
      </div>
      <template #footer>
        <el-button @click="printVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Printer" data-test="do-print-btn" @click="doWindowPrint">
          打印
        </el-button>
      </template>
    </el-dialog>

    <!-- 代建出库表单 -->
    <el-dialog
      v-model="proxyVisible"
      title="代建出库（仓库代商户登记）"
      width="560px"
      :close-on-click-modal="false"
      data-test="proxy-dialog"
    >
      <el-alert type="warning" :closable="false" class="proxy-alert">
        代建出库提交即扣减库存并直达「已出库」，商户可在出库后 30 天内发起客诉，请务必与商户当面/电话核实。
      </el-alert>
      <el-form
        ref="proxyFormRef"
        :model="proxyForm"
        :rules="proxyRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="商户" prop="wholesalerId">
          <EntityPickerDialog
            v-model="proxyForm.wholesalerId"
            title="选择商户"
            placeholder="点击选择商户"
            :columns="wholesalerPickerColumns"
            :fetch="fetchWholesalerPage"
            :selected-label="wholesalerNameMap[proxyForm.wholesalerId] || ''"
            class="full-width"
            @change="onProxyWholesalerChange"
          />
        </el-form-item>
        <el-form-item label="商品 SKU（在库）" prop="skuId">
          <EntityPickerDialog
            v-model="proxyForm.skuId"
            title="选择在库 SKU"
            placeholder="点击选择在库 SKU"
            row-key="skuId"
            label-key="skuId"
            :columns="skuPickerColumns"
            :fetch="fetchSkuPage"
            :selected-label="selectedSkuLabel"
            :disabled="!proxyForm.wholesalerId"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="出库数量" prop="qty">
          <el-input-number
            v-model="proxyForm.qty"
            :min="1"
            :precision="0"
            :step="1"
            :controls="false"
            placeholder="必填，大于 0 的整数"
            class="full-width"
            data-test="proxy-qty"
          />
          <div v-if="onhand !== null" class="onhand-hint" data-test="onhand-hint">
            当前在库 {{ onhand }} 件
            <span v-if="isLargeQty" class="onhand-hint--warn">
              ⚠️ 超过在库一半，属大额出库，下一步需复述件数
            </span>
          </div>
        </el-form-item>
        <el-form-item label="托盘数（可选）" prop="palletQty">
          <el-input-number
            v-model="proxyForm.palletQty"
            :min="0"
            :precision="0"
            :step="1"
            :controls="false"
            placeholder="本次托盘数，默认 0"
            class="full-width"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="proxyVisible = false">取消</el-button>
        <el-button type="primary" data-test="proxy-next" @click="onProxyNext">下一步</el-button>
      </template>
    </el-dialog>

    <!-- 代建出库 · 显著二次确认（复述件数 = confirmed 凭据 + restatedQty） -->
    <el-dialog
      v-model="restateVisible"
      title="⚠️ 代建出库二次确认"
      width="460px"
      :close-on-click-modal="false"
      data-test="restate-dialog"
    >
      <el-descriptions :column="1" size="small" border class="restate-info">
        <el-descriptions-item label="商户">
          {{ wholesalerNameMap[proxyForm.wholesalerId] || proxyForm.wholesalerId }}
        </el-descriptions-item>
        <el-descriptions-item label="商品">{{ selectedSkuLabel }}</el-descriptions-item>
        <el-descriptions-item label="出库数量">
          <span class="restate-qty">{{ proxyForm.qty }} 件</span>
          <span v-if="onhand !== null" class="cell-muted">（当前在库 {{ onhand }} 件）</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" class="restate-form" @submit.prevent>
        <el-form-item label="请复述出库件数（须与上方数量一致）" required>
          <el-input-number
            v-model="restateQty"
            :min="1"
            :precision="0"
            :step="1"
            :controls="false"
            placeholder="重新输入出库件数"
            class="full-width"
            data-test="restate-input"
          />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false">
        提交后立即扣减库存并登记「已出库」，不可撤回；商户可在 30 天内发起客诉。
      </el-alert>
      <template #footer>
        <el-button :disabled="proxySubmitting" @click="restateVisible = false">返回修改</el-button>
        <el-button
          type="danger"
          :loading="proxySubmitting"
          data-test="restate-confirm"
          @click="onProxySubmit"
        >
          确认登记出库
        </el-button>
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

.outbound-table {
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
.flag-tag {
  margin-left: var(--space-1);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

/* ===== 打印票面 ===== */
.print-sheet {
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  background: #fff;
  color: #1f2937;
}
.print-sheet__title {
  margin: 0;
  text-align: center;
  font-size: 20px;
  letter-spacing: 8px;
}
.print-sheet__doc-no {
  margin: var(--space-2) 0 var(--space-4);
  text-align: center;
  font-family: var(--font-family-mono);
  font-size: 15px;
  color: #374151;
}
.print-sheet__table {
  width: 100%;
  border-collapse: collapse;
}
.print-sheet__table th,
.print-sheet__table td {
  border: 1px solid #d1d5db;
  padding: 8px 12px;
  font-size: 13px;
  text-align: left;
}
.print-sheet__table th {
  width: 88px;
  background: #f9fafb;
  color: #6b7280;
  font-weight: 500;
}
.print-sheet__qty {
  font-weight: 700;
  font-size: 15px;
}
.print-sheet__signs {
  display: flex;
  justify-content: space-between;
  gap: var(--space-4);
  margin-top: var(--space-6);
  font-size: 13px;
}
.print-sheet__note {
  margin: var(--space-4) 0 0;
  font-size: 12px;
  color: #9ca3af;
}

/* ===== 代建出库 ===== */
.proxy-alert {
  margin-bottom: var(--space-4);
}
.onhand-hint {
  width: 100%;
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
  line-height: 1.6;
}
.onhand-hint--warn {
  color: var(--color-danger);
}
.restate-info {
  margin-bottom: var(--space-3);
}
.restate-qty {
  font-weight: var(--font-weight-bold);
  color: var(--color-danger);
  margin-right: var(--space-2);
}
.restate-form {
  margin-bottom: var(--space-3);
}
.full-width {
  width: 100%;
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

<!-- 打印样式：仅输出票面区域（el-dialog teleport 到 body，需非 scoped） -->
<style>
@media print {
  body * {
    visibility: hidden !important;
  }
  .print-sheet,
  .print-sheet * {
    visibility: visible !important;
  }
  .print-sheet {
    position: fixed !important;
    inset: 0 auto auto 0 !important;
    width: 100% !important;
    border: none !important;
    box-shadow: none !important;
  }
}
</style>
