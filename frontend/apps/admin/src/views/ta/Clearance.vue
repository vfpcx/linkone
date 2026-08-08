<script setup lang="ts">
/**
 * 清库单（QK- · WK 发起/提交 + TA 审批 · P3b T4-FE · PRD 11 §3.5 / 13 §3.4）
 *
 * 契约（权威：TenantClearanceController，据实查证）：
 *  - POST   /tenant/clearance-requests        WK 建草稿（一单一批次；仅 PENDING_CLEARANCE
 *      且推算剩余>0 / 同批次在途至多一张 → 50365；照片 ≥1（50366）≤3；qty ≤ 池在库 50251；
 *      OTHER 时 reasonRemark 必填）
 *  - PUT    /{id}      WK 编辑（DRAFT 直改 / REJECTED 改回 DRAFT 重提；batchId 不可变）
 *  - DELETE /{id}      WK 删草稿（仅 DRAFT）
 *  - POST   /{id}/submit  提交（CAS DRAFT→PENDING_APPROVAL）→ 通知 TA
 *  - GET    ?status=   列表（待审批创建升序；列表不带名称/批次信息——盘点先例）
 *  - GET    /{id}      详情（附 batchNo/batchExpiryDate/batchRemainingQty +
 *      currentStock/suggestedPalletRelease 封顶预览）
 *  - POST   /{id}/decide  TA 审批（APPROVED 锁内 clearStock 封顶 min(qty,currentStock) +
 *      EXPIRY_CLEARANCE 流水 + 批次 CLEARED + 商户凭证通知；REJECTED remark 必填）
 *
 * 产品口径（11 §3.5）：清库件数默认=推算剩余、现场核数可改（≤该商品在库数）；
 *  原因单选 过期/损坏/其他（其他备注必填）；照片必填 ≥1（≤3）不受拍照开关影响；
 *  通过=按池剩余在库封顶（D-10 同构）、仓储费当日截止、不计正常出库统计。
 *
 * 入口：批次临期页「发起清库」带 ?batch={id} 直开建单弹窗；本页「新建清库单」从待清理批次中选。
 */

import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
  AlarmClock,
  Remove,
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
  ClearanceReason,
  ClearanceRequest,
  ClearanceStatus,
  Sku,
  Wholesaler,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import { clearanceApi } from '@/api/clearance'
import { batchApi } from '@/api/batch'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { accountApi } from '@/api/account'

const router = useRouter()
const route = useRoute()
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
  '/ta/batches',
  '/ta/approvals',
])

const activeMenu = ref('/ta/clearance')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/clearance' ||
    key === '/ta/bills') {
    activeMenu.value = key
    return
  }
  if (IMPLEMENTED.has(key)) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 角色 ============
const isWk = computed(() => auth.roles?.some((r) => r.role === 'WK') ?? false)

// ============ 映射 ============
type BadgeType = 'warning' | 'primary' | 'success' | 'info' | 'danger'
const STATUS_META: Record<ClearanceStatus, { label: string; type: BadgeType }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  REJECTED: { label: '已驳回', type: 'danger' },
  APPROVED: { label: '已通过', type: 'success' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as ClearanceStatus] ?? { label: s, type: 'info' as BadgeType }

const REASON_LABELS: Record<string, string> = {
  EXPIRED: '过期',
  DAMAGED: '损坏',
  OTHER: '其他',
}
const reasonLabel = (r: string | null): string => (r ? (REASON_LABELS[r] ?? r) : '—')

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'
const formatDate = (v: string | null): string => (v ? String(v).slice(0, 10) : '—')

/** 批次过期天数文案（到效期 − 今日） */
const expiredDaysText = (expiry: string | null): string => {
  if (!expiry) return '—'
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const d = new Date(`${String(expiry).slice(0, 10)}T00:00:00`)
  const diff = Math.round((today.getTime() - d.getTime()) / 86400_000)
  if (diff > 0) return `已过期 ${diff} 天`
  if (diff === 0) return '今日到期'
  return `剩余 ${-diff} 天`
}

// ============ 名称映射（列表不带名称——盘点先例，前端自建） ============
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
const rows = ref<ClearanceRequest[]>([])
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const list = await clearanceApi.list({
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
    const list = await clearanceApi.list({ status: TAB_PENDING })
    pendingCount.value = list.length
  } catch {
    /* 静默 */
  }
}

const refreshAll = () => Promise.all([fetchList(), fetchPendingCount()])

const onTabChange = () => {
  void fetchList()
}

// ============ 建单/编辑弹窗（一单一批次 · 06 §3.4c 合并版骨架） ============
/** 可发起清库的批次（PENDING_CLEARANCE 且推算剩余>0，来自预警列表） */
const clearableBatches = ref<Batch[]>([])

const fetchClearableBatches = async () => {
  try {
    const res = await batchApi.expiring()
    clearableBatches.value = res.list.filter(
      (b) => String(b.status) === 'PENDING_CLEARANCE' && (b.remainingQty ?? 0) > 0,
    )
  } catch {
    // 全局 toast 已提示
  }
}

const batchPickerColumns: EntityPickerColumn<Batch>[] = [
  { label: '批次号', prop: 'batchNo', minWidth: 130 },
  { label: '商品', formatter: (b) => skuLabel(b.skuId), minWidth: 140 },
  { label: '商户', formatter: (b) => wholesalerLabel(b.wholesalerId), minWidth: 110 },
  { label: '推算剩余', formatter: (b) => `${b.remainingQty} 件*`, width: 90, align: 'right' },
  { label: '到效期', formatter: (b) => formatDate(b.expiryDate), width: 105 },
]
const fetchBatchPage = makeClientPickerFetch<Batch>(
  () => clearableBatches.value,
  (b, kw) =>
    b.batchNo.toLowerCase().includes(kw) ||
    skuLabel(b.skuId).toLowerCase().includes(kw) ||
    wholesalerLabel(b.wholesalerId).toLowerCase().includes(kw),
)

const editorVisible = ref(false)
/** null=新建 */
const editingId = ref<string | null>(null)
const editingStatus = ref<string>('')
const editorSaving = ref(false)
const editorSubmitting = ref(false)
const editorForm = ref({
  batchId: '',
  qty: undefined as number | undefined,
  reason: '' as ClearanceReason | '',
  reasonRemark: '',
  palletRelease: undefined as number | undefined,
  attachments: [] as string[],
  remark: '',
})
/** 编辑态的批次只读信息（新建=选中批次行；编辑=详情附带五字段） */
const editorBatch = ref<{
  batchNo: string
  expiryDate: string | null
  remainingQty: number | null
} | null>(null)

const onEditorBatchChange = () => {
  const b = clearableBatches.value.find((x) => String(x.id) === editorForm.value.batchId)
  editorBatch.value = b
    ? { batchNo: b.batchNo, expiryDate: b.expiryDate, remainingQty: b.remainingQty }
    : null
  // 件数默认=推算剩余（现场核数可改，PRD §3.5）
  if (b) editorForm.value.qty = b.remainingQty
}

const editorError = computed<string>(() => {
  const f = editorForm.value
  if (!editingId.value && !f.batchId) return '请选择待清理批次'
  if (f.qty === undefined || f.qty === null || !Number.isInteger(Number(f.qty)) || Number(f.qty) <= 0) {
    return '清库件数须为大于 0 的整数（现场核数）'
  }
  if (!f.reason) return '请选择清库原因'
  if (f.reason === 'OTHER' && !f.reasonRemark.trim()) return '选择「其他」时必须填写原因备注'
  if (f.attachments.length < 1) return '实物照片必填（至少 1 张，不受拍照开关影响）'
  if (f.attachments.length > 3) return '实物照片最多 3 张'
  return ''
})

const openCreate = async (batchId?: string) => {
  editingId.value = null
  editingStatus.value = ''
  editorForm.value = {
    batchId: batchId ?? '',
    qty: undefined,
    reason: '',
    reasonRemark: '',
    palletRelease: undefined,
    attachments: [],
    remark: '',
  }
  editorBatch.value = null
  await fetchClearableBatches()
  if (batchId) {
    onEditorBatchChange()
    if (!editorBatch.value) {
      ElMessage.warning('该批次当前不可清库（须为待清理状态且推算剩余大于 0）')
    }
  }
  editorVisible.value = true
}

const openEdit = async (row: ClearanceRequest) => {
  try {
    const detail = await clearanceApi.detail(String(row.id))
    editingId.value = String(detail.id)
    editingStatus.value = String(detail.status)
    editorForm.value = {
      batchId: String(detail.batchId),
      qty: detail.qty,
      reason: (detail.reason as ClearanceReason) ?? '',
      reasonRemark: detail.reasonRemark ?? '',
      palletRelease: detail.palletRelease === null ? undefined : detail.palletRelease,
      attachments: detail.attachments ? [...detail.attachments] : [],
      remark: detail.remark ?? '',
    }
    editorBatch.value = {
      batchNo: detail.batchNo ?? '—',
      expiryDate: detail.batchExpiryDate,
      remainingQty: detail.batchRemainingQty,
    }
    editorVisible.value = true
  } catch {
    // 全局 toast 已提示
  }
}

const buildPayload = () => {
  const f = editorForm.value
  return {
    qty: Number(f.qty),
    reason: f.reason as ClearanceReason,
    ...(f.reasonRemark.trim() ? { reasonRemark: f.reasonRemark.trim() } : {}),
    ...(f.palletRelease !== undefined && f.palletRelease !== null
      ? { palletRelease: Number(f.palletRelease) }
      : {}),
    attachments: f.attachments,
    ...(f.remark.trim() ? { remark: f.remark.trim() } : {}),
  }
}

const saveEditor = async (): Promise<ClearanceRequest> => {
  if (editingId.value) {
    return clearanceApi.update(editingId.value, buildPayload())
  }
  return clearanceApi.create({ batchId: editorForm.value.batchId, ...buildPayload() })
}

const onSaveDraft = async () => {
  if (editorError.value) return
  editorSaving.value = true
  try {
    const saved = await saveEditor()
    editorVisible.value = false
    ElMessage.success(
      editingStatus.value === 'REJECTED'
        ? `清库单 ${saved.docNo} 已改回草稿，可重新提交`
        : `清库单草稿已保存（${saved.docNo}）`,
    )
    activeTab.value = 'DRAFT'
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_CLEARANCE_BATCH_NOT_CLEARABLE) {
      // 50365：批次不可清 / 在途 QK 已存在（全局 toast 已提示），刷新可选批次
      await fetchClearableBatches()
    }
  } finally {
    editorSaving.value = false
  }
}

const onSaveAndSubmit = async () => {
  if (editorError.value) return
  try {
    await ElMessageBox.confirm(
      '提交审批后不可再编辑；通过时将按该商品池剩余在库封顶扣减、生成清库流水（仓储费当日截止、不计正常出库统计），批次转「已清库」。确认提交？',
      '提交清库审批',
      { confirmButtonText: '提交审批', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  editorSubmitting.value = true
  try {
    const saved = await saveEditor()
    const submitted = await clearanceApi.submit(String(saved.id))
    editorVisible.value = false
    ElMessage.success(`清库单 ${submitted.docNo} 已提交，等待租户管理员审批`)
    activeTab.value = TAB_PENDING
    await refreshAll()
  } catch {
    // 50365/50366/50251 等全局 toast 已提示
  } finally {
    editorSubmitting.value = false
  }
}

// ============ 草稿行内操作：提交 / 删除 ============
const rowActingId = ref('')

const onRowSubmit = async (row: ClearanceRequest) => {
  try {
    await ElMessageBox.confirm(
      `提交清库单 ${row.docNo} 审批？提交后草稿不可再编辑。`,
      '提交清库审批',
      { confirmButtonText: '提交审批', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rowActingId.value = String(row.id)
  try {
    await clearanceApi.submit(String(row.id))
    ElMessage.success(`清库单 ${row.docNo} 已提交，等待租户管理员审批`)
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_DOC_CAS_CONFLICT) {
      await refreshAll()
    }
  } finally {
    rowActingId.value = ''
  }
}

const onRowDelete = async (row: ClearanceRequest) => {
  try {
    await ElMessageBox.confirm(
      `删除清库单草稿 ${row.docNo}？删除后不可恢复，并释放该批次的在途清库名额。`,
      '删除草稿',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rowActingId.value = String(row.id)
  try {
    await clearanceApi.remove(String(row.id))
    ElMessage.success(`草稿 ${row.docNo} 已删除`)
    await refreshAll()
  } catch {
    // 全局 toast 已提示
  } finally {
    rowActingId.value = ''
  }
}

// ============ TA 审批弹窗（套盘点审批样式 + 封顶预览） ============
const decideVisible = ref(false)
const decideDoc = ref<ClearanceRequest | null>(null)
const decideRemark = ref('')
const decideSubmitting = ref(false)

/** 封顶预览（盘点先例：min(qty, currentStock)，差额写备注线下核查） */
const capPreview = computed<{ applied: number; shortfall: number } | null>(() => {
  const d = decideDoc.value
  if (!d) return null
  const onhand = Math.max(d.currentStock ?? 0, 0)
  const applied = Math.min(d.qty, onhand)
  return { applied, shortfall: d.qty - applied }
})

const openDecide = async (row: ClearanceRequest) => {
  try {
    decideDoc.value = await clearanceApi.detail(String(row.id))
    decideRemark.value = ''
    decideVisible.value = true
  } catch {
    // 全局 toast 已提示
  }
}

const doDecide = async (conclusion: 'APPROVED' | 'REJECTED') => {
  const doc = decideDoc.value
  if (!doc) return
  if (conclusion === 'REJECTED' && !decideRemark.value.trim()) {
    ElMessage.warning('驳回时必须填写审批意见')
    return
  }
  if (conclusion === 'APPROVED' && (capPreview.value?.shortfall ?? 0) > 0) {
    try {
      await ElMessageBox.confirm(
        `现场核数 ${doc.qty} 件超出当前在库 ${doc.currentStock ?? 0} 件，` +
          `将按在库封顶生效 ${capPreview.value?.applied} 件，差额 ${capPreview.value?.shortfall} 件写入备注线下核查。确认通过？`,
        '清库封顶确认',
        { confirmButtonText: '确认通过', cancelButtonText: '再想想', type: 'warning' },
      )
    } catch {
      return
    }
  }
  decideSubmitting.value = true
  try {
    const updated = await clearanceApi.decide(String(doc.id), {
      conclusion,
      ...(decideRemark.value.trim() ? { remark: decideRemark.value.trim() } : {}),
    })
    decideVisible.value = false
    if (conclusion === 'APPROVED') {
      const sf = capPreview.value?.shortfall ?? 0
      ElMessage.success(
        `清库单 ${updated.docNo} 已通过，库存已扣减、批次转「已清库」` +
          (sf > 0 ? `（差额 ${sf} 件已封顶，线下核查）` : ''),
      )
    } else {
      ElMessage.success(`清库单 ${updated.docNo} 已驳回，库管员可修改后重提`)
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

// ============ 详情 ============
const detailVisible = ref(false)
const detailDoc = ref<ClearanceRequest | null>(null)

const openDetail = async (row: ClearanceRequest) => {
  try {
    detailDoc.value = await clearanceApi.detail(String(row.id))
    detailVisible.value = true
  } catch {
    // 全局 toast 已提示
  }
}

onMounted(async () => {
  void refreshAll()
  await fetchNames()
  // 批次临期页「发起清库」入口：?batch={id} 直开建单弹窗
  const batchId = typeof route.query.batch === 'string' ? route.query.batch : ''
  if (batchId && isWk.value) {
    await openCreate(batchId)
  }
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
              v-if="m.key === '/ta/clearance'"
              :count="pendingCount"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">清库</h2>
            <p class="page-head__sub">
              过期批次强制清库（QK-）：库管员按现场核数发起，租户管理员审批；通过按池剩余在库封顶扣减，仓储费当日截止、不计正常出库统计
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              v-if="isWk"
              type="primary"
              :icon="Plus"
              data-test="new-clearance-btn"
              @click="openCreate()"
            >
              新建清库单
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="clearance-tabs" @tab-change="onTabChange">
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
            data-test="clearance-table"
            :empty-text="activeTab === TAB_PENDING ? '暂无待审批清库单' : '暂无清库单'"
          >
            <el-table-column prop="docNo" label="清库单号" min-width="165">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="110">
              <template #default="{ row }">{{ row.skuName || skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="商户" min-width="100">
              <template #default="{ row }">
                {{ row.wholesalerName || wholesalerLabel(row.wholesalerId) }}
              </template>
            </el-table-column>
            <el-table-column label="清库件数" width="80" align="right" prop="qty" />
            <el-table-column label="原因" width="70">
              <template #default="{ row }">{{ reasonLabel(row.reason) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注 / 驳回理由" min-width="105">
              <template #default="{ row }">
                <span :class="row.status === 'REJECTED' ? 'text-danger' : 'cell-muted'">
                  {{ row.status === 'REJECTED' ? row.rejectRemark || '—' : row.remark || '—' }}
                </span>
              </template>
            </el-table-column>
            <!-- 不用 fixed：1280 宽下 fixed 悬浮列会盖住状态/时间列（视觉目检发现），自然滚动即可 -->
            <el-table-column label="操作" width="225">
              <template #default="{ row }">
                <!-- 草稿：提交 / 编辑 / 删除（WK） -->
                <template v-if="row.status === 'DRAFT'">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="rowActingId === String(row.id)"
                    data-test="submit-btn"
                    @click="onRowSubmit(row as ClearanceRequest)"
                  >
                    提交审批
                  </el-button>
                  <el-button
                    size="small"
                    data-test="edit-btn"
                    @click="openEdit(row as ClearanceRequest)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="danger"
                    :disabled="rowActingId === String(row.id)"
                    data-test="delete-btn"
                    @click="onRowDelete(row as ClearanceRequest)"
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
                    @click="openDecide(row as ClearanceRequest)"
                  >
                    审批
                  </el-button>
                  <el-button
                    size="small"
                    text
                    data-test="detail-btn"
                    @click="openDetail(row as ClearanceRequest)"
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
                    @click="openEdit(row as ClearanceRequest)"
                  >
                    编辑重提
                  </el-button>
                  <el-button
                    size="small"
                    text
                    data-test="detail-btn"
                    @click="openDetail(row as ClearanceRequest)"
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
                  @click="openDetail(row as ClearanceRequest)"
                >
                  详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- WK 建单/编辑弹窗（一单一批次 · 06 §3.4c 合并版） -->
    <el-dialog
      v-model="editorVisible"
      :title="
        editingId
          ? editingStatus === 'REJECTED'
            ? '修改清库单（重提后回到草稿）'
            : '编辑清库单草稿'
          : '发起清库'
      "
      width="620px"
      top="4vh"
      data-test="clearance-editor-dialog"
      :close-on-click-modal="false"
    >
      <el-form label-width="96px" label-position="right" @submit.prevent>
        <el-form-item label="待清理批次" required>
          <template v-if="editingId">
            <span class="cell-name">{{ editorBatch?.batchNo ?? '—' }}</span>
            <span class="editor-hint">编辑时不可更换批次</span>
          </template>
          <EntityPickerDialog
            v-else
            v-model="editorForm.batchId"
            title="选择待清理批次"
            placeholder="仅可选待清理且推算剩余>0 的批次"
            :columns="batchPickerColumns"
            :fetch="fetchBatchPage"
            :selected-label="editorBatch ? editorBatch.batchNo : ''"
            data-test="clearance-batch-picker"
            @change="onEditorBatchChange"
          />
        </el-form-item>

        <!-- 批次只读信息 -->
        <el-alert
          v-if="editorBatch"
          type="warning"
          :closable="false"
          class="editor-alert"
          data-test="batch-info-banner"
        >
          <p class="batch-info">
            批次 <b>{{ editorBatch.batchNo }}</b>
            · 到效期 {{ formatDate(editorBatch.expiryDate) }}（{{ expiredDaysText(editorBatch.expiryDate) }}）
            · 推算剩余 <b>{{ editorBatch.remainingQty ?? '—' }}</b> 件*
          </p>
          <p class="batch-info batch-info--sub">* 推算值 · 截至今日 02:00，清库以现场核数为准</p>
        </el-alert>

        <el-form-item label="清库件数" required>
          <el-input-number
            v-model="editorForm.qty"
            :min="1"
            :step="1"
            step-strictly
            controls-position="right"
            data-test="clearance-qty"
          />
          <span class="editor-hint">默认=推算剩余，现场核数可改（≤该商品在库数）</span>
        </el-form-item>

        <el-form-item label="清库原因" required>
          <el-radio-group v-model="editorForm.reason" data-test="clearance-reason">
            <el-radio value="EXPIRED">过期</el-radio>
            <el-radio value="DAMAGED">损坏</el-radio>
            <el-radio value="OTHER">其他</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="editorForm.reason === 'OTHER'" label="原因备注" required>
          <el-input
            v-model="editorForm.reasonRemark"
            maxlength="512"
            placeholder="选择「其他」时必填（如客户投诉等）"
            data-test="clearance-reason-remark"
          />
        </el-form-item>

        <el-form-item label="释放托盘">
          <el-input-number
            v-model="editorForm.palletRelease"
            :min="0"
            :step="1"
            step-strictly
            controls-position="right"
            placeholder="默认比例"
            data-test="clearance-pallet"
          />
          <span class="editor-hint">留空=按比例默认值，可改（含 0）；全部出清默认释放全部</span>
        </el-form-item>

        <el-form-item label="实物照片" required>
          <div class="photo-field">
            <AttachmentUpload v-model="editorForm.attachments" :max="3" />
            <span class="editor-hint">必填 ≥1 张（≤3 张），不受拍照开关影响</span>
          </div>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="editorForm.remark"
            type="textarea"
            :rows="2"
            maxlength="512"
            show-word-limit
            placeholder="选填 ≤512 字"
            data-test="clearance-remark"
          />
        </el-form-item>
      </el-form>

      <p v-if="editorError" class="editor-error" data-test="editor-error">{{ editorError }}</p>

      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button
          :disabled="Boolean(editorError)"
          :loading="editorSaving"
          data-test="clearance-save-draft"
          @click="onSaveDraft"
        >
          存草稿
        </el-button>
        <el-button
          type="primary"
          :disabled="Boolean(editorError)"
          :loading="editorSubmitting"
          data-test="clearance-submit"
          @click="onSaveAndSubmit"
        >
          提交审批
        </el-button>
      </template>
    </el-dialog>

    <!-- TA 审批弹窗（套盘点审批样式 + 封顶预览） -->
    <el-dialog
      v-model="decideVisible"
      :title="`审批清库单 ${decideDoc?.docNo ?? ''}`"
      width="640px"
      top="4vh"
      data-test="clearance-decide-dialog"
      :close-on-click-modal="false"
    >
      <template v-if="decideDoc">
        <p class="decide-meta">
          商户：{{ decideDoc.wholesalerName || wholesalerLabel(decideDoc.wholesalerId) }}
          · 商品：{{ decideDoc.skuName || skuLabel(decideDoc.skuId) }}
          · 提交：{{ formatTime(decideDoc.updatedAt || decideDoc.createdAt) }}
        </p>

        <el-descriptions :column="2" border size="small" class="decide-desc">
          <el-descriptions-item label="批次号">{{ decideDoc.batchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="到效期">
            {{ formatDate(decideDoc.batchExpiryDate) }}（{{ expiredDaysText(decideDoc.batchExpiryDate) }}）
          </el-descriptions-item>
          <el-descriptions-item label="推算剩余">
            {{ decideDoc.batchRemainingQty ?? '—' }} 件*
          </el-descriptions-item>
          <el-descriptions-item label="现场核数（清库件数）">
            <b>{{ decideDoc.qty }}</b> 件
          </el-descriptions-item>
          <el-descriptions-item label="当前在库">
            {{ decideDoc.currentStock ?? '—' }} 件
          </el-descriptions-item>
          <el-descriptions-item label="封顶预览">
            <!-- data-test 落 span：el-descriptions-item 为 renderless 组件，attrs 不上 DOM -->
            <span data-test="cap-preview">
              生效 {{ capPreview?.applied ?? 0 }} 件
              <span
                v-if="(capPreview?.shortfall ?? 0) > 0"
                class="text-danger"
                data-test="cap-shortfall"
              >
                · 差额 {{ capPreview?.shortfall }} 件写入备注线下核查
              </span>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="清库原因">
            {{ reasonLabel(decideDoc.reason) }}
            <span v-if="decideDoc.reasonRemark" class="cell-muted">（{{ decideDoc.reasonRemark }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="释放托盘">
            {{
              decideDoc.palletRelease !== null && decideDoc.palletRelease !== undefined
                ? `覆盖 ${decideDoc.palletRelease}`
                : `默认 ${decideDoc.suggestedPalletRelease ?? 0}`
            }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="decideDoc.attachments?.length" class="detail-photos">
          <el-image
            v-for="(url, i) in decideDoc.attachments"
            :key="url"
            :src="url"
            :preview-src-list="decideDoc.attachments"
            :initial-index="i"
            fit="cover"
            class="detail-photo"
          />
        </div>

        <p class="decide-linkage" data-test="decide-linkage">
          通过后联动：按当前在库封顶扣减商品池、生成清库流水（落批次标识）、释放托盘、批次转「已清库」；
          仓储费当日截止，不计正常出库统计，站内信含照片凭证送达商户。
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

    <!-- 详情（只读追溯） -->
    <el-dialog
      v-model="detailVisible"
      :title="`清库单详情 ${detailDoc?.docNo ?? ''}`"
      width="640px"
      top="4vh"
      data-test="clearance-detail-dialog"
    >
      <template v-if="detailDoc">
        <p class="decide-meta">
          状态：
          <el-tag :type="statusMeta(String(detailDoc.status)).type" size="small" effect="light" round>
            {{ statusMeta(String(detailDoc.status)).label }}
          </el-tag>
          · 创建：{{ formatTime(detailDoc.createdAt) }}
          <template v-if="detailDoc.decidedAt">· 审批：{{ formatTime(detailDoc.decidedAt) }}</template>
        </p>
        <el-alert
          v-if="detailDoc.status === 'REJECTED' && detailDoc.rejectRemark"
          type="error"
          :closable="false"
          show-icon
          class="editor-alert"
          :title="`驳回理由：${detailDoc.rejectRemark}`"
        />

        <el-descriptions :column="2" border size="small" class="decide-desc">
          <el-descriptions-item label="商户">
            {{ detailDoc.wholesalerName || wholesalerLabel(detailDoc.wholesalerId) }}
          </el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ detailDoc.skuName || skuLabel(detailDoc.skuId) }}
          </el-descriptions-item>
          <el-descriptions-item label="批次号">{{ detailDoc.batchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="到效期">
            {{ formatDate(detailDoc.batchExpiryDate) }}
          </el-descriptions-item>
          <el-descriptions-item label="清库件数（现场核数）">
            {{ detailDoc.qty }} 件
          </el-descriptions-item>
          <el-descriptions-item label="清库原因">
            {{ reasonLabel(detailDoc.reason) }}
            <span v-if="detailDoc.reasonRemark" class="cell-muted">（{{ detailDoc.reasonRemark }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="释放托盘">
            {{ detailDoc.palletRelease ?? `默认 ${detailDoc.suggestedPalletRelease ?? 0}` }}
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailDoc.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="detailDoc.attachments?.length" class="detail-photos">
          <el-image
            v-for="(url, i) in detailDoc.attachments"
            :key="url"
            :src="url"
            :preview-src-list="detailDoc.attachments"
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
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.text-danger {
  color: var(--color-danger);
  font-weight: var(--font-weight-medium);
}
.editor-alert {
  margin-bottom: var(--space-4);
}
.editor-hint {
  margin-left: var(--space-2);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.editor-error {
  margin: var(--space-2) 0 0;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}
.batch-info {
  margin: 0;
  line-height: 1.6;
}
.batch-info--sub {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.photo-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.photo-field .editor-hint {
  margin-left: 0;
}
.decide-meta {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
}
.decide-desc {
  margin-bottom: var(--space-3);
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
