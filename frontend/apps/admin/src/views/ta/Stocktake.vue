<script setup lang="ts">
/**
 * 盘点单（TA 端 · WK 建/提 + TA 审批 · P3b T3-FE · 11 PRD §2.2/§2.4-C 线框）
 *
 * 契约（权威：TenantStocktakeController，据实查证）：
 *  - POST   /tenant/count-sheets           WK 建草稿（同商户在途至多一张 50356；明细 50355，≤200 行）
 *  - PUT    /tenant/count-sheets/{id}      WK 编辑（DRAFT 直改 / REJECTED 改回 DRAFT 重提；items 全量替换）
 *  - DELETE /tenant/count-sheets/{id}      WK 删除草稿（仅 DRAFT）
 *  - POST   /{id}/submit                   提交（system_qty 提交时刻快照定格）→ 通知 TA
 *  - GET    /tenant/count-sheets?status=   列表（待审批创建升序先到先审）
 *  - GET    /in-transit-hint?wholesalerId= 在途提示条聚合（护栏，13 §2.2）
 *  - GET    /{id}                          详情（items 含 currentStock/suggestedPalletRelease + inTransitHint）
 *  - POST   /{id}/decide                   TA 审批（APPROVED 逐 SKU 锁内 GAIN/LOSS；REJECTED remark 必填）
 *
 * 产品口径（11 §2.2）：
 *  - 系统数=账面库存（已扣后）：实物 > 账面是正常现象，不是盘盈；在途提示条为必做护栏；
 *  - 两时点语义分离：差异以提交时刻快照为准；盘亏生效量以审批时刻在库封顶（D-10），
 *    审批弹窗实时预览 min(|盘亏|, currentStock)，差额高亮并写备注线下核查；
 *  - 驳回（理由必填）→ 编辑改回草稿重提；已通过不可逆；始终按 SKU 总数盘（批次分支隐藏）。
 *
 * 视觉：沿用 ta/Outbound.vue 顶栏 + 左侧菜单 shell；审批弹窗沿 06 §2.3 样式。
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
  Plus,
  Checked,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  NavCountBadge,
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type {
  CountSheet,
  CountSheetItem,
  CountSheetItemInput,
  CountSheetStatus,
  InventoryItem,
  Sku,
  StocktakeInTransitHint,
  Wholesaler,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import { stocktakeApi } from '@/api/stocktake'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { inventoryApi } from '@/api/inventory'
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
  { key: '/ta/returns', label: '退货受理', icon: RefreshLeft },
  { key: '/ta/stocktake', label: '盘点', icon: Checked },
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
  '/ta/returns',
  '/ta/approvals',
])

const activeMenu = ref('/ta/stocktake')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/stocktake') {
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
const STATUS_META: Record<CountSheetStatus, { label: string; type: BadgeType }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  REJECTED: { label: '已驳回', type: 'danger' },
  APPROVED: { label: '已通过', type: 'success' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as CountSheetStatus] ?? { label: s, type: 'info' as BadgeType }

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

/** 差异展示：+N 盘盈 / −N 盘亏 / 0 无差异 */
const diffText = (diff: number): string =>
  diff > 0 ? `盘盈 ${diff} 件` : diff < 0 ? `盘亏 ${-diff} 件` : '无差异'

// ============ 名称回显 ============
const wholesalers = ref<Wholesaler[]>([])
const wholesalerNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const w of wholesalers.value) map[String(w.id)] = w.name
  return map
})
const wholesalerLabel = (id: unknown): string =>
  wholesalerNameMap.value[String(id)] || String(id)

const fetchWholesalers = async () => {
  try {
    wholesalers.value = await wholesalerApi.list()
  } catch {
    // 全局 toast 已提示
  }
}

// ============ 列表 ============
const TAB_PENDING = 'PENDING_APPROVAL'
const TABS: Array<{ name: string; label: string }> = [
  { name: TAB_PENDING, label: '待审批' },
  { name: 'DRAFT', label: '草稿' },
  { name: 'REJECTED', label: '已驳回' },
  { name: 'APPROVED', label: '已通过' },
  { name: 'ALL', label: '全部' },
]
const activeTab = ref(TAB_PENDING)
const loading = ref(false)
const rows = ref<CountSheet[]>([])
/** 待审批角标 */
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const list = await stocktakeApi.list({
      status: activeTab.value === 'ALL' ? undefined : activeTab.value,
    })
    rows.value = list
    if (activeTab.value === TAB_PENDING) pendingCount.value = list.length
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const fetchPendingCount = async () => {
  try {
    const list = await stocktakeApi.list({ status: TAB_PENDING })
    pendingCount.value = list.length
  } catch {
    /* 静默 */
  }
}

const refreshAll = () => Promise.all([fetchList(), fetchPendingCount()])

const onTabChange = () => {
  void fetchList()
}

// ============ 录入页数据源（选定商户后拉 SKU / 库存 / 在途提示） ============
const skus = ref<Sku[]>([])
const inventories = ref<InventoryItem[]>([])
const editorHint = ref<StocktakeInTransitHint | null>(null)

const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
  return map
})
const skuLabel = (id: unknown): string => skuNameMap.value[String(id)] || String(id)

/** skuId → 当前账面（录入页「账面数自动带出」数据源；提交时后端重快照定格） */
const stockMap = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const inv of inventories.value) map[String(inv.skuId)] = Number(inv.qty) || 0
  return map
})

const loadEditorSources = async (wholesalerId: string) => {
  try {
    const [skuList, invList, hint] = await Promise.all([
      skuApi.list(wholesalerId),
      inventoryApi.query({ wholesalerId }),
      stocktakeApi.inTransitHint(wholesalerId),
    ])
    skus.value = skuList
    inventories.value = invList
    editorHint.value = hint
  } catch {
    // 全局 toast 已提示
  }
}

/** 在途提示条文案（出库在途 + 已受理退货在途，均为 0 时不展示） */
const hintText = (hint: StocktakeInTransitHint | null): string => {
  if (!hint) return ''
  const parts: string[] = []
  if (hint.outboundDocCount > 0) {
    parts.push(`${hint.outboundDocCount} 张已确认未出库单据（合计 ${hint.outboundQtyTotal} 件）`)
  }
  if (hint.returnDocCount > 0) {
    parts.push(`${hint.returnDocCount} 张已受理未登记退货单（${hint.returnQtyTotal} 件）`)
  }
  if (parts.length === 0) return ''
  return `本商户当前有 ${parts.join('、')}，实物与账面的差异可能为在途占用，建议避开作业时段盘点`
}

/** 某 SKU 的在途占用件数（出库在途 + 退货在途；差异列旁标注用） */
const inTransitOfSku = (hint: StocktakeInTransitHint | null, skuId: unknown): number => {
  if (!hint) return 0
  const key = String(skuId)
  return (hint.skuOutboundQty?.[key] ?? 0) + (hint.skuReturnQty?.[key] ?? 0)
}

// ============ 盘点单编辑器（新建 / 草稿编辑 / 驳回重改） ============
interface EditorItem {
  skuId: string
  actualQty: number | undefined
  /** undefined=按默认建议值；数字（含 0）=WK 覆盖 */
  palletDelta: number | undefined
  remark: string
}

const editorVisible = ref(false)
/** null=新建 */
const editingId = ref<string | null>(null)
const editingStatus = ref<string>('')
const editorSaving = ref(false)
const editorSubmitting = ref(false)
const editorForm = ref({
  wholesalerId: '',
  remark: '',
  attachments: [] as string[],
  items: [] as EditorItem[],
})

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
  {
    label: '当前账面',
    formatter: (s) => `${stockMap.value[String(s.id)] ?? 0} 件`,
    width: 100,
    align: 'right',
  },
]
const fetchSkuPage = makeClientPickerFetch<Sku>(
  () => skus.value,
  (s, kw) => s.name.toLowerCase().includes(kw) || (s.spec ?? '').toLowerCase().includes(kw),
)

const onEditorWholesalerChange = async () => {
  editorForm.value.items = []
  editorHint.value = null
  if (editorForm.value.wholesalerId) {
    await loadEditorSources(editorForm.value.wholesalerId)
  }
}

const addItemRow = () => {
  editorForm.value.items.push({ skuId: '', actualQty: undefined, palletDelta: undefined, remark: '' })
}

const removeItemRow = (idx: number) => {
  editorForm.value.items.splice(idx, 1)
}

/** 全仓便利：一键载入该商户全部在库 > 0 的 SKU（实物数留空逐行录入） */
const loadAllInStock = async () => {
  // 选商户后的数据源加载可能尚未返回：为空时就地重拉一次（防连点竞态）
  if (inventories.value.length === 0 && editorForm.value.wholesalerId) {
    await loadEditorSources(editorForm.value.wholesalerId)
  }
  const existing = new Set(editorForm.value.items.map((i) => i.skuId))
  let added = 0
  for (const inv of inventories.value) {
    const sid = String(inv.skuId)
    if (Number(inv.qty) > 0 && !existing.has(sid)) {
      editorForm.value.items.push({ skuId: sid, actualQty: undefined, palletDelta: undefined, remark: '' })
      added += 1
    }
  }
  if (added === 0) ElMessage.info('没有可追加的在库商品')
}

/** 行差异（录入页实时预览；账面取当前值，提交时后端重快照） */
const rowDiff = (item: EditorItem): number | null => {
  if (!item.skuId || item.actualQty === undefined) return null
  return Number(item.actualQty) - (stockMap.value[item.skuId] ?? 0)
}

/** 同单 SKU 重复（50355 前端预检） */
const duplicateSkuIds = computed<Set<string>>(() => {
  const seen = new Set<string>()
  const dup = new Set<string>()
  for (const i of editorForm.value.items) {
    if (!i.skuId) continue
    if (seen.has(i.skuId)) dup.add(i.skuId)
    seen.add(i.skuId)
  }
  return dup
})

const canSaveEditor = computed(
  () =>
    Boolean(editorForm.value.wholesalerId) &&
    editorForm.value.items.length >= 1 &&
    editorForm.value.items.every(
      (i) => Boolean(i.skuId) && i.actualQty !== undefined && Number(i.actualQty) >= 0,
    ) &&
    duplicateSkuIds.value.size === 0,
)

const openCreate = () => {
  editingId.value = null
  editingStatus.value = ''
  editorForm.value = { wholesalerId: '', remark: '', attachments: [], items: [] }
  editorHint.value = null
  editorVisible.value = true
}

const openEdit = async (row: CountSheet) => {
  try {
    const detail = await stocktakeApi.detail(String(row.id))
    editingId.value = String(detail.id)
    editingStatus.value = String(detail.status)
    editorForm.value = {
      wholesalerId: String(detail.wholesalerId),
      remark: detail.remark ?? '',
      attachments: detail.attachments ? [...detail.attachments] : [],
      items: (detail.items ?? []).map((i) => ({
        skuId: String(i.skuId),
        actualQty: i.actualQty,
        palletDelta: i.palletDelta === null ? undefined : i.palletDelta,
        remark: i.remark ?? '',
      })),
    }
    editorVisible.value = true
    await loadEditorSources(String(detail.wholesalerId))
  } catch {
    // 全局 toast 已提示
  }
}

const buildItems = (): CountSheetItemInput[] =>
  editorForm.value.items.map((i) => ({
    skuId: i.skuId,
    actualQty: Number(i.actualQty),
    ...(i.palletDelta !== undefined && i.palletDelta !== null
      ? { palletDelta: Number(i.palletDelta) }
      : {}),
    ...(i.remark.trim() ? { remark: i.remark.trim() } : {}),
  }))

/** 存草稿（新建 POST / 编辑 PUT；REJECTED 编辑保存后 CAS 回草稿） */
const saveEditor = async (): Promise<CountSheet | null> => {
  const payload = {
    remark: editorForm.value.remark.trim() || undefined,
    attachments: editorForm.value.attachments.length ? editorForm.value.attachments : undefined,
    items: buildItems(),
  }
  if (editingId.value) {
    return stocktakeApi.update(editingId.value, payload)
  }
  return stocktakeApi.create({ wholesalerId: editorForm.value.wholesalerId, ...payload })
}

const onSaveDraft = async () => {
  if (!canSaveEditor.value) return
  editorSaving.value = true
  try {
    const saved = await saveEditor()
    editorVisible.value = false
    ElMessage.success(
      editingStatus.value === 'REJECTED'
        ? `盘点单 ${saved?.docNo ?? ''} 已改回草稿，可重新提交`
        : `盘点单草稿已保存（${saved?.docNo ?? ''}）`,
    )
    activeTab.value = 'DRAFT'
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_STOCKTAKE_OPEN_EXISTS) {
      // 同商户在途盘点唯一：50356（全局 toast 已提示），保留弹窗供改选商户
    }
  } finally {
    editorSaving.value = false
  }
}

/** 保存并提交审批（system_qty 快照说明在确认弹窗中明示） */
const onSaveAndSubmit = async () => {
  if (!canSaveEditor.value) return
  try {
    await ElMessageBox.confirm(
      '提交后账面数将按提交时刻重新快照定格（此后的出库不再改变差异值），' +
        '盘亏生效量以租户管理员审批时刻的在库封顶。确认提交审批？',
      '提交盘点审批',
      { confirmButtonText: '提交审批', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  editorSubmitting.value = true
  try {
    const saved = await saveEditor()
    if (!saved) return
    const submitted = await stocktakeApi.submit(String(saved.id))
    editorVisible.value = false
    ElMessage.success(`盘点单 ${submitted.docNo} 已提交，等待租户管理员审批`)
    activeTab.value = TAB_PENDING
    await refreshAll()
  } catch {
    // 50355/50356 等全局 toast 已提示
  } finally {
    editorSubmitting.value = false
  }
}

// ============ 草稿行内操作：提交 / 删除 ============
const rowActingId = ref('')

const onRowSubmit = async (row: CountSheet) => {
  try {
    await ElMessageBox.confirm(
      `提交盘点单 ${row.docNo} 审批？提交后账面数按提交时刻重新快照定格，草稿不可再编辑。`,
      '提交盘点审批',
      { confirmButtonText: '提交审批', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rowActingId.value = String(row.id)
  try {
    await stocktakeApi.submit(String(row.id))
    ElMessage.success(`盘点单 ${row.docNo} 已提交，等待租户管理员审批`)
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_DOC_CAS_CONFLICT) {
      await refreshAll()
    }
  } finally {
    rowActingId.value = ''
  }
}

const onRowDelete = async (row: CountSheet) => {
  try {
    await ElMessageBox.confirm(
      `删除盘点单草稿 ${row.docNo}？删除后不可恢复，并释放该商户的在途盘点名额。`,
      '删除草稿',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rowActingId.value = String(row.id)
  try {
    await stocktakeApi.remove(String(row.id))
    ElMessage.success(`草稿 ${row.docNo} 已删除`)
    await refreshAll()
  } catch {
    // 全局 toast 已提示
  } finally {
    rowActingId.value = ''
  }
}

// ============ TA 审批弹窗（06 §2.3 样式 + 封顶预览） ============
const decideVisible = ref(false)
const decideSheet = ref<CountSheet | null>(null)
const decideRemark = ref('')
const decideSubmitting = ref(false)

/** 封顶预览（前端直算，13 v1.3 备注 9：min(|盘亏|, currentStock)，无需另拉库存） */
interface CapPreview {
  applied: number
  shortfall: number
}
const capOf = (item: CountSheetItem): CapPreview | null => {
  if (item.diff >= 0) return null
  const onhand = Math.max(item.currentStock ?? 0, 0)
  const applied = Math.min(-item.diff, onhand)
  return { applied, shortfall: -item.diff - applied }
}

/** 联动摘要：通过后将生成的流水统计 */
const decideSummary = computed(() => {
  const items = decideSheet.value?.items ?? []
  let gain = 0
  let loss = 0
  let shortfall = 0
  for (const i of items) {
    if (i.diff > 0) gain += 1
    if (i.diff < 0) {
      const cap = capOf(i)
      if (cap) {
        if (cap.applied > 0) loss += 1
        shortfall += cap.shortfall
      }
    }
  }
  return { gain, loss, shortfall }
})

const openDecide = async (row: CountSheet) => {
  try {
    const detail = await stocktakeApi.detail(String(row.id))
    decideSheet.value = detail
    decideRemark.value = ''
    decideVisible.value = true
  } catch {
    // 全局 toast 已提示
  }
}

const doDecide = async (conclusion: 'APPROVED' | 'REJECTED') => {
  const sheet = decideSheet.value
  if (!sheet) return
  if (conclusion === 'REJECTED' && !decideRemark.value.trim()) {
    ElMessage.warning('驳回时必须填写审批意见')
    return
  }
  if (conclusion === 'APPROVED' && decideSummary.value.shortfall > 0) {
    try {
      await ElMessageBox.confirm(
        `存在盘亏封顶：差额合计 ${decideSummary.value.shortfall} 件将按审批时刻在库封顶生效，` +
          '差额写入备注并站内信通知线下核查。确认通过？',
        '盘亏封顶确认',
        { confirmButtonText: '确认通过', cancelButtonText: '再想想', type: 'warning' },
      )
    } catch {
      return
    }
  }
  decideSubmitting.value = true
  try {
    const updated = await stocktakeApi.decide(String(sheet.id), {
      conclusion,
      ...(decideRemark.value.trim() ? { remark: decideRemark.value.trim() } : {}),
    })
    decideVisible.value = false
    if (conclusion === 'APPROVED') {
      const sf = decideSummary.value.shortfall
      ElMessage.success(
        `盘点单 ${updated.docNo} 已通过，库存已按差异调整` +
          (sf > 0 ? `（盘亏差额 ${sf} 件已封顶，线下核查）` : ''),
      )
    } else {
      ElMessage.success(`盘点单 ${updated.docNo} 已驳回，库管员可修改后重提`)
    }
    await refreshAll()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      decideVisible.value = false
      await refreshAll()
    }
  } finally {
    decideSubmitting.value = false
  }
}

// ============ 详情（已决单追溯 / 任意单查看） ============
const detailVisible = ref(false)
const detailSheet = ref<CountSheet | null>(null)

const openDetail = async (row: CountSheet) => {
  try {
    detailSheet.value = await stocktakeApi.detail(String(row.id))
    detailVisible.value = true
  } catch {
    // 全局 toast 已提示
  }
}

/** 生效值展示（appliedDiff 带符号；驳回/未决 —） */
const appliedText = (item: CountSheetItem): string => {
  if (item.appliedDiff === null || item.appliedDiff === undefined) return '—'
  if (item.appliedDiff > 0) return `+${item.appliedDiff}`
  return String(item.appliedDiff)
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
              v-if="m.key === '/ta/stocktake'"
              :count="pendingCount"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">盘点</h2>
            <p class="page-head__sub">
              账面数=系统库存（已确认未出库单已扣账、货仍在仓——实物多于账面是正常现象）；差异以提交时刻快照为准，盘亏生效量以审批时刻在库封顶
            </p>
          </div>
          <div class="page-head__actions">
            <el-button type="primary" :icon="Plus" data-test="new-sheet-btn" @click="openCreate">
              新建盘点单
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="stocktake-tabs" @tab-change="onTabChange">
            <el-tab-pane v-for="t in TABS" :key="t.name" :name="t.name">
              <template #label>
                <span class="tab-label">
                  {{ t.label }}
                  <NavCountBadge v-if="t.name === TAB_PENDING" :count="pendingCount" />
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="sheet-table"
            data-test="stocktake-table"
            :empty-text="activeTab === TAB_PENDING ? '暂无待审批盘点单' : '暂无盘点单'"
          >
            <el-table-column prop="docNo" label="盘点单号" min-width="190">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商户" min-width="140">
              <template #default="{ row }">
                {{ row.wholesalerName || wholesalerLabel(row.wholesalerId) }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="165">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="审批时间" width="165">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.decidedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注 / 驳回理由" min-width="150">
              <template #default="{ row }">
                <span :class="row.status === 'REJECTED' ? 'stock-short' : 'cell-muted'">
                  {{ row.status === 'REJECTED' ? row.rejectRemark || '—' : row.remark || '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <!-- 草稿：提交 / 编辑 / 删除 -->
                <template v-if="row.status === 'DRAFT'">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="rowActingId === String(row.id)"
                    data-test="submit-btn"
                    @click="onRowSubmit(row as CountSheet)"
                  >
                    提交审批
                  </el-button>
                  <el-button size="small" data-test="edit-btn" @click="openEdit(row as CountSheet)">
                    编辑
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="danger"
                    :disabled="rowActingId === String(row.id)"
                    data-test="delete-btn"
                    @click="onRowDelete(row as CountSheet)"
                  >
                    删除
                  </el-button>
                </template>

                <!-- 待审批：TA 审批 + 详情 -->
                <template v-else-if="row.status === 'PENDING_APPROVAL'">
                  <el-button
                    type="primary"
                    size="small"
                    data-test="decide-btn"
                    @click="openDecide(row as CountSheet)"
                  >
                    审批
                  </el-button>
                  <el-button
                    size="small"
                    text
                    data-test="detail-btn"
                    @click="openDetail(row as CountSheet)"
                  >
                    详情
                  </el-button>
                </template>

                <!-- 已驳回：编辑重提 + 详情 -->
                <template v-else-if="row.status === 'REJECTED'">
                  <el-button
                    type="warning"
                    size="small"
                    plain
                    data-test="reedit-btn"
                    @click="openEdit(row as CountSheet)"
                  >
                    编辑重提
                  </el-button>
                  <el-button
                    size="small"
                    text
                    data-test="detail-btn"
                    @click="openDetail(row as CountSheet)"
                  >
                    详情
                  </el-button>
                </template>

                <!-- 已通过：详情 -->
                <el-button
                  v-else
                  size="small"
                  text
                  data-test="detail-btn"
                  @click="openDetail(row as CountSheet)"
                >
                  详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 盘点单编辑器（新建 / 草稿编辑 / 驳回重改 · 线框 C） -->
    <el-dialog
      v-model="editorVisible"
      :title="editingId ? (editingStatus === 'REJECTED' ? '修改盘点单（重提后回到草稿）' : '编辑盘点单草稿') : '新建盘点单'"
      width="760px"
      top="4vh"
      data-test="sheet-editor-dialog"
      :close-on-click-modal="false"
    >
      <!-- 在途提示条（必做护栏） -->
      <el-alert
        v-if="hintText(editorHint)"
        type="info"
        :closable="false"
        show-icon
        class="editor-alert"
        data-test="in-transit-banner"
        :title="hintText(editorHint)"
      />

      <el-form label-width="90px" label-position="right" @submit.prevent>
        <el-form-item label="批发商" required>
          <EntityPickerDialog
            v-model="editorForm.wholesalerId"
            title="选择被盘商户"
            placeholder="点击选择商户"
            :columns="wholesalerPickerColumns"
            :fetch="fetchWholesalerPage"
            :selected-label="wholesalerNameMap[editorForm.wholesalerId] || ''"
            :disabled="Boolean(editingId)"
            data-test="sheet-wholesaler-picker"
            @change="onEditorWholesalerChange"
          />
          <span v-if="editingId" class="editor-hint">编辑时不可更换商户</span>
        </el-form-item>
      </el-form>

      <!-- 明细行（逐 SKU 实盘录入；同单 SKU 不重复） -->
      <div class="items-head">
        <span class="items-head__title">盘点明细（{{ editorForm.items.length }} 行，≤200）</span>
        <div class="items-head__actions">
          <el-button
            size="small"
            :disabled="!editorForm.wholesalerId"
            data-test="load-all-btn"
            @click="loadAllInStock"
          >
            载入全部在库商品（全仓盘点）
          </el-button>
          <el-button
            size="small"
            type="primary"
            plain
            :icon="Plus"
            :disabled="!editorForm.wholesalerId"
            data-test="add-item-btn"
            @click="addItemRow"
          >
            添加商品
          </el-button>
        </div>
      </div>

      <div v-if="editorForm.items.length === 0" class="items-empty">
        选择商户后逐个添加商品，或一键载入全部在库商品
      </div>

      <div
        v-for="(item, idx) in editorForm.items"
        :key="idx"
        class="item-row"
        data-test="item-row"
      >
        <div class="item-row__main">
          <EntityPickerDialog
            v-model="item.skuId"
            title="选择商品"
            placeholder="选择商品"
            :columns="skuPickerColumns"
            :fetch="fetchSkuPage"
            :selected-label="skuNameMap[item.skuId] || ''"
            class="item-row__sku"
          />
          <span class="item-row__sys">
            账面数：<b data-test="item-system-qty">{{ item.skuId ? (stockMap[item.skuId] ?? 0) : '—' }}</b>
          </span>
          <span class="item-row__actual">
            实物
            <el-input-number
              v-model="item.actualQty"
              :min="0"
              :step="1"
              step-strictly
              size="small"
              controls-position="right"
              data-test="item-actual"
            />
          </span>
          <span
            v-if="rowDiff(item) !== null"
            class="item-row__diff"
            :class="{
              'diff-loss': (rowDiff(item) ?? 0) < 0,
              'diff-gain': (rowDiff(item) ?? 0) > 0,
            }"
            data-test="item-diff"
          >
            {{ diffText(rowDiff(item) ?? 0) }}
          </span>
          <el-button
            size="small"
            text
            type="danger"
            data-test="remove-item-btn"
            @click="removeItemRow(idx)"
          >
            移除
          </el-button>
        </div>
        <div class="item-row__sub">
          <span v-if="item.skuId && duplicateSkuIds.has(item.skuId)" class="item-dup" data-test="item-dup-warn">
            同单商品重复，请移除
          </span>
          <span
            v-if="(rowDiff(item) ?? 0) < 0 && inTransitOfSku(editorHint, item.skuId) > 0"
            class="item-transit"
            data-test="item-transit-note"
          >
            其中 ≤{{ inTransitOfSku(editorHint, item.skuId) }} 件可能为在途占用
          </span>
          <span v-if="(rowDiff(item) ?? 0) < 0" class="item-pallet">
            释放托盘
            <el-input-number
              v-model="item.palletDelta"
              :min="0"
              :step="1"
              step-strictly
              size="small"
              controls-position="right"
              placeholder="默认比例"
              data-test="item-pallet"
            />
            <span class="editor-hint">留空=按比例建议值，可改 0</span>
          </span>
          <span v-else-if="(rowDiff(item) ?? 0) > 0" class="item-pallet">
            新增托盘
            <el-input-number
              v-model="item.palletDelta"
              :min="0"
              :step="1"
              step-strictly
              size="small"
              controls-position="right"
              data-test="item-pallet"
            />
            <span class="editor-hint">盘盈可选填增加托盘</span>
          </span>
          <el-input
            v-model="item.remark"
            size="small"
            maxlength="512"
            placeholder="差异理由（选填）"
            class="item-row__remark"
            data-test="item-remark"
          />
        </div>
      </div>

      <el-form label-width="90px" label-position="right" class="editor-foot" @submit.prevent>
        <el-form-item label="盘点说明">
          <el-input
            v-model="editorForm.remark"
            type="textarea"
            :rows="2"
            maxlength="512"
            show-word-limit
            placeholder="选填，≤512 字"
            data-test="sheet-remark"
          />
        </el-form-item>
        <el-form-item label="实物照片">
          <AttachmentUpload v-model="editorForm.attachments" :max="5" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button
          :disabled="!canSaveEditor"
          :loading="editorSaving"
          data-test="sheet-save-draft"
          @click="onSaveDraft"
        >
          存草稿
        </el-button>
        <el-button
          type="primary"
          :disabled="!canSaveEditor"
          :loading="editorSubmitting"
          data-test="sheet-submit"
          @click="onSaveAndSubmit"
        >
          提交审批
        </el-button>
      </template>
    </el-dialog>

    <!-- TA 审批弹窗（06 §2.3 样式 + 封顶预览） -->
    <el-dialog
      v-model="decideVisible"
      :title="`审批盘点单 ${decideSheet?.docNo ?? ''}`"
      width="720px"
      top="4vh"
      data-test="stocktake-decide-dialog"
      :close-on-click-modal="false"
    >
      <template v-if="decideSheet">
        <p class="decide-meta">
          商户：{{ decideSheet.wholesalerName || wholesalerLabel(decideSheet.wholesalerId) }}
          · 提交时间：{{ formatTime(decideSheet.updatedAt || decideSheet.createdAt) }}
        </p>
        <el-alert
          v-if="hintText(decideSheet.inTransitHint)"
          type="info"
          :closable="false"
          show-icon
          class="editor-alert"
          data-test="decide-transit-banner"
          :title="hintText(decideSheet.inTransitHint)"
        />

        <el-table :data="decideSheet.items ?? []" row-key="id" size="small" data-test="decide-items-table">
          <el-table-column label="商品" min-width="150">
            <template #default="{ row }">{{ row.skuName || skuLabel(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="账面数" width="80" align="right" prop="systemQty" />
          <el-table-column label="实物数" width="80" align="right" prop="actualQty" />
          <el-table-column label="差异" width="110">
            <template #default="{ row }">
              <span :class="{ 'diff-loss': row.diff < 0, 'diff-gain': row.diff > 0 }">
                {{ diffText(row.diff) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="当前在库" width="85" align="right">
            <template #default="{ row }">{{ row.currentStock ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="封顶预览（生效 / 差额）" min-width="190">
            <template #default="{ row }">
              <template v-if="row.diff < 0">
                <span data-test="cap-preview">
                  盘亏生效 {{ capOf(row as CountSheetItem)?.applied ?? 0 }} 件
                  <span
                    v-if="(capOf(row as CountSheetItem)?.shortfall ?? 0) > 0"
                    class="cap-shortfall"
                    data-test="cap-shortfall"
                  >
                    · 差额 {{ capOf(row as CountSheetItem)?.shortfall }} 件写入备注线下核查
                  </span>
                </span>
              </template>
              <span v-else-if="row.diff > 0">盘盈 +{{ row.diff }} 件入池</span>
              <span v-else class="cell-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="托盘" width="105">
            <template #default="{ row }">
              <span class="cell-muted">
                {{
                  row.palletDelta !== null && row.palletDelta !== undefined
                    ? `覆盖 ${row.palletDelta}`
                    : row.diff < 0
                      ? `默认 ${row.suggestedPalletRelease ?? 0}`
                      : '—'
                }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="理由" min-width="110">
            <template #default="{ row }">
              <span class="cell-muted">{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <p class="decide-linkage" data-test="decide-linkage">
          通过后联动：盘盈流水 ×{{ decideSummary.gain }}、盘亏流水 ×{{ decideSummary.loss }}（按审批时刻在库封顶<template
            v-if="decideSummary.shortfall > 0"
          >，差额合计 <b class="cap-shortfall">{{ decideSummary.shortfall }} 件</b></template
          >）；盘亏当日截止 / 盘盈次日起算计费。
        </p>

        <el-input
          v-model="decideRemark"
          type="textarea"
          :rows="2"
          maxlength="512"
          show-word-limit
          placeholder="审批意见（驳回时必填）"
          data-test="decide-remark"
        />
      </template>
      <template #footer>
        <el-button
          type="danger"
          plain
          :loading="decideSubmitting"
          data-test="decide-reject"
          @click="doDecide('REJECTED')"
        >
          驳回（填理由）
        </el-button>
        <el-button
          type="primary"
          :loading="decideSubmitting"
          data-test="decide-approve"
          @click="doDecide('APPROVED')"
        >
          通过
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情（已决追溯 / 只读查看） -->
    <el-dialog
      v-model="detailVisible"
      :title="`盘点单详情 ${detailSheet?.docNo ?? ''}`"
      width="720px"
      top="4vh"
      data-test="stocktake-detail-dialog"
    >
      <template v-if="detailSheet">
        <p class="decide-meta">
          商户：{{ detailSheet.wholesalerName || wholesalerLabel(detailSheet.wholesalerId) }}
          · 状态：
          <el-tag :type="statusMeta(String(detailSheet.status)).type" size="small" effect="light" round>
            {{ statusMeta(String(detailSheet.status)).label }}
          </el-tag>
          · 创建：{{ formatTime(detailSheet.createdAt) }}
          <template v-if="detailSheet.decidedAt">· 审批：{{ formatTime(detailSheet.decidedAt) }}</template>
        </p>
        <el-alert
          v-if="detailSheet.status === 'REJECTED' && detailSheet.rejectRemark"
          type="error"
          :closable="false"
          show-icon
          class="editor-alert"
          :title="`驳回理由：${detailSheet.rejectRemark}`"
        />

        <el-table :data="detailSheet.items ?? []" row-key="id" size="small" data-test="detail-items-table">
          <el-table-column label="商品" min-width="150">
            <template #default="{ row }">{{ row.skuName || skuLabel(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="账面数" width="80" align="right" prop="systemQty" />
          <el-table-column label="实物数" width="80" align="right" prop="actualQty" />
          <el-table-column label="差异" width="110">
            <template #default="{ row }">
              <span :class="{ 'diff-loss': row.diff < 0, 'diff-gain': row.diff > 0 }">
                {{ diffText(row.diff) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="生效值" width="80" align="right">
            <template #default="{ row }">
              <span data-test="detail-applied">{{ appliedText(row as CountSheetItem) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="托盘" width="90" align="right">
            <template #default="{ row }">
              <span class="cell-muted">{{ row.palletDelta ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="理由 / 差额说明" min-width="150">
            <template #default="{ row }">
              <span class="cell-muted">{{ row.remark || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <p v-if="detailSheet.remark" class="decide-meta">盘点说明：{{ detailSheet.remark }}</p>
        <div v-if="detailSheet.attachments?.length" class="detail-photos">
          <el-image
            v-for="(url, i) in detailSheet.attachments"
            :key="url"
            :src="url"
            :preview-src-list="detailSheet.attachments"
            :initial-index="i"
            fit="cover"
            class="detail-photo"
          />
        </div>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
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

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.sheet-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.stock-short {
  color: var(--color-danger);
}
.diff-loss {
  color: var(--color-danger);
  font-weight: var(--font-weight-medium);
}
.diff-gain {
  color: var(--color-success);
  font-weight: var(--font-weight-medium);
}
.cap-shortfall {
  color: var(--color-danger);
  font-weight: var(--font-weight-semibold);
}

/* ===== 编辑器 ===== */
.editor-alert {
  margin-bottom: var(--space-4);
}
.editor-hint {
  margin-left: var(--space-2);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.items-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
  gap: var(--space-2);
}
.items-head__title {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.items-head__actions {
  display: flex;
  gap: var(--space-2);
}
.items-empty {
  padding: var(--space-5);
  text-align: center;
  color: var(--color-fg-3);
  background: var(--color-bg-2);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
}
.item-row {
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  margin-bottom: var(--space-3);
}
.item-row__main {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.item-row__sku {
  min-width: 220px;
  flex: 1;
}
.item-row__sys {
  color: var(--color-fg-2);
  white-space: nowrap;
}
.item-row__actual {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}
.item-row__diff {
  white-space: nowrap;
}
.item-row__sub {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
  flex-wrap: wrap;
}
.item-dup {
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}
.item-transit {
  color: var(--color-warning);
  font-size: var(--font-size-caption);
}
.item-pallet {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}
.item-row__remark {
  flex: 1;
  min-width: 180px;
}
.editor-foot {
  margin-top: var(--space-4);
}

/* ===== 审批 / 详情弹窗 ===== */
.decide-meta {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
}
.decide-linkage {
  margin: var(--space-3) 0;
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  line-height: 1.6;
}
.detail-photos {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}
.detail-photo {
  width: 72px;
  height: 72px;
  border-radius: var(--radius-sm);
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
