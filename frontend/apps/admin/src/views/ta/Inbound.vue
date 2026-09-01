<script setup lang="ts">
/**
 * WK 入库工作台（PC）— phase-1 C1 代建登记 + P3b T1-FE 正向申请链受理/驳回/登记/打印/R3 纠错
 *
 * 契约（权威：backend/.../document/controller/InboundController.java 实测）：
 *  - POST /tenant/inbound                 代建登记（单事务：建单 + 增库存）
 *  - GET  /tenant/inbound?status=         status=SUBMITTED 待受理队列（后端升序，先到先受理）
 *  - POST /tenant/inbound/{id}/accept     受理锁单（50313 商户非营业 / 50330/50331 刷新重试）
 *  - POST /tenant/inbound/{id}/reject     R2 驳回（仅待受理；原因单选 + 备注必填 + 附件 ≤5）
 *  - POST /tenant/inbound/{id}/register   登记正向链（5% 整型边界 50351；此刻才加库存）
 *  - POST /tenant/inbound/{id}/print      打印核对单/补打（非状态节点）
 *  - POST /tenant/inbound/{id}/corrections  R3 纠错（登记后 ≤24h 50352 / 防重 50353 / 非法 50354）
 *
 * 产品口径（11 PRD §1.4-§1.7 / 线框 C·D·E）：
 *  - 受理二次确认「受理后批发商将不可撤回」；≤5% 黄条 + 差异备注必填；>5% 红条 + 登记按钮置灰；
 *  - 正向链 CONFIRMED 统一「已入库」；打印非状态节点，登记前核对、登记后补打；
 *  - 纠错改小遇已售按在库封顶，差额线下定责（弹窗内实时预览）。
 *  - ⚠️ 契约注记：状态机 ACCEPTED→REJECTED 不可达（驳回仅待受理态），受理后发现超差
 *    只能线下与商户协调（详见 13 §1.1 冻结矩阵）。
 *
 * 视觉：沿用 Skus.vue 的顶栏 + 左侧菜单 shell + el-table/el-form 风格。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Shop,
  User,
  Document,
  Coin,
  ChatLineSquare,
  Setting,
  TrendCharts,
  Goods,
  Box,
  Stamp,
  Van,
  RefreshLeft,
  Checked,
  AlarmClock,
  Remove,
  Printer,
  Refresh,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  EntityPickerDialog,
  makeClientPickerFetch,
  NavCountBadge,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type {
  Wholesaler,
  Sku,
  InboundRequest,
  InboundRegisterRequest,
  InboundRejectReason,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { inboundApi } from '@/api/inbound'
import { inventoryApi } from '@/api/inventory'
import { batchApi } from '@/api/batch'
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
const activeMenu = ref('/ta/inbound')

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
  { key: '/ta/inbound', label: '入库', icon: Box },
  { key: '/ta/outbound', label: '出库作业', icon: Van },
  { key: '/ta/returns', label: '退货受理', icon: RefreshLeft },
  { key: '/ta/stocktake', label: '盘点', icon: Checked },
  { key: '/ta/batches', label: '批次临期', icon: AlarmClock },
  { key: '/ta/clearance', label: '清库', icon: Remove },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '审批中心', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/inbound') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees' ||
    key === '/ta/skus' ||
    key === '/ta/wholesaler-applications' ||
    key === '/ta/approvals' ||
    key === '/ta/outbound' ||
    key === '/ta/returns' ||
    key === '/ta/stocktake' ||
    key === '/ta/batches' ||
    key === '/ta/clearance' ||
    key === '/ta/bills' ||
    key === '/ta/messages'
  ) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 商户选择器 ============
const wholesalerLoading = ref(false)
const wholesalers = ref<Wholesaler[]>([])
const selectedWholesalerId = ref<string>('')

/** 商户 id → 名称，供入库记录表回显（VO 只回 wholesalerId） */
const wholesalerNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const w of wholesalers.value) map[String(w.id)] = w.name
  return map
})

const fetchWholesalers = async () => {
  wholesalerLoading.value = true
  try {
    wholesalers.value = await wholesalerApi.list()
    if (!selectedWholesalerId.value && wholesalers.value.length > 0) {
      selectedWholesalerId.value = String(wholesalers.value[0].id)
      await onWholesalerChange()
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    wholesalerLoading.value = false
  }
}

const onWholesalerChange = async () => {
  // 换商户：重置已选 SKU，重新拉该商户 SKU 与入库记录
  form.skuId = ''
  await Promise.all([fetchSkus(), fetchRecords()])
}

// 弹窗选择器：商户（开放实体集，UX 规范 2026-07-25）
const wholesalerPickerColumns: EntityPickerColumn<Wholesaler>[] = [
  { label: '商户名称', prop: 'name', minWidth: 160 },
  { label: '简介', formatter: (w) => w.intro || '—', minWidth: 160 },
  { label: '创建时间', formatter: (w) => String(w.createdAt ?? '').slice(0, 10), width: 110 },
]

const fetchWholesalerPage = makeClientPickerFetch<Wholesaler>(
  () => wholesalers.value,
  (w, kw) => w.name.toLowerCase().includes(kw) || (w.intro ?? '').toLowerCase().includes(kw),
)

// ============ SKU 选择器（选定商户后拉其在售/全部 SKU） ============
const skuLoading = ref(false)
const skus = ref<Sku[]>([])

/** SKU id → 名称，供入库记录表回显（VO 只回 skuId） */
const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.name
  return map
})

const fetchSkus = async () => {
  if (!selectedWholesalerId.value) {
    skus.value = []
    return
  }
  skuLoading.value = true
  try {
    skus.value = await skuApi.list(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  } finally {
    skuLoading.value = false
  }
}

// 弹窗选择器：商品 SKU（开放实体集）
const skuPickerColumns: EntityPickerColumn<Sku>[] = [
  { label: '商品名称', prop: 'name', minWidth: 160 },
  { label: '规格', formatter: (s) => s.spec || '—', minWidth: 100 },
  { label: '单价', formatter: (s) => `¥${Number(s.unitPrice).toFixed(2)}`, width: 100, align: 'right' },
  { label: '状态', formatter: (s) => (s.listed ? '在售' : '已下架'), width: 90 },
]

const fetchSkuPage = makeClientPickerFetch<Sku>(
  () => skus.value,
  (s, kw) => s.name.toLowerCase().includes(kw) || (s.spec ?? '').toLowerCase().includes(kw),
)

/** 已选 SKU 回显（含规格，与原下拉 label 一致） */
const selectedSkuLabel = computed(() => {
  const s = skus.value.find((x) => String(x.id) === form.skuId)
  if (!s) return ''
  return s.spec ? `${s.name}（${s.spec}）` : s.name
})

// ============ 入库记录表 ============
const recordsLoading = ref(false)
const records = ref<InboundRequest[]>([])

const fetchRecords = async () => {
  if (!selectedWholesalerId.value) {
    records.value = []
    return
  }
  recordsLoading.value = true
  try {
    records.value = await inboundApi.list({ wholesalerId: selectedWholesalerId.value })
  } catch {
    // 全局 toast 已提示
  } finally {
    recordsLoading.value = false
  }
}

const formatTime = (v: string | null): string => {
  if (!v) return '—'
  // 后端 createdAt 为 LocalDateTime（无时区偏移），直接格式化本地展示串，不做时区转换
  return String(v).replace('T', ' ').slice(0, 19)
}

// ============ 登记入库表单 ============
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  skuId: '' as string,
  qty: undefined as number | undefined,
  palletQty: undefined as number | undefined,
  // P3b T4-W1 批次三字段（商户批次开关启用时必填，13 §3.2；代建=提交即登记按当刻开关校验）
  batchNo: '' as string,
  productionDate: '' as string,
  expiryDate: '' as string,
})

const rules: FormRules = {
  skuId: [{ required: true, message: '请选择商品 SKU', trigger: 'change' }],
  qty: [
    { required: true, message: '请输入入库数量', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || (v as unknown) === '') {
          cb(new Error('请输入入库数量'))
        } else if (!Number.isInteger(Number(v))) {
          cb(new Error('入库数量必须为整数'))
        } else if (Number(v) <= 0) {
          cb(new Error('入库数量必须大于 0'))
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

const resetForm = () => {
  form.qty = undefined
  form.palletQty = undefined
  form.batchNo = ''
  form.productionDate = ''
  form.expiryDate = ''
  formRef.value?.clearValidate()
}

// ============ 批次三字段（P3b T4-W1 · 13 §3.2） ============
// 收口 L-1：开关经 GET /wholesaler/tenants/{tenantId}/batch-config 读取（本仓任一 ACTIVE 角色可读），
// false=关闭档隐藏三字段；null=未知（拉取失败）保守常显；
// 必填校验仍以后端为权威（缺失 40003「缺少批次号/生产日期/到效期」toast 回显）。
const myTenantId = computed(() => {
  const fromInfo = auth.tenantInfo?.tenantId
  if (fromInfo) return String(fromInfo)
  const entry = auth.roles?.find((r) => (r.role === 'WK' || r.role === 'TA') && r.tenantId)
  return entry?.tenantId ? String(entry.tenantId) : ''
})

const batchEnabled = ref<boolean | null>(null)

const fetchBatchConfig = async () => {
  if (!myTenantId.value) return
  try {
    const cfg = await batchApi.config(myTenantId.value)
    batchEnabled.value = cfg.batchEnabled === 1
  } catch {
    // 42001/网络异常：保持 null（保守显示批次字段）
  }
}
const todayStr = (): string => {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 批次字段客户端预检（填了任一项则三项齐；日期口径同后端 40205/40206） */
const batchFieldError = computed<string>(() => {
  const anyFilled = Boolean(form.batchNo.trim() || form.productionDate || form.expiryDate)
  if (!anyFilled) return ''
  if (!form.batchNo.trim()) return '已填批次信息：批次号必填'
  if (form.batchNo.trim().length > 64) return '批次号最长 64 字'
  if (!form.productionDate) return '已填批次信息：生产日期必填'
  if (form.productionDate > todayStr()) return '生产日期不能晚于今天'
  if (!form.expiryDate) return '已填批次信息：到效期必填'
  if (form.expiryDate <= form.productionDate) return '到效期必须晚于生产日期'
  return ''
})

/** 到效期警示：过期=强警告（登记需二次确认 50364）；临期（≤30 天）=黄条放行 */
const batchExpiryState = computed<'expired' | 'near' | ''>(() => {
  if (batchFieldError.value || !form.expiryDate) return ''
  const today = todayStr()
  if (form.expiryDate <= today) return 'expired'
  const diff = Math.round(
    (new Date(`${form.expiryDate}T00:00:00`).getTime() - new Date(`${today}T00:00:00`).getTime()) /
      86400_000,
  )
  return diff <= 30 ? 'near' : ''
})

const buildRegisterPayload = (expiredConfirmed: boolean): InboundRegisterRequest => {
  const payload: InboundRegisterRequest = {
    wholesalerId: selectedWholesalerId.value,
    skuId: form.skuId,
    qty: Number(form.qty),
  }
  if (form.palletQty !== undefined && form.palletQty !== null) {
    payload.palletQty = Number(form.palletQty)
  }
  if (form.batchNo.trim()) {
    payload.batchNo = form.batchNo.trim()
    payload.productionDate = form.productionDate
    payload.expiryDate = form.expiryDate
    if (expiredConfirmed) payload.expiredConfirmed = true
  }
  return payload
}

const doProxyRegister = async (expiredConfirmed: boolean): Promise<void> => {
  const created = await inboundApi.register(buildRegisterPayload(expiredConfirmed))
  const stockTip =
    created.currentStock !== null && created.currentStock !== undefined
      ? `，当前库存 ${created.currentStock}`
      : ''
  ElMessage.success(`入库登记成功（单号 ${created.docNo}）${stockTip}`)
  resetForm()
  await fetchRecords()
}

const onSubmit = async () => {
  if (!formRef.value) return
  if (!selectedWholesalerId.value) {
    ElMessage.warning('请先选择商户')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (batchFieldError.value) {
    ElMessage.warning(batchFieldError.value)
    return
  }

  submitting.value = true
  try {
    await doProxyRegister(false)
  } catch (e) {
    // 50364 过期强警告：二次确认后携 expiredConfirmed=true 重发（04 §3.1）
    if (e instanceof ApiError && e.code === ErrorCode.STATE_BATCH_EXPIRED_CONFIRM_REQUIRED) {
      try {
        await ElMessageBox.confirm(
          `该批次到效期为 ${form.expiryDate}，已过期。过期货物入库后将立即进入临期/待清理流程。确认仍要登记入库？`,
          '过期批次强警告',
          { confirmButtonText: '确认登记（已当面核实）', cancelButtonText: '取消', type: 'error' },
        )
        await doProxyRegister(true)
      } catch (e2) {
        if (e2 instanceof ApiError) {
          // 二次提交仍失败（50362 等）：全局 toast 已提示
        }
        // 取消确认：静默返回，表单保留
      }
    }
    // 50362 批次号重复 / 40003 缺字段 / 40205/40206：全局 toast 已提示，表单保留供修改
  } finally {
    submitting.value = false
  }
}

// ==================== P3b T1 · 正向申请链工作台 ====================

/** 页面主 Tab：申请工作台（正向链）/ 代建登记（既有表单） */
const PAGE_TAB_FORWARD = 'forward'
const PAGE_TAB_PROXY = 'proxy'
const pageTab = ref(PAGE_TAB_FORWARD)

const formatQtyPct = (n: number) => (Math.round(n * 100) / 100).toString()

/** 工作台状态 Tab（正向链专属；REJECTED/WITHDRAWN 归「已关闭」历史） */
const WB_TABS: Array<{ name: string; label: string }> = [
  { name: 'SUBMITTED', label: '申请待受理' },
  { name: 'ACCEPTED', label: '已受理待登记' },
  { name: 'CONFIRMED', label: '已入库' },
  { name: 'REJECTED', label: '已驳回' },
]
const wbTab = ref('SUBMITTED')
const wbLoading = ref(false)
const wbRows = ref<InboundRequest[]>([])
/** 待受理角标（独立拉取，切 Tab 后仍准确） */
const wbPendingCount = ref(0)

/** 正向链过滤（列表端点返回全链单据，工作台只看 source=WA_SUBMIT） */
const onlyForward = (rows: InboundRequest[]) => rows.filter((r) => r.source === 'WA_SUBMIT')

const fetchWb = async () => {
  wbLoading.value = true
  try {
    const rows = onlyForward(await inboundApi.list({ status: wbTab.value }))
    wbRows.value = rows
    if (wbTab.value === 'SUBMITTED') wbPendingCount.value = rows.length
    void ensureWbSkuNames(rows)
  } catch {
    // 全局 toast 已提示
  } finally {
    wbLoading.value = false
  }
}

const fetchWbPendingCount = async () => {
  try {
    wbPendingCount.value = onlyForward(await inboundApi.list({ status: 'SUBMITTED' })).length
  } catch {
    /* 静默 */
  }
}

const onWbTabChange = () => void fetchWb()

/** 工作台 SKU 名称映射（跨商户；按需拉取并缓存，SKU 列表已放行库管员只读） */
const wbSkuNameMap = ref<Record<string, string>>({})
const wbSkuLoadedWholesalers = new Set<string>()

const ensureWbSkuNames = async (rows: InboundRequest[]) => {
  const wids = Array.from(new Set(rows.map((r) => String(r.wholesalerId)))).filter(
    (w) => w && !wbSkuLoadedWholesalers.has(w),
  )
  await Promise.all(
    wids.map(async (w) => {
      try {
        const list = await skuApi.list(w)
        wbSkuLoadedWholesalers.add(w)
        for (const s of list) {
          wbSkuNameMap.value[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
        }
      } catch {
        // 名称拉取失败回退展示 skuId（不阻塞队列）
      }
    }),
  )
}

const wbSkuLabel = (id: unknown): string => wbSkuNameMap.value[String(id)] || String(id)

/** 「同批 N 单」标识（当前列表内同 batchSubmitId 计数） */
const wbBatchCountMap = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const r of wbRows.value) {
    const key = r.batchSubmitId ? String(r.batchSubmitId) : ''
    if (key) map[key] = (map[key] ?? 0) + 1
  }
  return map
})
const wbBatchTag = (row: InboundRequest): string => {
  const key = row.batchSubmitId ? String(row.batchSubmitId) : ''
  const n = key ? (wbBatchCountMap.value[key] ?? 0) : 0
  return n >= 2 ? `同批 ${n} 单` : ''
}

/** CAS/状态类冲突统一「刷新重试」处理（T1-BE 备注 3：50330/50331 两态均按刷新） */
const refreshOnStateConflict = async (e: unknown): Promise<boolean> => {
  if (
    e instanceof ApiError &&
    (e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID ||
      e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
      e.code === ErrorCode.STATE_WA_NOT_ACTIVE)
  ) {
    await Promise.all([fetchWb(), fetchWbPendingCount()])
    return true
  }
  return false
}

// ============ 受理（一次点击 + 二次确认） ============
const acceptingId = ref('')

const onAccept = async (row: InboundRequest) => {
  try {
    await ElMessageBox.confirm(
      `受理申请 ${row.docNo}（${wbSkuLabel(row.skuId)} × ${row.requestedQty ?? row.qty}）？受理后批发商将不可撤回。`,
      '确认受理',
      { confirmButtonText: '确认受理', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  acceptingId.value = String(row.id)
  try {
    const updated = await inboundApi.accept(String(row.id))
    ElMessage.success(`申请 ${updated.docNo} 已受理，请安排收货登记`)
    await Promise.all([fetchWb(), fetchWbPendingCount()])
  } catch (e) {
    await refreshOnStateConflict(e)
  } finally {
    acceptingId.value = ''
  }
}

// ============ R2 驳回弹窗 ============
const REJECT_REASONS: Array<{ value: InboundRejectReason; label: string }> = [
  { value: 'QTY', label: '数量不符' },
  { value: 'QUALITY', label: '质量问题' },
  { value: 'BATCH', label: '批次不符' },
  { value: 'OTHER', label: '其他' },
]
const rejectReasonLabel = (v: string | null | undefined) =>
  REJECT_REASONS.find((r) => r.value === v)?.label ?? v ?? '—'

const rejectVisible = ref(false)
const rejectTarget = ref<InboundRequest | null>(null)
const rejectSubmitting = ref(false)
const rejectForm = reactive({
  reason: '' as InboundRejectReason | '',
  remark: '',
  attachments: [] as string[],
})

const openReject = (row: InboundRequest) => {
  rejectTarget.value = row
  rejectForm.reason = ''
  rejectForm.remark = ''
  rejectForm.attachments = []
  rejectVisible.value = true
}

const onRejectSubmit = async () => {
  const row = rejectTarget.value
  if (!row) return
  if (!rejectForm.reason) {
    ElMessage.warning('请选择驳回原因')
    return
  }
  if (!rejectForm.remark.trim()) {
    ElMessage.warning('请填写驳回备注')
    return
  }
  rejectSubmitting.value = true
  try {
    const updated = await inboundApi.reject(String(row.id), {
      reason: rejectForm.reason,
      remark: rejectForm.remark.trim(),
      ...(rejectForm.attachments.length ? { attachments: rejectForm.attachments } : {}),
    })
    rejectVisible.value = false
    ElMessage.success(`申请 ${updated.docNo} 已驳回，批发商可复制重建后重新提交`)
    await Promise.all([fetchWb(), fetchWbPendingCount()])
  } catch (e) {
    if (await refreshOnStateConflict(e)) rejectVisible.value = false
  } finally {
    rejectSubmitting.value = false
  }
}

// ============ 登记入库弹窗（5% 差异边界，线框 D） ============
const registerVisible = ref(false)
const registerTarget = ref<InboundRequest | null>(null)
const registerSubmitting = ref(false)
const registerForm = reactive({
  actualQty: undefined as number | undefined,
  palletQty: undefined as number | undefined,
  remark: '',
  attachments: [] as string[],
})

const openRegister = (row: InboundRequest) => {
  registerTarget.value = row
  registerForm.actualQty = row.requestedQty ?? row.qty
  registerForm.palletQty = row.palletQty ?? undefined
  registerForm.remark = ''
  registerForm.attachments = []
  registerVisible.value = true
}

/** 差异件数（实登 − 申请） */
const regDiff = computed(() => {
  const row = registerTarget.value
  const actual = registerForm.actualQty
  if (!row || actual === undefined || actual === null) return 0
  return Number(actual) - (row.requestedQty ?? row.qty)
})
/** 差异百分比（展示用） */
const regDiffPct = computed(() => {
  const row = registerTarget.value
  const requested = row?.requestedQty ?? row?.qty ?? 0
  if (!requested) return 0
  return (Math.abs(regDiff.value) / requested) * 100
})
/** >5% 禁止登记（与后端同口径整型算式：|actual−requested|×100 > requested×5，含等于放行） */
const regDiffExceeded = computed(() => {
  const row = registerTarget.value
  const actual = registerForm.actualQty
  if (!row || actual === undefined || actual === null) return false
  const requested = row.requestedQty ?? row.qty
  return Math.abs(Number(actual) - requested) * 100 > requested * 5
})
const regValid = computed(() => {
  const a = registerForm.actualQty
  if (a === undefined || a === null || !Number.isInteger(Number(a)) || Number(a) <= 0) return false
  if (regDiffExceeded.value) return false
  if (regDiff.value !== 0 && !registerForm.remark.trim()) return false
  return true
})

/** 单据自带批次的过期态（P3b T4-W1：登记时按单据自身 expiryDate 判 50364） */
const registerBatchExpired = computed<boolean>(() => {
  const e = registerTarget.value?.expiryDate
  if (!e) return false
  const today = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return String(e).slice(0, 10) <= `${today.getFullYear()}-${p(today.getMonth() + 1)}-${p(today.getDate())}`
})

const doForwardRegister = async (expiredConfirmed: boolean): Promise<void> => {
  const row = registerTarget.value
  if (!row) return
  const updated = await inboundApi.registerForward(String(row.id), {
    actualQty: Number(registerForm.actualQty),
    ...(registerForm.palletQty !== undefined && registerForm.palletQty !== null
      ? { palletQty: Number(registerForm.palletQty) }
      : {}),
    ...(registerForm.remark.trim() ? { remark: registerForm.remark.trim() } : {}),
    ...(registerForm.attachments.length ? { attachments: registerForm.attachments } : {}),
    ...(expiredConfirmed ? { expiredConfirmed: true } : {}),
  })
  registerVisible.value = false
  const stockTip =
    updated.currentStock !== null && updated.currentStock !== undefined
      ? `，当前库存 ${updated.currentStock}`
      : ''
  ElMessage.success(`申请 ${updated.docNo} 已登记入库（实登 ${updated.qty} 件）${stockTip}`)
  await Promise.all([fetchWb(), fetchWbPendingCount()])
}

const onRegisterSubmit = async () => {
  const row = registerTarget.value
  if (!row || !regValid.value) return
  registerSubmitting.value = true
  try {
    await doForwardRegister(false)
  } catch (e) {
    // 50351 超界回显：弹窗保持打开，红条已由 regDiffExceeded 展示；服务端兜底命中时刷新单据
    if (e instanceof ApiError && e.code === ErrorCode.STATE_INBOUND_QTY_DIFF_EXCEEDED) {
      // 全局 toast 已提示「差异超 5%，请驳回后重新申请」，表单保留供改数
    } else if (e instanceof ApiError && e.code === ErrorCode.STATE_BATCH_EXPIRED_CONFIRM_REQUIRED) {
      // 50364 过期批次强警告（04 §3.1）：二次确认后携 expiredConfirmed=true 重发
      try {
        await ElMessageBox.confirm(
          `该批次到效期为 ${String(row.expiryDate ?? '').slice(0, 10)}，已过期。` +
            '过期货物入库后将立即进入临期/待清理流程。确认仍要登记入库？',
          '过期批次强警告',
          { confirmButtonText: '确认登记（已当面核实）', cancelButtonText: '取消', type: 'error' },
        )
        await doForwardRegister(true)
      } catch (e2) {
        if (e2 instanceof ApiError) {
          if (await refreshOnStateConflict(e2)) registerVisible.value = false
        }
        // 取消确认：弹窗保留
      }
    } else {
      if (await refreshOnStateConflict(e)) registerVisible.value = false
    }
  } finally {
    registerSubmitting.value = false
  }
}

// ============ 打印核对单（非状态节点，登记前后均可） ============
const printVisible = ref(false)
const printTarget = ref<InboundRequest | null>(null)
const printingId = ref('')

const onPrintForward = async (row: InboundRequest) => {
  printingId.value = String(row.id)
  try {
    const updated = await inboundApi.print(String(row.id))
    printTarget.value = updated
    printVisible.value = true
    await fetchWb()
  } catch {
    // 全局 toast 已提示
  } finally {
    printingId.value = ''
  }
}

const doWindowPrint = () => {
  window.print()
}

// ============ R3 登记纠错（≤24h，线框 E） ============
/** 24h 窗口内（前端置灰辅助；权威判定在后端 SQL，50352 兜底） */
const within24h = (row: InboundRequest): boolean => {
  if (!row.registeredAt) return false
  const t = new Date(String(row.registeredAt)).getTime()
  return Number.isFinite(t) && Date.now() - t <= 24 * 3600 * 1000
}

const corrVisible = ref(false)
const corrTarget = ref<InboundRequest | null>(null)
const corrSubmitting = ref(false)
const corrForm = reactive({
  newQty: undefined as number | undefined,
  reason: '',
})
/** 当前在库（改小封顶预览；轻量快照，实际以审批时刻锁内为准） */
const corrOnhand = ref<number | null>(null)

const openCorrection = async (row: InboundRequest) => {
  corrTarget.value = row
  corrForm.newQty = row.qty
  corrForm.reason = ''
  corrOnhand.value = null
  corrVisible.value = true
  try {
    const list = await inventoryApi.query({
      wholesalerId: String(row.wholesalerId),
      skuId: String(row.skuId),
    })
    corrOnhand.value = list.length ? Number(list[0].qty) : 0
  } catch {
    // 预览失败不阻塞表单（审批弹窗仍有权威预览）
  }
}

/** 纠错差额（新 − 原实登） */
const corrDelta = computed(() => {
  const row = corrTarget.value
  const n = corrForm.newQty
  if (!row || n === undefined || n === null) return 0
  return Number(n) - row.qty
})
/** 改小封顶预览：实际冲销 = min(|delta|, max(onhand,0)) */
const corrApplied = computed(() => {
  if (corrDelta.value >= 0) return 0
  const onhand = Math.max(corrOnhand.value ?? 0, 0)
  return Math.min(Math.abs(corrDelta.value), onhand)
})
const corrShortfall = computed(() =>
  corrDelta.value < 0 ? Math.abs(corrDelta.value) - corrApplied.value : 0,
)
const corrValid = computed(() => {
  const n = corrForm.newQty
  if (n === undefined || n === null || !Number.isInteger(Number(n)) || Number(n) < 0) return false
  if (corrDelta.value === 0) return false
  return Boolean(corrForm.reason.trim())
})

const onCorrectionSubmit = async () => {
  const row = corrTarget.value
  if (!row || !corrValid.value) return
  corrSubmitting.value = true
  try {
    await inboundApi.createCorrection(String(row.id), {
      newQty: Number(corrForm.newQty),
      reason: corrForm.reason.trim(),
    })
    corrVisible.value = false
    ElMessage.success('纠错申请已提交，等待租户管理员审批')
  } catch (e) {
    // 50352 超窗 / 50353 防重 / 50354 非法：全局 toast 已提示
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_INBOUND_CORRECTION_WINDOW_CLOSED ||
        e.code === ErrorCode.STATE_INBOUND_CORRECTION_PENDING_EXISTS)
    ) {
      corrVisible.value = false
      await fetchWb()
    }
  } finally {
    corrSubmitting.value = false
  }
}

onMounted(() => {
  void fetchWholesalers()
  void fetchWb()
  void fetchBatchConfig()
})
</script>

<template>
  <div class="ta-shell">
    <!-- 顶栏 -->
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #store>
        <WarehouseSwitcher />
      </template>
    </AppTopbar>

    <div class="ta-body">
      <!-- 左侧菜单 -->
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">入库</h2>
            <p class="page-head__sub">
              {{
                pageTab === PAGE_TAB_FORWARD
                  ? '批发商提交的入库申请在此受理、驳回与登记；提交与受理不影响库存与计费，登记后库存实时增加'
                  : '仓管员为商户 SKU 现场代建登记入库，登记后库存实时增加'
              }}
            </p>
          </div>
          <el-button
            v-if="pageTab === PAGE_TAB_FORWARD"
            :icon="Refresh"
            :loading="wbLoading"
            @click="fetchWb"
          >
            刷新
          </el-button>
        </header>

        <!-- 页面主 Tab：申请工作台 / 现场代建（P3b T1） -->
        <el-tabs v-model="pageTab" class="page-tabs" data-test="inbound-page-tabs">
          <el-tab-pane :name="PAGE_TAB_FORWARD">
            <template #label>
              <span class="tab-label">
                申请工作台
                <NavCountBadge :count="wbPendingCount" />
              </span>
            </template>
          </el-tab-pane>
          <el-tab-pane label="现场代建入库" :name="PAGE_TAB_PROXY" />
        </el-tabs>

        <!-- ============ 申请工作台（正向链） ============ -->
        <section v-if="pageTab === PAGE_TAB_FORWARD" class="card">
          <el-tabs v-model="wbTab" data-test="wb-tabs" @tab-change="onWbTabChange">
            <el-tab-pane v-for="t in WB_TABS" :key="t.name" :name="t.name">
              <template #label>
                <span class="tab-label">
                  {{ t.label }}
                  <NavCountBadge v-if="t.name === 'SUBMITTED'" :count="wbPendingCount" />
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>

          <el-table
            v-loading="wbLoading"
            :data="wbRows"
            row-key="id"
            class="inbound-table"
            data-test="wb-table"
            :empty-text="wbTab === 'SUBMITTED' ? '暂无待受理申请' : '暂无单据'"
          >
            <el-table-column prop="docNo" label="申请单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="批发商" min-width="130">
              <template #default="{ row }">
                {{ wholesalerNameMap[String(row.wholesalerId)] || row.wholesalerId }}
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ wbSkuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="申请件数" width="100" align="right">
              <template #default="{ row }">{{ row.requestedQty ?? row.qty }}</template>
            </el-table-column>
            <el-table-column v-if="wbTab === 'CONFIRMED'" label="实登件数" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="托盘" width="80" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.palletQty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标识" width="110">
              <template #default="{ row }">
                <el-tag
                  v-if="wbBatchTag(row as InboundRequest)"
                  type="info"
                  effect="plain"
                  size="small"
                  data-test="wb-batch-tag"
                >
                  {{ wbBatchTag(row as InboundRequest) }}
                </el-tag>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column
              v-if="wbTab === 'REJECTED'"
              label="驳回原因"
              width="120"
            >
              <template #default="{ row }">
                {{ rejectReasonLabel(row.rejectReason) }}
              </template>
            </el-table-column>
            <el-table-column :label="wbTab === 'CONFIRMED' ? '登记时间' : '提交时间'" width="170">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ formatTime(wbTab === 'CONFIRMED' ? row.registeredAt : row.createdAt) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="打印" width="90" align="center">
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
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'SUBMITTED'">
                  <el-button
                    type="primary"
                    size="small"
                    :loading="acceptingId === String(row.id)"
                    data-test="accept-btn"
                    @click="onAccept(row as InboundRequest)"
                  >
                    受理
                  </el-button>
                  <el-button
                    type="danger"
                    size="small"
                    plain
                    data-test="reject-btn"
                    @click="openReject(row as InboundRequest)"
                  >
                    驳回
                  </el-button>
                </template>
                <template v-else-if="row.status === 'ACCEPTED'">
                  <el-button
                    type="primary"
                    size="small"
                    data-test="register-btn"
                    @click="openRegister(row as InboundRequest)"
                  >
                    登记入库
                  </el-button>
                  <el-button
                    size="small"
                    :icon="Printer"
                    :loading="printingId === String(row.id)"
                    data-test="print-btn"
                    @click="onPrintForward(row as InboundRequest)"
                  >
                    核对单
                  </el-button>
                </template>
                <template v-else-if="row.status === 'CONFIRMED'">
                  <el-button
                    size="small"
                    :icon="Printer"
                    :loading="printingId === String(row.id)"
                    data-test="reprint-btn"
                    @click="onPrintForward(row as InboundRequest)"
                  >
                    补打
                  </el-button>
                  <el-tooltip
                    :content="
                      within24h(row as InboundRequest)
                        ? '登记后 24 小时内可发起纠错'
                        : '已超过 24 小时纠错窗口，请通过盘点调整'
                    "
                    placement="top"
                  >
                    <span class="corr-btn-wrap">
                      <el-button
                        size="small"
                        type="warning"
                        plain
                        :disabled="!within24h(row as InboundRequest)"
                        data-test="corr-btn"
                        @click="openCorrection(row as InboundRequest)"
                      >
                        发起纠错
                      </el-button>
                    </span>
                  </el-tooltip>
                </template>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- 商户选择器（现场代建） -->
        <section v-if="pageTab === PAGE_TAB_PROXY" class="card">
          <div class="toolbar">
            <span class="toolbar__label">商户</span>
            <EntityPickerDialog
              v-model="selectedWholesalerId"
              title="选择商户"
              placeholder="点击选择商户"
              :columns="wholesalerPickerColumns"
              :fetch="fetchWholesalerPage"
              :selected-label="wholesalerNameMap[selectedWholesalerId] || ''"
              class="toolbar__select"
              @change="onWholesalerChange"
            />
            <span v-if="!wholesalerLoading && wholesalers.length === 0" class="toolbar__empty">
              当前店铺暂无商户，请先在「入驻商户」创建
            </span>
          </div>

          <!-- 登记入库表单 -->
          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            class="inbound-form"
            @submit.prevent="onSubmit"
          >
            <div class="inbound-form__row">
              <el-form-item label="商品 SKU" prop="skuId" class="inbound-form__item">
                <EntityPickerDialog
                  v-model="form.skuId"
                  title="选择商品"
                  placeholder="点击选择商品"
                  :columns="skuPickerColumns"
                  :fetch="fetchSkuPage"
                  :selected-label="selectedSkuLabel"
                  :disabled="!selectedWholesalerId"
                  class="full-width"
                />
              </el-form-item>

              <el-form-item label="入库数量" prop="qty" class="inbound-form__item">
                <el-input-number
                  v-model="form.qty"
                  :min="1"
                  :precision="0"
                  :step="1"
                  :controls="false"
                  placeholder="必填，大于 0 的整数"
                  class="full-width"
                />
              </el-form-item>

              <el-form-item label="托盘数（可选）" prop="palletQty" class="inbound-form__item">
                <el-input-number
                  v-model="form.palletQty"
                  :min="0"
                  :precision="0"
                  :step="1"
                  :controls="false"
                  placeholder="本次托盘数，默认 0"
                  class="full-width"
                />
              </el-form-item>

              <el-form-item label=" " class="inbound-form__item inbound-form__submit">
                <el-button
                  type="primary"
                  :icon="Box"
                  :loading="submitting"
                  :disabled="!selectedWholesalerId"
                  @click="onSubmit"
                >
                  登记入库
                </el-button>
              </el-form-item>
            </div>

            <!-- P3b T4-W1 批次三字段（收口 L-1：batchEnabled=false 关闭档隐藏；unknown 保守显示） -->
            <div v-if="batchEnabled !== false" class="inbound-form__row">
              <el-form-item
                :label="batchEnabled === true ? '批次号 *' : '批次号（开启批次管理时必填）'"
                class="inbound-form__item"
              >
                <el-input
                  v-model="form.batchNo"
                  maxlength="64"
                  placeholder="如 BATCH-A1；(商户,商品,批次号) 唯一"
                  class="full-width"
                  data-test="proxy-batch-no"
                />
              </el-form-item>
              <el-form-item label="生产日期" class="inbound-form__item">
                <el-date-picker
                  v-model="form.productionDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="不晚于今天"
                  class="full-width"
                  data-test="proxy-production-date"
                />
              </el-form-item>
              <el-form-item label="到效期" class="inbound-form__item">
                <el-date-picker
                  v-model="form.expiryDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="晚于生产日期"
                  class="full-width"
                  data-test="proxy-expiry-date"
                />
              </el-form-item>
            </div>
            <p v-if="batchFieldError" class="batch-error" data-test="proxy-batch-error">
              {{ batchFieldError }}
            </p>
            <el-alert
              v-else-if="batchExpiryState === 'expired'"
              type="error"
              :closable="false"
              show-icon
              class="batch-alert"
              data-test="proxy-expired-alert"
              title="该批次到效期不晚于今天（已过期）：登记时将弹出强警告，需二次确认方可入库"
            />
            <el-alert
              v-else-if="batchExpiryState === 'near'"
              type="warning"
              :closable="false"
              show-icon
              class="batch-alert"
              data-test="proxy-near-alert"
              title="该批次临近到效期（≤30 天）：登记放行，入库后将立即进入临期列表"
            />
          </el-form>
        </section>

        <!-- 入库记录表（现场代建） -->
        <section v-if="pageTab === PAGE_TAB_PROXY" class="card">
          <div class="card__head">
            <h3 class="card__title">入库记录</h3>
            <el-button text :loading="recordsLoading" @click="fetchRecords">刷新</el-button>
          </div>
          <el-table
            v-loading="recordsLoading"
            :data="records"
            stripe
            class="inbound-table"
            empty-text="该商户暂无入库记录，登记后将在此显示"
          >
            <el-table-column prop="docNo" label="入库单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商户" min-width="140">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ wholesalerNameMap[String(row.wholesalerId)] || row.wholesalerId }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="商品 SKU" min-width="160">
              <template #default="{ row }">
                <span>{{ skuNameMap[String(row.skuId)] || row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="托盘数" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.palletQty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="登记时间" width="180">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- R2 驳回弹窗（线框 C：原因单选 + 备注必填 + 照片 ≤5） -->
    <el-dialog
      v-model="rejectVisible"
      title="驳回入库申请"
      width="520px"
      :close-on-click-modal="false"
      data-test="reject-dialog"
    >
      <template v-if="rejectTarget">
        <p class="dlg-doc">
          <span class="cell-name">{{ rejectTarget.docNo }}</span>
          <span class="cell-muted">
            {{ wholesalerNameMap[String(rejectTarget.wholesalerId)] || '' }} ·
            {{ wbSkuLabel(rejectTarget.skuId) }} ×
            {{ rejectTarget.requestedQty ?? rejectTarget.qty }}
          </span>
        </p>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="驳回原因（必选）" required>
            <el-radio-group v-model="rejectForm.reason" data-test="reject-reason">
              <el-radio v-for="r in REJECT_REASONS" :key="r.value" :value="r.value">
                {{ r.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="备注（必填）" required>
            <el-input
              v-model="rejectForm.remark"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="向批发商说明驳回原因（≤200 字）"
              data-test="reject-remark"
            />
          </el-form-item>
          <el-form-item label="照片（选填 ≤5 张）">
            <AttachmentUpload v-model="rejectForm.attachments" :max="5" />
          </el-form-item>
        </el-form>
        <p class="dlg-note">驳回不影响库存与计费；批发商可一键复制重建后重新提交。</p>
      </template>
      <template #footer>
        <el-button :disabled="rejectSubmitting" @click="rejectVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="rejectSubmitting"
          data-test="reject-submit"
          @click="onRejectSubmit"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>

    <!-- 登记入库弹窗（线框 D：5% 差异边界） -->
    <el-dialog
      v-model="registerVisible"
      title="登记入库"
      width="560px"
      :close-on-click-modal="false"
      data-test="register-dialog"
    >
      <template v-if="registerTarget">
        <el-descriptions :column="2" size="small" border class="reg-info">
          <el-descriptions-item label="单号" :span="2">
            {{ registerTarget.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="批发商">
            {{ wholesalerNameMap[String(registerTarget.wholesalerId)] || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ wbSkuLabel(registerTarget.skuId) }}
          </el-descriptions-item>
          <el-descriptions-item label="申请件数">
            {{ registerTarget.requestedQty ?? registerTarget.qty }} 件（只读）
          </el-descriptions-item>
          <!-- P3b T4-W1：单据自带批次三字段（提交时录入，登记按此判过期 50364） -->
          <template v-if="registerTarget.batchNo">
            <el-descriptions-item label="批次号">
              {{ registerTarget.batchNo }}
            </el-descriptions-item>
            <el-descriptions-item label="生产日期">
              {{ String(registerTarget.productionDate ?? '').slice(0, 10) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="到效期" :span="2">
              {{ String(registerTarget.expiryDate ?? '').slice(0, 10) || '—' }}
            </el-descriptions-item>
          </template>
        </el-descriptions>

        <el-alert
          v-if="registerTarget.batchNo && registerBatchExpired"
          type="error"
          :closable="false"
          show-icon
          class="reg-alert"
          data-test="register-expired-alert"
          title="该批次到效期不晚于今天（已过期）：点击登记后将弹出强警告，需二次确认方可入库"
        />

        <el-form label-position="top" @submit.prevent>
          <el-form-item label="实际入库件数（必填）" required>
            <el-input-number
              v-model="registerForm.actualQty"
              :min="1"
              :precision="0"
              :step="1"
              class="full-width"
              data-test="register-actual-qty"
            />
          </el-form-item>

          <!-- ≤5% 黄条 / >5% 红条（含等于放行，整型算式与后端一致） -->
          <el-alert
            v-if="regDiffExceeded"
            type="error"
            :closable="false"
            class="reg-alert"
            data-test="diff-exceeded-alert"
          >
            实收与申请件数差异 {{ Math.abs(regDiff) }} 件（{{ formatQtyPct(regDiffPct) }}%），
            超过 5%，禁止登记；请驳回后由批发商重新提交（本单已受理，需线下与批发商协调）。
          </el-alert>
          <el-alert
            v-else-if="regDiff !== 0"
            type="warning"
            :closable="false"
            class="reg-alert"
            data-test="diff-warn-alert"
          >
            实际与申请差 {{ Math.abs(regDiff) }} 件（{{ formatQtyPct(regDiffPct) }}%），
            将按实登记，差异备注必填。
          </el-alert>

          <el-form-item
            v-if="regDiff !== 0 && !regDiffExceeded"
            label="差异备注（必填）"
            required
          >
            <el-input
              v-model="registerForm.remark"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="说明差异原因（≤200 字）"
              data-test="register-remark"
            />
          </el-form-item>

          <el-form-item label="占用托盘数（可改）">
            <el-input-number
              v-model="registerForm.palletQty"
              :min="0"
              :precision="0"
              :step="1"
              class="full-width"
              data-test="register-pallet"
            />
          </el-form-item>

          <el-form-item label="照片附件（选填 ≤5 张）">
            <AttachmentUpload v-model="registerForm.attachments" :max="5" />
          </el-form-item>
        </el-form>

        <p class="dlg-note">登记成功即加库存并生成入库流水，计费自次日 0:00 起算。</p>
      </template>
      <template #footer>
        <el-button
          :icon="Printer"
          :disabled="registerSubmitting"
          @click="registerTarget && onPrintForward(registerTarget)"
        >
          打印核对单
        </el-button>
        <el-button :disabled="registerSubmitting" @click="registerVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="registerSubmitting"
          :disabled="!regValid"
          data-test="register-submit"
          @click="onRegisterSubmit"
        >
          登记入库
        </el-button>
      </template>
    </el-dialog>

    <!-- R3 发起纠错弹窗（线框 E：封顶预览） -->
    <el-dialog
      v-model="corrVisible"
      title="登记纠错（24 小时内）"
      width="520px"
      :close-on-click-modal="false"
      data-test="corr-dialog"
    >
      <template v-if="corrTarget">
        <el-descriptions :column="2" size="small" border class="reg-info">
          <el-descriptions-item label="原单" :span="2">
            {{ corrTarget.docNo }}
          </el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ wbSkuLabel(corrTarget.skuId) }}
          </el-descriptions-item>
          <el-descriptions-item label="原实登件数">{{ corrTarget.qty }} 件</el-descriptions-item>
        </el-descriptions>

        <el-form label-position="top" @submit.prevent>
          <el-form-item label="更正后件数（必填）" required>
            <el-input-number
              v-model="corrForm.newQty"
              :min="0"
              :precision="0"
              :step="1"
              class="full-width"
              data-test="corr-new-qty"
            />
          </el-form-item>

          <p class="corr-delta" data-test="corr-delta">
            差额：
            <span :class="corrDelta > 0 ? 'delta-up' : corrDelta < 0 ? 'delta-down' : ''">
              {{ corrDelta > 0 ? `+${corrDelta}` : corrDelta }} 件
            </span>
            <span v-if="corrDelta === 0" class="cell-muted">（与原实登相同，无法提交）</span>
          </p>

          <el-alert
            v-if="corrDelta < 0"
            :type="corrShortfall > 0 ? 'error' : 'info'"
            :closable="false"
            class="reg-alert"
            data-test="corr-cap-alert"
          >
            <template v-if="corrOnhand === null">正在读取当前在库…</template>
            <template v-else-if="corrShortfall > 0">
              当前在库 {{ corrOnhand }} 件，将按剩余在库封顶冲销 {{ corrApplied }} 件；
              差额 {{ corrShortfall }} 件已售出无法冲销，将写入纠错单备注，责任线下认定。
            </template>
            <template v-else>
              当前在库 {{ corrOnhand }} 件，审批通过后将冲销 {{ corrApplied }} 件（库存充足，无差额）。
            </template>
          </el-alert>
          <el-alert
            v-else-if="corrDelta > 0"
            type="info"
            :closable="false"
            class="reg-alert"
          >
            审批通过后将补录 {{ corrDelta }} 件入库流水（沿用原入库时间计费）。
          </el-alert>

          <el-form-item label="纠错理由（必填）" required>
            <el-input
              v-model="corrForm.reason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="说明纠错原因，例如现场复核多记 / 少记（≤200 字）"
              data-test="corr-reason"
            />
          </el-form-item>
        </el-form>

        <p class="dlg-note">
          提交后进入租户管理员审批；通过后生效并联动库存，驳回则原单不变（窗口内可重新发起）。
        </p>
      </template>
      <template #footer>
        <el-button :disabled="corrSubmitting" @click="corrVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="corrSubmitting"
          :disabled="!corrValid"
          data-test="corr-submit"
          @click="onCorrectionSubmit"
        >
          提交审批
        </el-button>
      </template>
    </el-dialog>

    <!-- 打印核对单（票面区域走 @media print 输出；申请/实登双值展示） -->
    <el-dialog
      v-model="printVisible"
      title="入库申请核对单"
      width="640px"
      :close-on-click-modal="false"
      data-test="print-dialog"
    >
      <div v-if="printTarget" class="print-sheet" data-test="print-sheet">
        <h3 class="print-sheet__title">入库单</h3>
        <p class="print-sheet__doc-no">{{ printTarget.docNo }}</p>
        <table class="print-sheet__table">
          <tbody>
            <tr>
              <th>批发商</th>
              <td>{{ wholesalerNameMap[String(printTarget.wholesalerId)] || printTarget.wholesalerId }}</td>
              <th>状态</th>
              <td>
                {{
                  printTarget.status === 'CONFIRMED'
                    ? '已入库'
                    : printTarget.status === 'ACCEPTED'
                      ? '已受理（核对用）'
                      : '待受理（核对用）'
                }}
              </td>
            </tr>
            <tr>
              <th>商品</th>
              <td>{{ wbSkuLabel(printTarget.skuId) }}</td>
              <th>托盘数</th>
              <td>{{ printTarget.palletQty ?? 0 }}</td>
            </tr>
            <tr>
              <th>申请件数</th>
              <td class="print-sheet__qty">{{ printTarget.requestedQty ?? printTarget.qty }} 件</td>
              <th>实登件数</th>
              <td class="print-sheet__qty">
                {{ printTarget.status === 'CONFIRMED' ? `${printTarget.qty} 件` : '—' }}
              </td>
            </tr>
            <tr>
              <th>提交时间</th>
              <td>{{ formatTime(printTarget.createdAt) }}</td>
              <th>登记时间</th>
              <td>{{ formatTime(printTarget.registeredAt ?? null) }}</td>
            </tr>
            <tr>
              <th>打印次数</th>
              <td>第 {{ printTarget.printCount ?? 1 }} 次</td>
              <th>打印时间</th>
              <td>{{ formatTime(printTarget.printedAt ?? null) }}</td>
            </tr>
          </tbody>
        </table>
        <div class="print-sheet__signs">
          <span>库管员签字：__________________</span>
          <span>送货人签字：__________________</span>
        </div>
        <p class="print-sheet__note">
          登记前打印用于收货核对，登记后补打用于盖章存档；申请与实登双值以单据为准。
        </p>
      </div>
      <template #footer>
        <el-button @click="printVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Printer" data-test="do-print-btn" @click="doWindowPrint">
          打印
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


/* ===== body ===== */
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

/* ===== 主区 ===== */
.ta-main {
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

/* ===== 卡片 + 工具栏 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}
.card__title {
  margin: 0;
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}
.toolbar__label {
  font-size: var(--font-size-body);
  color: var(--color-fg-2);
  font-weight: var(--font-weight-medium);
}
.toolbar__select {
  width: 260px;
}
.toolbar__empty {
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
}

/* ===== 登记表单 ===== */
.inbound-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: flex-end;
}
.inbound-form__item {
  flex: 1 1 200px;
  margin-bottom: 0;
}
.inbound-form__submit {
  flex: 0 0 auto;
}

/* ===== 记录表 ===== */
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
.full-width {
  width: 100%;
}

/* ===== P3b T1 工作台 ===== */
.page-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}
.corr-btn-wrap {
  display: inline-block;
  margin-left: var(--space-3);
}

.dlg-doc {
  margin: 0 0 var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.dlg-note {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  line-height: 1.6;
}
.reg-info {
  margin-bottom: var(--space-3);
}
.reg-alert {
  margin-bottom: var(--space-3);
}
.reg-alert :deep(.el-alert__description) {
  margin: 0;
}
.corr-delta {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-body);
  color: var(--color-fg-2);
}
.delta-up {
  color: var(--color-success);
  font-weight: var(--font-weight-bold);
}
.delta-down {
  color: var(--color-danger);
  font-weight: var(--font-weight-bold);
}

/* ===== 打印票面（复用 ta/Outbound.vue 版式先例） ===== */
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

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .toolbar__select {
    width: 100%;
  }
  .inbound-form__item {
    flex: 1 1 100%;
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

/* ===== P3b T4 批次三字段 ===== */
.batch-error {
  margin: 0 0 var(--space-3);
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}
.batch-alert {
  margin-bottom: var(--space-3);
}
</style>
