<script setup lang="ts">
/**
 * TA 店铺设置（PC）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §2.2（地图地址 + 5 个总开关）+ §2.4（计费规则变更确认 R20）
 *  - 规则：shared/product/05-business-rules.md §1（计费维度）/ §8（容量公示）/ §9（临期）/ §13 开关矩阵
 *  - 视觉：MASTER 设计 token，沿用 Dashboard.vue 的顶栏 + 左侧菜单 shell 与 Register.vue 的 el-form 风格
 *  - 故事：US-TA-04（计费）/ US-TA-10（容量公示）/ US-TA-11（拍照）/ US-TA-12（批次）
 *
 * 契约：
 *  - GET  /tenant/me  → TenantSettings（tenantApi.getSettings；billingDim 为只读镜像）
 *  - PUT  /tenant/me  ← UpdateTenantSettingsRequest（不再携带任何计费字段——
 *    原 billingByQty/pricePerQtyDay 等四个幽灵字段从未被后端消费，§2.6 活缺陷已随 P4 W4 收口）
 *  - 计费规则（P4 W1 新 API，独立于本页通用保存）：
 *      GET  /tenant/billing-rules → {current, history}（版本链留痕）
 *      POST /tenant/billing-rules（R20 变更须 confirmed=true，缺失 40003 → 弹二次确认后重发；
 *      首存免确认、自当日生效、不补历史 · PRD 13-p4 §1.3/§1.4）
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
  Stamp,
  Box,
  Van,
  RefreshLeft,
  Checked,
  AlarmClock,
  Remove,
  Plus,
  Top,
  Bottom,
  Delete,
  StarFilled,
} from '@element-plus/icons-vue'
import { AppTopbar } from '@cangchu/ui-shared'
import type {
  TenantSettings,
  UpdateTenantSettingsRequest,
  CapacityVisibility,
  CapacityPrecision,
  PhotoMode,
  BillingRules,
  Wholesaler,
} from '@cangchu/api-types'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { tenantApi, storefrontApi } from '@/api/tenant'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { batchApi } from '@/api/batch'
import { accountApi } from '@/api/account'
import { billingRuleApi } from '@/api/billing'
import { ApiError } from '@/api/http'
import { billingDimLabel } from '@/utils/billing'

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
const activeMenu = ref('/ta/settings')

interface MenuItem {
  key: string
  label: string
  icon: typeof Setting
}

const menus: MenuItem[] = [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  { key: '/ta/wholesaler-applications', label: '入驻审批', icon: Stamp },
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
  if (key === '/ta/settings') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees' ||
    key === '/ta/wholesaler-applications' ||
    key === '/ta/approvals' ||
    key === '/ta/inbound' ||
    key === '/ta/outbound' ||
    key === '/ta/returns' ||
    key === '/ta/stocktake' ||
    key === '/ta/batches' ||
    key === '/ta/bills' ||
    key === '/ta/clearance' ||
    key === '/ta/messages'
  ) {
    router.push(key)
    return
  }
  // 其它菜单页尚未实现，保持占位
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 表单数据 ============
const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)

// 完整快照（用于检测计费规则是否变更 → 是否需要二次确认）
const original = ref<TenantSettings | null>(null)
// 只读元信息
const meta = reactive({
  tenantId: '',
  tenantSimpleCode: '',
  accuracySource: '' as string,
})

const form = reactive({
  storeName: '',
  // 地址
  addressText: '',
  lng: undefined as number | undefined,
  lat: undefined as number | undefined,
  // 5 开关
  batchEnabled: false,
  locationEnabled: false, // C2 货位功能（无副作用，随本页保存生效）
  photoMode: 'OPTIONAL' as PhotoMode,
  capacityVisibility: 'WA_ONLY' as CapacityVisibility,
  capacityPrecision: 'EXACT' as CapacityPrecision,
  expiryThresholdDays: 30 as number | undefined,
  // 容量
  totalQty: undefined as number | undefined,
  totalPallet: undefined as number | undefined,
})

// ============ 校验规则 ============
const rules = computed<FormRules>(() => ({
  storeName: [
    { required: true, message: '请输入店铺名称', trigger: 'blur' },
    { max: 30, message: '店铺名称最多 30 字', trigger: 'blur' },
  ],
  expiryThresholdDays: [
    {
      validator: (_r, v, cb) => {
        if (form.batchEnabled) {
          if (v === undefined || v === null || Number(v) < 1) {
            cb(new Error('临期阈值需 ≥1 天'))
            return
          }
        }
        cb()
      },
      trigger: 'change',
    },
  ],
}))

// ============ 回填 ============
const applyToForm = (s: TenantSettings) => {
  meta.tenantId = String(s.tenantId)
  meta.tenantSimpleCode = s.tenantSimpleCode
  meta.accuracySource = s.address?.accuracySource ?? ''

  form.storeName = s.storeName ?? ''
  form.addressText = s.address?.text ?? ''
  form.lng = s.address?.lng
  form.lat = s.address?.lat

  form.batchEnabled = !!s.batchEnabled
  form.locationEnabled = s.locationEnabled === 1
  form.photoMode = s.photoMode ?? 'OPTIONAL'
  form.capacityVisibility = s.capacityVisibility ?? 'WA_ONLY'
  form.capacityPrecision = s.capacityPrecision ?? 'EXACT'

  form.expiryThresholdDays = s.expiryThresholdDays ?? 30

  form.totalQty = s.totalQty
  form.totalPallet = s.totalPallet
}

const fetchSettings = async () => {
  loading.value = true
  try {
    const data = await tenantApi.getSettings()
    original.value = data
    applyToForm(data)
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 提交 ============
const buildPayload = (): UpdateTenantSettingsRequest => ({
  storeName: form.storeName,
  address:
    form.addressText || form.lng !== undefined || form.lat !== undefined
      ? {
          text: form.addressText,
          lng: form.lng ?? 0,
          lat: form.lat ?? 0,
          accuracySource: (original.value?.address?.accuracySource ?? 'MAP_CLICK'),
        }
      : null,
  // batchEnabled 不随通用设置提交（D-13：通用接口传非 0 → 50360、传 0 会绕过冻结逻辑；
  // 开关一律走专用端点 POST /tenant/settings/batch-toggle，见 onBatchToggleBeforeChange）
  batchEnabled: undefined,
  photoMode: form.photoMode,
  capacityVisibility: form.capacityVisibility,
  capacityPrecision: form.capacityPrecision,
  expiryThresholdDays: form.batchEnabled ? form.expiryThresholdDays : undefined,
  // C2 货位功能：无副作用（纯显隐+必填校验），随通用设置提交（对照 batchEnabled 禁改走专用端点）
  locationEnabled: form.locationEnabled ? 1 : 0,
  totalQty: form.totalQty,
  totalPallet: form.totalPallet,
})

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await tenantApi.updateSettings(buildPayload())
    ElMessage.success('店铺设置已保存')
    await fetchSettings() // 重新拉取，刷新 original 快照
  } catch {
    // 全局 toast 已提示
  } finally {
    saving.value = false
  }
}

// ============ 计费规则（P4 W1 API · 独立于本页通用保存） ============
// 首存免二次确认、当日生效、不补历史（PRD 13-p4 §1.3）；
// 变更走 R20 二次确认（40003 凭据缺失 → 弹窗确认后重发 confirmed=true）。
const ruleLoading = ref(false)
const ruleSaving = ref(false)
const rules$ = ref<BillingRules | null>(null)

const ruleForm = reactive({
  byQty: false,
  priceQty: undefined as number | undefined,
  byPallet: false,
  pricePallet: undefined as number | undefined,
})

const applyRuleToForm = () => {
  const cur = rules$.value?.current ?? null
  ruleForm.byQty = !!cur?.qtyEnabled
  ruleForm.priceQty = cur?.pricePerQtyDay ?? undefined
  ruleForm.byPallet = !!cur?.palletEnabled
  ruleForm.pricePallet = cur?.pricePerPalletDay ?? undefined
}

const fetchRules = async () => {
  ruleLoading.value = true
  try {
    const data = await billingRuleApi.getRules()
    // 无规则空态后端省略 current 字段 → 归一为 null
    rules$.value = { current: data?.current ?? null, history: data?.history ?? [] }
    applyRuleToForm()
  } catch {
    // 全局 toast 已提示
  } finally {
    ruleLoading.value = false
  }
}

/** 首次设置（从未保存过规则）？→ 空态引导横幅 + 保存免二次确认 */
const isFirstRule = computed(() => !rules$.value?.current)

/** 对外展示计费维度（只读镜像三值映射，BOTH=并存） */
const billingDimText = computed(() => {
  const cur = rules$.value?.current
  if (!cur) return billingDimLabel(original.value?.billingDim ?? null)
  return billingDimLabel(cur.qtyEnabled && cur.palletEnabled ? 'BOTH' : cur.qtyEnabled ? 'QTY' : 'PALLET')
})

/** 两维均未勾选 → 保存置灰（PRD §1.2） */
const canSaveRule = computed(() => ruleForm.byQty || ruleForm.byPallet)

/** 与当前规则完全相同 → 不产生新版本、不发通知（PRD §1.4） */
const ruleUnchanged = computed(() => {
  const cur = rules$.value?.current
  if (!cur) return false
  const num = (v: number | null | undefined) => (v === null || v === undefined ? null : Number(v))
  return (
    cur.qtyEnabled === ruleForm.byQty &&
    cur.palletEnabled === ruleForm.byPallet &&
    (!ruleForm.byQty || num(cur.pricePerQtyDay) === num(ruleForm.priceQty)) &&
    (!ruleForm.byPallet || num(cur.pricePerPalletDay) === num(ruleForm.pricePallet))
  )
})

/** R20 二次确认弹窗（线框 13-p4 §8.2：变更内容 + 影响四条） */
const confirmRuleChange = async (): Promise<boolean> => {
  const cur = rules$.value?.current
  const fmt = (v: number | null | undefined) => (v === null || v === undefined ? '未启用' : `${v} `)
  const qtyOld = cur?.qtyEnabled ? fmt(cur.pricePerQtyDay) : '未启用'
  const qtyNew = ruleForm.byQty ? `${ruleForm.priceQty} 元/件·天` : '未启用'
  const palletOld = cur?.palletEnabled ? fmt(cur.pricePerPalletDay) : '未启用'
  const palletNew = ruleForm.byPallet ? `${ruleForm.pricePallet} 元/托盘·天` : '未启用'
  try {
    await ElMessageBox.confirm(
      `变更自今日起生效，内容如下：\n` +
        `件·天计费：${qtyOld}→ ${qtyNew}\n` +
        `托盘·天计费：${palletOld}→ ${palletNew}\n\n` +
        `影响：\n` +
        `✓ 历史已出账账单不重算\n` +
        `✓ 本月账单按变更日分段计费，变更当日起即按新规则\n` +
        `✓ 全部在驻批发商将收到通知\n` +
        `✓ 今日多次变更以最后一次为准`,
      '确认变更计费规则',
      {
        confirmButtonText: '确认变更',
        cancelButtonText: '取消',
        type: 'warning',
        customClass: 'billing-rule-confirm',
      },
    )
    return true
  } catch {
    return false
  }
}

const postRule = (confirmed?: boolean) =>
  billingRuleApi.saveRule({
    billingByQty: ruleForm.byQty,
    pricePerQtyDay: ruleForm.byQty ? ruleForm.priceQty : undefined,
    billingByPallet: ruleForm.byPallet,
    pricePerPalletDay: ruleForm.byPallet ? ruleForm.pricePallet : undefined,
    confirmed,
  })

const onSaveRule = async () => {
  if (!canSaveRule.value) {
    ElMessage.warning('请至少启用一种计费维度并填写单价')
    return
  }
  if ((ruleForm.byQty && (ruleForm.priceQty === undefined || ruleForm.priceQty === null)) ||
      (ruleForm.byPallet && (ruleForm.pricePallet === undefined || ruleForm.pricePallet === null))) {
    ElMessage.warning('启用的计费维度必须填写单价（≥0）')
    return
  }
  if (ruleUnchanged.value) {
    ElMessage.info('规则未发生变化')
    return
  }

  // 已有规则 → R20 变更二次确认；首存免确认（无「变更」即无回溯保护问题）
  let confirmed: boolean | undefined
  if (!isFirstRule.value) {
    if (!(await confirmRuleChange())) return
    confirmed = true
  }

  ruleSaving.value = true
  try {
    await postRule(confirmed)
    ElMessage.success(isFirstRule.value ? '计费规则已保存，自今日起生效' : '计费规则已更新，今日起按新规则计费')
    await fetchRules()
  } catch (e) {
    // 40003 = 后端要求变更凭据（并发下他人先改等场景）→ 弹二次确认后重发 confirmed=true
    if (e instanceof ApiError && e.code === ErrorCode.VALIDATION_REQUIRED) {
      if (await confirmRuleChange()) {
        try {
          await postRule(true)
          ElMessage.success('计费规则已更新，今日起按新规则计费')
          await fetchRules()
        } catch {
          /* 全局 toast 已提示 */
        }
      }
    }
    // 50379 等其余错误：全局 toast 已按 messages-zh 提示
  } finally {
    ruleSaving.value = false
  }
}

const onResetRule = () => {
  applyRuleToForm()
  ElMessage.info('已还原为当前生效的计费规则')
}

// ============ 批次开关（专用端点，P3b T4-FE） ============
// D-13/T4-W1：通用设置接口禁改 batchEnabled（50360），开关一律走
// POST /tenant/settings/batch-toggle（真实翻转须 confirmed=true；24h ≤2 次 → 50361）。
const batchToggling = ref(false)

/** el-switch before-change：确认弹窗 + 专用端点成功后才翻转显示态 */
const onBatchToggleBeforeChange = async (): Promise<boolean> => {
  const enable = !form.batchEnabled
  const tip = enable
    ? '开启后将为现有库存生成默认批次占位（每个在库大于 0 的商品一条，保质期空，可在「批次临期」页补录到效期），新入库需登记批次号与保质期。'
    : '关闭后批次登记簿将冻结（全部未终结批次标「已冻结」），临期预警停用，入库批次字段隐藏；已生成的清库单继续走完；再次启用将生成新的默认批次，不复活已冻结批次。'
  try {
    await ElMessageBox.confirm(
      `${tip}\n\n批次开关 24 小时内最多操作 2 次，确认${enable ? '开启' : '关闭'}？`,
      enable ? '开启批次管理' : '关闭批次管理',
      { confirmButtonText: enable ? '确认开启' : '确认关闭', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return false
  }
  batchToggling.value = true
  try {
    const res = await batchApi.toggle({ enable, confirmed: true })
    if (enable) {
      ElMessage.success(
        `批次管理已开启，已生成 ${res.defaultBatchCount} 条默认批次吸收现有库存（可在「批次临期」页补录到效期）`,
      )
    } else {
      ElMessage.success(`批次管理已关闭，已冻结 ${res.closedBatchCount} 条批次`)
    }
    if (original.value) original.value.batchEnabled = res.batchEnabled === 1
    return true
  } catch {
    // 50361 「24 小时内最多操作 2 次」等：全局 toast 已按 messages-zh 提示
    return false
  } finally {
    batchToggling.value = false
  }
}

const onReset = () => {
  if (original.value) applyToForm(original.value)
  formRef.value?.clearValidate()
  ElMessage.info('已还原为上次保存的设置')
}

// ============ 撮合运营（P5-A W4 · 18-p5-design §4.3 · GET/PUT /tenant/storefront/featured） ============
// 主推商品 ≤20、置顶批发商 ≤5；覆盖写幂等；保存后店铺页（RT 进店）按序展示「主推」/「置顶」。
// 后端接口由 backend-dev 交付；若未就绪，全局 toast 会提示，页面保留空态可重试（已按契约编写）。
const featuredLoading = ref(false)
const featuredSaving = ref(false)
const featuredDirty = ref(false)

interface FeaturedEntry {
  id: string
  name: string
  sub: string
}

/** 已选主推商品（有序） */
const mainSkus = ref<FeaturedEntry[]>([])
/** 已选置顶批发商（有序） */
const pinnedWas = ref<FeaturedEntry[]>([])
/** 候选池：本店在售 SKU */
const skuCandidates = ref<FeaturedEntry[]>([])
/** 候选池：本店批发商 */
const waCandidates = ref<FeaturedEntry[]>([])
/** 添加用多选临时值（change 后立即清空，避免重复注入） */
const pendingMainAdd = ref<string[]>([])
const pendingWaAdd = ref<string[]>([])

const mainSelectedIds = computed(() => new Set(mainSkus.value.map((s) => s.id)))
const waSelectedIds = computed(() => new Set(pinnedWas.value.map((w) => w.id)))

const fetchFeatured = async () => {
  featuredLoading.value = true
  try {
    const cfg = await storefrontApi.getFeatured()
    const was = await wholesalerApi.list().catch(() => [] as Wholesaler[])

    // 候选池：批发商（仅 ACTIVE 在驻）
    waCandidates.value = (was ?? [])
      .filter((w) => w.status !== 'OFFLINE')
      .map((w) => ({ id: String(w.id), name: w.name, sub: `商户 · ${w.status === 'ACTIVE' ? '正常' : '其他'}` }))

    // 候选池：本店在售 SKU（逐商户拉取合并，仅 listed）
    const skuList: FeaturedEntry[] = []
    for (const w of was ?? []) {
      if (w.status === 'OFFLINE') continue
      try {
        const skus = await skuApi.list(String(w.id))
        for (const s of skus ?? []) {
          if (!s.listed) continue
          skuList.push({ id: String(s.id), name: s.name, sub: `${w.name}${s.spec ? ` · ${s.spec}` : ''}` })
        }
      } catch {
        // 单个商户拉取失败不阻断整体候选
      }
    }
    skuCandidates.value = skuList

    // 回显（后端已有序；不在候选池的 id 保留占位，避免下架后配置丢失）
    mainSkus.value = (cfg?.mainSkuIds ?? []).map((id) => {
      const hit = skuList.find((s) => s.id === String(id))
      return hit ?? { id: String(id), name: `商品 ${String(id).slice(-6)}`, sub: '已下架或不在候选' }
    })
    pinnedWas.value = (cfg?.pinWaIds ?? []).map((id) => {
      const hit = waCandidates.value.find((w) => w.id === String(id))
      return hit ?? { id: String(id), name: `商户 ${String(id).slice(-6)}`, sub: '已退出或不在候选' }
    })
    featuredDirty.value = false
  } catch {
    // 后端接口未就绪/网络异常：全局 toast 已提示；保留空态可重试
    featuredDirty.value = false
  } finally {
    featuredLoading.value = false
  }
}

const onAddMainSkus = (ids: string[]) => {
  const fresh = ids.filter((id) => !mainSelectedIds.value.has(id))
  for (const id of fresh) {
    if (mainSkus.value.length >= 20) break
    const hit = skuCandidates.value.find((s) => s.id === id)
    if (hit) mainSkus.value.push({ ...hit })
  }
  pendingMainAdd.value = []
  featuredDirty.value = true
}

const onAddWa = (ids: string[]) => {
  const fresh = ids.filter((id) => !waSelectedIds.value.has(id))
  for (const id of fresh) {
    if (pinnedWas.value.length >= 5) break
    const hit = waCandidates.value.find((w) => w.id === id)
    if (hit) pinnedWas.value.push({ ...hit })
  }
  pendingWaAdd.value = []
  featuredDirty.value = true
}

const moveItem = <T>(arr: T[], from: number, dir: -1 | 1) => {
  const to = from + dir
  if (to < 0 || to >= arr.length) return
  const [item] = arr.splice(from, 1)
  arr.splice(to, 0, item)
  featuredDirty.value = true
}

const removeMainSku = (id: string) => {
  mainSkus.value = mainSkus.value.filter((s) => s.id !== id)
  featuredDirty.value = true
}

const removePinnedWa = (id: string) => {
  pinnedWas.value = pinnedWas.value.filter((w) => w.id !== id)
  featuredDirty.value = true
}

const saveFeatured = async () => {
  if (mainSkus.value.length > 20) {
    ElMessage.warning('主推商品最多 20 件')
    return
  }
  if (pinnedWas.value.length > 5) {
    ElMessage.warning('置顶批发商最多 5 家')
    return
  }
  featuredSaving.value = true
  try {
    await storefrontApi.updateFeatured({
      mainSkuIds: mainSkus.value.map((s) => s.id),
      pinWaIds: pinnedWas.value.map((w) => w.id),
    })
    ElMessage.success('撮合运营配置已保存，店铺页将按新顺序展示')
    featuredDirty.value = false
    await fetchFeatured() // 刷新快照（后端去重/钳制后的权威序）
  } catch {
    // 50711-50714 等：全局 toast 已提示
  } finally {
    featuredSaving.value = false
  }
}

const resetFeatured = () => {
  void fetchFeatured()
  ElMessage.info('已还原为当前生效的撮合配置')
}

onMounted(() => {
  void fetchSettings()
  void fetchRules()
  void fetchFeatured()
})
</script>

<template>
  <div class="ta-shell" v-loading="loading">
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
            <h2 class="page-head__title">店铺设置</h2>
            <p class="page-head__sub">
              店铺码：<code>{{ meta.tenantSimpleCode || '—' }}</code>
            </p>
          </div>
        </header>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="settings-form"
          @submit.prevent="onSubmit"
        >
          <!-- 基础资料 -->
          <section class="card">
            <h3 class="card__title">基础资料</h3>
            <el-form-item label="店铺名称" prop="storeName">
              <el-input v-model="form.storeName" placeholder="如：XX 海鲜库" maxlength="30" show-word-limit />
            </el-form-item>
          </section>

          <!-- 仓库地址（含坐标） -->
          <section class="card">
            <h3 class="card__title">仓库地址（含坐标）</h3>
            <el-form-item label="详细地址">
              <el-input
                v-model="form.addressText"
                type="textarea"
                :rows="2"
                placeholder="如：北京市顺义区 XX 路 XX 号"
                maxlength="120"
              />
            </el-form-item>
            <div class="coord-row">
              <el-form-item label="经度 (lng)" class="coord-item">
                <el-input-number
                  v-model="form.lng"
                  :precision="6"
                  :step="0.0001"
                  :controls="false"
                  placeholder="116.6531"
                  class="full-w"
                />
              </el-form-item>
              <el-form-item label="纬度 (lat)" class="coord-item">
                <el-input-number
                  v-model="form.lat"
                  :precision="6"
                  :step="0.0001"
                  :controls="false"
                  placeholder="40.1287"
                  class="full-w"
                />
              </el-form-item>
            </div>
            <p class="hint">
              定位来源：{{ meta.accuracySource || '未定位' }}
              ｜ 地图选点 SDK（高德/腾讯）待后续 Agent 接入，当前可手填坐标
            </p>
          </section>

          <!-- 运营开关（5 开关） -->
          <section class="card">
            <h3 class="card__title">运营开关</h3>

            <!-- 1. 批次管理 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">批次管理</span>
                <span class="switch-row__desc">
                  关闭时入库不录批次/保质期，临期预警停用；开关立即生效（不随本页保存），24 小时内最多操作 2 次
                  <el-button
                    v-if="form.batchEnabled"
                    text
                    type="primary"
                    size="small"
                    data-test="goto-batches"
                    @click="handleMenuSelect('/ta/batches')"
                  >
                    批次临期页 →
                  </el-button>
                </span>
              </div>
              <el-switch
                v-model="form.batchEnabled"
                :loading="batchToggling"
                :before-change="onBatchToggleBeforeChange"
                active-text="启用"
                inactive-text="关闭"
                inline-prompt
                data-test="batch-toggle-switch"
              />
            </div>

            <el-divider class="thin" />

            <!-- 1b. 货位管理（P5-D C2 US-WK-05：无副作用，随本页保存生效） -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">货位管理</span>
                <span class="switch-row__desc">
                  开启后入库登记须填放置货位、出库登记须填拣出货位，批次登记簿可移库并留变更记录；关闭=出入库不录货位，存量货位保留
                </span>
              </div>
              <el-switch
                v-model="form.locationEnabled"
                active-text="启用"
                inactive-text="关闭"
                inline-prompt
                data-test="location-toggle-switch"
              />
            </div>

            <el-divider class="thin" />

            <!-- 2. 入库拍照 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">入库拍照</span>
                <span class="switch-row__desc">控制入库录单是否需要现场照片，及 SKU 展示图来源</span>
              </div>
              <el-radio-group v-model="form.photoMode">
                <el-radio-button value="OFF">关闭</el-radio-button>
                <el-radio-button value="OPTIONAL">选填</el-radio-button>
                <el-radio-button value="REQUIRED">必填</el-radio-button>
              </el-radio-group>
            </div>

            <el-divider class="thin" />

            <!-- 3. 容量公示 -->
            <div class="switch-block">
              <div class="switch-row__label">
                <span class="switch-row__name">容量公示</span>
                <span class="switch-row__desc">决定本店在终端买家/批发商列表中的可见性与精度（§8）</span>
              </div>
              <div class="sub-field">
                <span class="sub-field__label">可见范围</span>
                <el-radio-group v-model="form.capacityVisibility">
                  <el-radio value="PRIVATE">不公开</el-radio>
                  <el-radio value="WA_ONLY">已入驻批发商</el-radio>
                  <el-radio value="PUBLIC">全平台</el-radio>
                </el-radio-group>
              </div>
              <div class="sub-field">
                <span class="sub-field__label">精度档位</span>
                <el-radio-group v-model="form.capacityPrecision">
                  <el-radio value="EXACT">精确数</el-radio>
                  <el-radio value="TIER">模糊档（&lt;30% / 30-70% / &gt;70%）</el-radio>
                </el-radio-group>
              </div>
            </div>

            <el-divider class="thin" />

            <!-- 4. 计费维度（只读摘要 · 设置移至下方「计费规则」卡，PRD 13-p4 §1.5） -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">计费维度</span>
                <span class="switch-row__desc" data-test="billing-dim-summary">
                  当前对外展示：{{ billingDimText }}｜单价与开关请在下方「计费规则」区块设置
                </span>
              </div>
            </div>

            <el-divider class="thin" />

            <!-- 5. 临期阈值 -->
            <div class="switch-row">
              <div class="switch-row__label">
                <span class="switch-row__name">临期阈值</span>
                <span class="switch-row__desc">仅批次启用时生效（§9.1，默认 30 天）</span>
              </div>
              <el-form-item prop="expiryThresholdDays" class="inline-item">
                <el-input-number
                  v-model="form.expiryThresholdDays"
                  :min="1"
                  :max="365"
                  :step="1"
                  :disabled="!form.batchEnabled"
                />
                <span class="unit">天</span>
              </el-form-item>
            </div>
          </section>

          <!-- 计费规则（P4 · 独立数据源 billing-rules，独立保存） -->
          <section class="card" v-loading="ruleLoading" data-test="billing-rule-card">
            <div class="rule-head">
              <h3 class="card__title">计费规则</h3>
              <span v-if="rules$?.current" class="rule-head__version" data-test="billing-rule-version">
                当前版本：第 {{ rules$.current.version }} 版
              </span>
            </div>

            <!-- 首次空态引导横幅（PRD §8.1 逐字） -->
            <el-alert
              v-if="isFirstRule && !ruleLoading"
              type="warning"
              :closable="false"
              show-icon
              class="rule-alert"
              data-test="billing-rule-first-banner"
              title="尚未设置计费规则，保存后系统才会开始累计仓储费并按月生成账单"
            />

            <div class="rule-form">
              <div class="bill-line">
                <el-checkbox v-model="ruleForm.byQty" data-test="rule-qty-toggle">按件计费</el-checkbox>
                <el-input-number
                  v-model="ruleForm.priceQty"
                  :min="0"
                  :precision="4"
                  :step="0.01"
                  :disabled="!ruleForm.byQty"
                  placeholder="0.0500"
                  class="bill-line__price"
                  data-test="rule-qty-price"
                />
                <span class="unit">元/件·天</span>
              </div>

              <div class="bill-line">
                <el-checkbox v-model="ruleForm.byPallet" data-test="rule-pallet-toggle">按托盘计费</el-checkbox>
                <el-input-number
                  v-model="ruleForm.pricePallet"
                  :min="0"
                  :precision="4"
                  :step="0.01"
                  :disabled="!ruleForm.byPallet"
                  placeholder="1.2000"
                  class="bill-line__price"
                  data-test="rule-pallet-price"
                />
                <span class="unit">元/托盘·天</span>
              </div>

              <!-- 托盘基线提示（D-P4-5 逐字） -->
              <p v-if="ruleForm.byPallet" class="hint" data-test="rule-pallet-hint">
                ※ 启用托盘·天计费前，建议先完成一次盘点校准在库托盘数，确保计费基数准确
              </p>
            </div>

            <!-- 生效口径固定文案（PRD §1.3 逐字采用） -->
            <p class="rule-note" data-test="billing-rule-effective-note">
              ※ 计费规则自保存当日起生效。生效日之前的在库时间不计费、不补出历史账单；生效当月的账单只包含生效日起的费用。
            </p>

            <!-- 历史版本留痕（对账核查「这一段按哪版价算」） -->
            <template v-if="(rules$?.history?.length ?? 0) > 0 || rules$?.current">
              <h4 class="rule-history__title">历史版本</h4>
              <el-table
                :data="[...(rules$?.current ? [rules$.current] : []), ...(rules$?.history ?? [])]"
                size="small"
                class="rule-history__table"
                data-test="billing-rule-history"
              >
                <el-table-column label="版本" width="80">
                  <template #default="{ row }">第{{ row.version }}版</template>
                </el-table-column>
                <el-table-column label="生效起止" min-width="180">
                  <template #default="{ row }">
                    {{ row.effectiveFrom }} ~ {{ row.effectiveTo ?? '至今' }}
                  </template>
                </el-table-column>
                <el-table-column label="件·天单价" min-width="100">
                  <template #default="{ row }">
                    {{ row.qtyEnabled ? row.pricePerQtyDay : '未启用' }}
                  </template>
                </el-table-column>
                <el-table-column label="托盘·天单价" min-width="100">
                  <template #default="{ row }">
                    {{ row.palletEnabled ? row.pricePerPalletDay : '未启用' }}
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <div class="rule-actions">
              <el-button :disabled="ruleSaving" @click="onResetRule">取消</el-button>
              <el-button
                type="primary"
                :loading="ruleSaving"
                :disabled="!canSaveRule"
                data-test="billing-rule-save"
                @click="onSaveRule"
              >
                保存
              </el-button>
            </div>
            <p v-if="!canSaveRule" class="hint">※ 请至少启用一种计费维度并填写单价</p>
          </section>

          <!-- 撮合运营（P5-A W4 · 独立数据源 GET/PUT /tenant/storefront/featured，独立保存） -->
          <section class="card" v-loading="featuredLoading" data-test="featured-card">
            <div class="featured-head">
              <h3 class="card__title">撮合运营</h3>
              <span class="featured-head__desc">
                主推商品 ≤20、置顶批发商 ≤5，可排序；保存后店铺页按此顺序展示「主推」「置顶」标识
              </span>
            </div>

            <!-- 主推商品 -->
            <div class="featured-block">
              <div class="featured-block__head">
                <span class="featured-block__name">
                  <el-icon class="featured-block__icon"><StarFilled /></el-icon>
                  主推商品
                </span>
                <el-select
                  v-model="pendingMainAdd"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  :max-collapse-tags="2"
                  class="featured-block__select"
                  :disabled="mainSkus.length >= 20"
                  :placeholder="mainSkus.length >= 20 ? '已达上限（20 件）' : '搜索并选择要主推的商品（可多选）'"
                  @change="onAddMainSkus"
                >
                  <el-option
                    v-for="s in skuCandidates"
                    :key="s.id"
                    :value="s.id"
                    :label="`${s.name}（${s.sub}）`"
                    :disabled="mainSelectedIds.has(s.id)"
                  />
                </el-select>
                <span class="featured-block__count">{{ mainSkus.length }}/20</span>
              </div>

              <ul v-if="mainSkus.length > 0" class="featured-list">
                <li v-for="(s, idx) in mainSkus" :key="s.id" class="featured-item">
                  <span class="featured-item__idx">{{ idx + 1 }}</span>
                  <div class="featured-item__main">
                    <span class="featured-item__name">{{ s.name }}</span>
                    <span class="featured-item__sub">{{ s.sub }}</span>
                  </div>
                  <div class="featured-item__ops">
                    <el-button size="small" :icon="Top" :disabled="idx === 0" @click="moveItem(mainSkus, idx, -1)" />
                    <el-button size="small" :icon="Bottom" :disabled="idx === mainSkus.length - 1" @click="moveItem(mainSkus, idx, 1)" />
                    <el-button size="small" type="danger" :icon="Delete" @click="removeMainSku(s.id)" />
                  </div>
                </li>
              </ul>
              <p v-else class="featured-block__empty">尚未设置主推商品</p>
            </div>

            <el-divider class="thin" />

            <!-- 置顶批发商 -->
            <div class="featured-block">
              <div class="featured-block__head">
                <span class="featured-block__name">
                  <el-icon class="featured-block__icon"><StarFilled /></el-icon>
                  置顶批发商
                </span>
                <el-select
                  v-model="pendingWaAdd"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  :max-collapse-tags="2"
                  class="featured-block__select"
                  :disabled="pinnedWas.length >= 5"
                  :placeholder="pinnedWas.length >= 5 ? '已达上限（5 家）' : '搜索并选择要置顶的批发商（可多选）'"
                  @change="onAddWa"
                >
                  <el-option
                    v-for="w in waCandidates"
                    :key="w.id"
                    :value="w.id"
                    :label="`${w.name}（${w.sub}）`"
                    :disabled="waSelectedIds.has(w.id)"
                  />
                </el-select>
                <span class="featured-block__count">{{ pinnedWas.length }}/5</span>
              </div>

              <ul v-if="pinnedWas.length > 0" class="featured-list">
                <li v-for="(w, idx) in pinnedWas" :key="w.id" class="featured-item">
                  <span class="featured-item__idx">{{ idx + 1 }}</span>
                  <div class="featured-item__main">
                    <span class="featured-item__name">{{ w.name }}</span>
                    <span class="featured-item__sub">{{ w.sub }}</span>
                  </div>
                  <div class="featured-item__ops">
                    <el-button size="small" :icon="Top" :disabled="idx === 0" @click="moveItem(pinnedWas, idx, -1)" />
                    <el-button size="small" :icon="Bottom" :disabled="idx === pinnedWas.length - 1" @click="moveItem(pinnedWas, idx, 1)" />
                    <el-button size="small" type="danger" :icon="Delete" @click="removePinnedWa(w.id)" />
                  </div>
                </li>
              </ul>
              <p v-else class="featured-block__empty">尚未设置置顶批发商</p>
            </div>

            <div class="featured-actions">
              <el-button :disabled="!featuredDirty" @click="resetFeatured">取消</el-button>
              <el-button
                type="primary"
                :loading="featuredSaving"
                :disabled="!featuredDirty"
                data-test="featured-save"
                @click="saveFeatured"
              >
                保存
              </el-button>
            </div>
          </section>

          <!-- 容量（可选） -->
          <section class="card">
            <h3 class="card__title">额定容量（可选）</h3>
            <div class="coord-row">
              <el-form-item label="总件数容量" class="coord-item">
                <el-input-number v-model="form.totalQty" :min="0" :step="100" :controls="false" class="full-w" placeholder="如 100000" />
              </el-form-item>
              <el-form-item label="总托盘容量" class="coord-item">
                <el-input-number v-model="form.totalPallet" :min="0" :step="10" :controls="false" class="full-w" placeholder="如 2000" />
              </el-form-item>
            </div>
          </section>

          <!-- 操作栏 -->
          <div class="action-bar">
            <el-button @click="onReset">取消</el-button>
            <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
          </div>
        </el-form>
      </main>
    </div>
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
  max-width: 920px;
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
.page-head__sub code {
  background: var(--color-bg-3);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-family: var(--font-family-mono);
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0 0 var(--space-4);
}

/* 坐标 / 容量两列 */
.coord-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}
.coord-item {
  margin-bottom: 0;
}
.full-w {
  width: 100%;
}
.full-w :deep(.el-input-number) {
  width: 100%;
}

.hint {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}

/* ===== 开关行 ===== */
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-2) 0;
}
.switch-block {
  padding: var(--space-2) 0;
}
.switch-row__label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.switch-row__name {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.switch-row__desc {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
}

.thin {
  margin: var(--space-3) 0;
}

/* 子字段（容量公示） */
.sub-field {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}
.sub-field__label {
  width: 84px;
  flex-shrink: 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-body);
}

/* 计费规则卡 */
.rule-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}
.rule-head__version {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.rule-alert {
  margin-bottom: var(--space-3);
}
.rule-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rule-note {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
  line-height: 1.6;
}
.rule-history__title {
  margin: var(--space-4) 0 var(--space-2);
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.rule-history__table {
  width: 100%;
}
.rule-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

/* 计费行 */
.bill-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
  margin-bottom: 0;
}
.bill-line :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.bill-line__price {
  margin-bottom: 0;
}
.unit {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.inline-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 0;
}
.inline-item :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

/* 撮合运营卡 */
.featured-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.featured-head__desc {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.featured-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.featured-block__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.featured-block__name {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
  min-width: 96px;
}
.featured-block__icon {
  color: #d4a017;
}
.featured-block__select {
  width: 320px;
  max-width: 100%;
}
.featured-block__count {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  font-variant-numeric: tabular-nums;
}
.featured-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.featured-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--color-bg-2);
  border-radius: var(--radius-base);
}
.featured-item__idx {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-brand-accent);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.featured-item__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.featured-item__name {
  color: var(--color-fg-1);
  font-weight: var(--font-weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.featured-item__sub {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.featured-item__ops {
  display: flex;
  gap: var(--space-1);
  flex-shrink: 0;
}
.featured-block__empty {
  margin: 0;
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
  padding: var(--space-2) 0;
}
.featured-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

/* ===== 操作栏 ===== */
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-2) 0 var(--space-6);
  position: sticky;
  bottom: 0;
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .ta-main {
    max-width: none;
  }
}
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .coord-row {
    grid-template-columns: 1fr;
  }
  .switch-row {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
  .sub-field {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
}
</style>
