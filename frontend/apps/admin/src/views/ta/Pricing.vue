<script setup lang="ts">
/**
 * TA 价格管理（PC）— P2 客户专属价 / 批量调价 / 调价历史
 *
 * 来源：
 *  - 契约：backend CustomerPriceController / batch-price-update / price-change-logs（Waves 1&2）
 *      GET    /tenant/customer-prices?wholesalerId=
 *      POST   /tenant/customer-prices
 *      PATCH  /tenant/customer-prices/{id}
 *      DELETE /tenant/customer-prices/{id}
 *      POST   /tenant/skus/batch-price-update
 *      POST   /tenant/customer-prices/batch-update
 *      GET    /tenant/price-change-logs?wholesalerId=&changeType=
 *  - 视觉：沿用 ta/Skus.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格
 *
 * 范围（slice 4a）：仅本页三个板块，不含结算弹窗 / RT 命中价展示（slice 4b）。
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
  PriceTag,
  Plus,
  Stamp,
} from '@element-plus/icons-vue'
import { AppTopbar, MoneyDisplay, StatusBadge } from '@cangchu/ui-shared'
import type {
  Wholesaler,
  Sku,
  CustomerPriceVo,
  PriceChangeLogVo,
  PriceStatus,
  AdjustMode,
  ChangeType,
  PriceTargetField,
  SetCustomerPriceRequest,
  UpdateCustomerPriceRequest,
  BatchPublicPriceRequest,
  BatchCustomerPriceRequest,
  BatchPriceResultVo,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { pricingApi } from '@/api/pricing'
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
const activeMenu = ref('/ta/pricing')

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
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '审批中心', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/pricing') {
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
    key === '/ta/approvals'
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
  await Promise.all([fetchSkus(), fetchCustomerPrices(), fetchChangeLogs()])
}

// ============ SKU（供 skuName 映射 + 选择器） ============
const skus = ref<Sku[]>([])

const fetchSkus = async () => {
  if (!selectedWholesalerId.value) {
    skus.value = []
    return
  }
  try {
    skus.value = await skuApi.list(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  }
}

const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.name
  return map
})

const skuNameOf = (skuId: string): string => skuNameMap.value[String(skuId)] ?? `SKU#${skuId}`

// ============ 通用格式化 ============
const statusVariant = (s: PriceStatus): 'success' | 'default' | 'warning' => {
  if (s === 'ACTIVE') return 'success'
  if (s === 'EXPIRED') return 'warning'
  return 'default'
}
const statusText = (s: PriceStatus): string =>
  s === 'ACTIVE' ? '生效中' : s === 'EXPIRED' ? '已过期' : '已停用'

const sourceText = (s: CustomerPriceVo['source']): string =>
  s === 'from_inquiry' ? '询价生成' : '手工设置'

const formatDateTime = (v?: string | null): string => {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ============ (a) 客户专属价 ============
const cpLoading = ref(false)
const customerPrices = ref<CustomerPriceVo[]>([])

const fetchCustomerPrices = async () => {
  if (!selectedWholesalerId.value) {
    customerPrices.value = []
    return
  }
  cpLoading.value = true
  try {
    customerPrices.value = await pricingApi.listCustomerPrices(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  } finally {
    cpLoading.value = false
  }
}

// ---- 设置 / 编辑对话框 ----
const cpDialogVisible = ref(false)
const cpSubmitting = ref(false)
const cpFormRef = ref<FormInstance>()
const cpEditingId = ref<string>('') // 空=新建，否则编辑

const cpDialogTitle = computed(() => (cpEditingId.value ? '编辑专属价' : '设置专属价'))

const cpForm = reactive({
  skuId: '' as string,
  rtPhone: '' as string,
  unitPrice: undefined as number | undefined,
  expireAt: '' as string,
  status: 'ACTIVE' as PriceStatus,
})

const cpRules: FormRules = {
  skuId: [{ required: true, message: '请选择 SKU', trigger: 'change' }],
  rtPhone: [
    { required: true, message: '请输入 RT 手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '请输入 11 位手机号', trigger: 'blur' },
  ],
  unitPrice: [
    { required: true, message: '请输入专属单价', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || v === ('' as unknown)) cb(new Error('请输入专属单价'))
        else if (Number(v) <= 0) cb(new Error('单价必须大于 0'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
}

const resetCpForm = () => {
  cpForm.skuId = ''
  cpForm.rtPhone = ''
  cpForm.unitPrice = undefined
  cpForm.expireAt = ''
  cpForm.status = 'ACTIVE'
}

const openCpCreate = () => {
  cpEditingId.value = ''
  resetCpForm()
  cpDialogVisible.value = true
  cpFormRef.value?.clearValidate()
}

const openCpEdit = (row: CustomerPriceVo) => {
  cpEditingId.value = String(row.id)
  cpForm.skuId = String(row.skuId)
  cpForm.rtPhone = row.rtPhone
  cpForm.unitPrice = Number(row.unitPrice)
  cpForm.expireAt = row.expireAt ?? ''
  cpForm.status = row.status
  cpDialogVisible.value = true
  cpFormRef.value?.clearValidate()
}

const onCpSubmit = async () => {
  if (!cpFormRef.value) return
  if (!selectedWholesalerId.value) {
    ElMessage.warning('请先选择商户')
    return
  }
  const valid = await cpFormRef.value.validate().catch(() => false)
  if (!valid) return

  cpSubmitting.value = true
  try {
    if (cpEditingId.value) {
      const payload: UpdateCustomerPriceRequest = {
        unitPrice: Number(cpForm.unitPrice),
        status: cpForm.status,
      }
      payload.expireAt = cpForm.expireAt || undefined
      await pricingApi.updateCustomerPrice(cpEditingId.value, payload)
      ElMessage.success('专属价已更新')
    } else {
      const payload: SetCustomerPriceRequest = {
        wholesalerId: selectedWholesalerId.value,
        skuId: cpForm.skuId,
        rtPhone: cpForm.rtPhone.trim(),
        unitPrice: Number(cpForm.unitPrice),
      }
      if (cpForm.expireAt) payload.expireAt = cpForm.expireAt
      await pricingApi.setCustomerPrice(payload)
      ElMessage.success('专属价已设置')
    }
    cpDialogVisible.value = false
    await Promise.all([fetchCustomerPrices(), fetchChangeLogs()])
  } catch {
    // 全局 toast 已提示（VALIDATION/STATE/BUSINESS 均已 toast）
  } finally {
    cpSubmitting.value = false
  }
}

const revokingId = ref<string>('')

const onCpRevoke = async (row: CustomerPriceVo) => {
  try {
    await ElMessageBox.confirm(
      `确认撤销 ${row.rtPhone} 对「${skuNameOf(String(row.skuId))}」的专属价？撤销后状态将变为「已停用」。`,
      '撤销确认',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  revokingId.value = String(row.id)
  try {
    await pricingApi.revokeCustomerPrice(String(row.id))
    ElMessage.success('已撤销')
    await Promise.all([fetchCustomerPrices(), fetchChangeLogs()])
  } catch {
    // 全局 toast 已提示
  } finally {
    revokingId.value = ''
  }
}

// ============ (b) 批量调价 ============
type BatchScope = 'public' | 'customer'

const batchSubmitting = ref(false)
const batchResult = ref<BatchPriceResultVo | null>(null)

const batchForm = reactive({
  scope: 'public' as BatchScope,
  skuIds: [] as string[], // public
  cpIds: [] as string[], // customer
  adjustMode: 'PCT_UP' as AdjustMode,
  value: undefined as number | undefined,
  targetField: 'unitPrice' as PriceTargetField,
  expireAt: '' as string,
})

interface AdjustOption {
  value: AdjustMode
  label: string
}

const publicAdjustOptions: AdjustOption[] = [
  { value: 'PCT_UP', label: '按百分比上调（%）' },
  { value: 'PCT_DOWN', label: '按百分比下调（%）' },
  { value: 'SET_VALUE', label: '设为定值（元）' },
  { value: 'DELTA', label: '增减定额（元，可负）' },
]

const customerAdjustOptions: AdjustOption[] = [
  ...publicAdjustOptions,
  { value: 'DISABLE', label: '批量停用' },
  { value: 'SET_EXPIRE', label: '批量设置过期时间' },
]

const batchAdjustOptions = computed(() =>
  batchForm.scope === 'public' ? publicAdjustOptions : customerAdjustOptions,
)

const batchNeedsValue = computed(
  () =>
    batchForm.adjustMode === 'PCT_UP' ||
    batchForm.adjustMode === 'PCT_DOWN' ||
    batchForm.adjustMode === 'SET_VALUE' ||
    batchForm.adjustMode === 'DELTA',
)
const batchNeedsExpire = computed(() => batchForm.adjustMode === 'SET_EXPIRE')

const onBatchScopeChange = () => {
  // 切换范围时纠正无效 adjustMode（public 不支持 DISABLE/SET_EXPIRE）
  if (
    batchForm.scope === 'public' &&
    (batchForm.adjustMode === 'DISABLE' || batchForm.adjustMode === 'SET_EXPIRE')
  ) {
    batchForm.adjustMode = 'PCT_UP'
  }
  batchResult.value = null
}

const onBatchSubmit = async () => {
  if (!selectedWholesalerId.value) {
    ElMessage.warning('请先选择商户')
    return
  }
  if (batchNeedsValue.value && (batchForm.value === undefined || batchForm.value === null)) {
    ElMessage.warning('请输入调整数值')
    return
  }
  if (batchNeedsExpire.value && !batchForm.expireAt) {
    ElMessage.warning('请选择过期时间')
    return
  }

  batchSubmitting.value = true
  batchResult.value = null
  try {
    if (batchForm.scope === 'public') {
      if (batchForm.skuIds.length === 0) {
        ElMessage.warning('请至少选择 1 个 SKU')
        return
      }
      const payload: BatchPublicPriceRequest = {
        wholesalerId: selectedWholesalerId.value,
        skuIds: batchForm.skuIds,
        adjustMode: batchForm.adjustMode as BatchPublicPriceRequest['adjustMode'],
        value: Number(batchForm.value),
        targetField: batchForm.targetField,
      }
      batchResult.value = await pricingApi.batchPublicPrice(payload)
    } else {
      if (batchForm.cpIds.length === 0) {
        ElMessage.warning('请至少选择 1 条专属价')
        return
      }
      const payload: BatchCustomerPriceRequest = {
        wholesalerId: selectedWholesalerId.value,
        ids: batchForm.cpIds,
        adjustMode: batchForm.adjustMode,
      }
      if (batchNeedsValue.value) payload.value = Number(batchForm.value)
      if (batchNeedsExpire.value) payload.expireAt = batchForm.expireAt
      batchResult.value = await pricingApi.batchCustomerPrice(payload)
    }
    ElMessage.success(`批量调价完成，影响 ${batchResult.value.affectedCount} 条`)
    // 刷新受影响的数据 + 历史
    await Promise.all([fetchSkus(), fetchCustomerPrices(), fetchChangeLogs()])
  } catch {
    // 全局 toast 已提示
  } finally {
    batchSubmitting.value = false
  }
}

// ============ (c) 调价历史 ============
const logLoading = ref(false)
const changeLogs = ref<PriceChangeLogVo[]>([])
const changeTypeFilter = ref<ChangeType | ''>('')

const fetchChangeLogs = async () => {
  if (!selectedWholesalerId.value) {
    changeLogs.value = []
    return
  }
  logLoading.value = true
  try {
    changeLogs.value = await pricingApi.listChangeLogs(
      selectedWholesalerId.value,
      changeTypeFilter.value || undefined,
    )
  } catch {
    // 全局 toast 已提示
  } finally {
    logLoading.value = false
  }
}

const adjustModeText = (m: AdjustMode): string => {
  const map: Record<AdjustMode, string> = {
    PCT_UP: '百分比上调',
    PCT_DOWN: '百分比下调',
    SET_VALUE: '设为定值',
    DELTA: '增减定额',
    DISABLE: '批量停用',
    SET_EXPIRE: '设置过期',
  }
  return map[m] ?? m
}

const changeTypeText = (t: ChangeType): string =>
  t === 'PUBLIC_PRICE' ? '公开价' : '客户专属价'

onMounted(fetchWholesalers)
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
            <h2 class="page-head__title">价格管理</h2>
            <p class="page-head__sub">维护客户专属价、批量调价，并回溯调价历史</p>
          </div>
        </header>

        <section class="card">
          <!-- 商户选择器 -->
          <div class="toolbar">
            <span class="toolbar__label">商户</span>
            <el-select
              v-model="selectedWholesalerId"
              placeholder="请选择商户"
              :loading="wholesalerLoading"
              class="toolbar__select"
              filterable
              @change="onWholesalerChange"
            >
              <el-option
                v-for="w in wholesalers"
                :key="String(w.id)"
                :label="w.name"
                :value="String(w.id)"
              />
            </el-select>
            <span v-if="!wholesalerLoading && wholesalers.length === 0" class="toolbar__empty">
              当前店铺暂无商户，请先在「入驻商户」创建
            </span>
          </div>

          <el-tabs class="pricing-tabs">
            <!-- (a) 客户专属价 -->
            <el-tab-pane label="客户专属价">
              <div class="tab-head">
                <span class="tab-head__hint">为指定 RT 手机号设置某 SKU 的专属单价</span>
                <el-button
                  type="primary"
                  :icon="Plus"
                  :disabled="!selectedWholesalerId"
                  @click="openCpCreate"
                >
                  设置专属价
                </el-button>
              </div>

              <el-table
                v-loading="cpLoading"
                :data="customerPrices"
                stripe
                class="pricing-table"
                empty-text="该商户暂无客户专属价，点击「设置专属价」开始"
              >
                <el-table-column label="RT 手机号" min-width="140">
                  <template #default="{ row }">
                    <span class="cell-name">{{ row.rtPhone }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="SKU" min-width="180">
                  <template #default="{ row }">
                    <span>{{ skuNameOf(String(row.skuId)) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="专属单价" width="140" align="right">
                  <template #default="{ row }">
                    <MoneyDisplay :value="row.unitPrice" />
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">
                    <StatusBadge
                      :variant="statusVariant(row.status)"
                      :text="statusText(row.status)"
                      :dot="true"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="来源" width="110">
                  <template #default="{ row }">
                    <span class="cell-muted">{{ sourceText(row.source) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="过期时间" min-width="150">
                  <template #default="{ row }">
                    <span class="cell-muted">{{ formatDateTime(row.expireAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="170" fixed="right">
                  <template #default="{ row }">
                    <el-button text type="primary" @click="openCpEdit(row as CustomerPriceVo)">编辑</el-button>
                    <el-button
                      text
                      type="danger"
                      :loading="revokingId === String(row.id)"
                      :disabled="row.status === 'DISABLED'"
                      @click="onCpRevoke(row as CustomerPriceVo)"
                    >
                      撤销
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- (b) 批量调价 -->
            <el-tab-pane label="批量调价">
              <el-form label-position="top" class="batch-form">
                <el-form-item label="调价范围">
                  <el-radio-group v-model="batchForm.scope" @change="onBatchScopeChange">
                    <el-radio-button value="public">公开价（SKU）</el-radio-button>
                    <el-radio-button value="customer">客户专属价</el-radio-button>
                  </el-radio-group>
                </el-form-item>

                <el-form-item
                  v-if="batchForm.scope === 'public'"
                  label="选择 SKU（多选，1..200）"
                >
                  <el-select
                    v-model="batchForm.skuIds"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="请选择要调价的 SKU"
                    class="full-width"
                  >
                    <el-option
                      v-for="s in skus"
                      :key="String(s.id)"
                      :label="s.name"
                      :value="String(s.id)"
                    />
                  </el-select>
                </el-form-item>

                <el-form-item v-else label="选择专属价（多选，1..500）">
                  <el-select
                    v-model="batchForm.cpIds"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="请选择要调整的专属价"
                    class="full-width"
                  >
                    <el-option
                      v-for="cp in customerPrices"
                      :key="String(cp.id)"
                      :label="`${cp.rtPhone} · ${skuNameOf(String(cp.skuId))}`"
                      :value="String(cp.id)"
                    />
                  </el-select>
                </el-form-item>

                <el-form-item label="调整方式">
                  <el-select v-model="batchForm.adjustMode" class="mid-width">
                    <el-option
                      v-for="opt in batchAdjustOptions"
                      :key="opt.value"
                      :label="opt.label"
                      :value="opt.value"
                    />
                  </el-select>
                </el-form-item>

                <el-form-item
                  v-if="batchForm.scope === 'public'"
                  label="调整字段"
                >
                  <el-radio-group v-model="batchForm.targetField">
                    <el-radio value="unitPrice">单价</el-radio>
                    <el-radio value="moqPrice">起批价</el-radio>
                  </el-radio-group>
                </el-form-item>

                <el-form-item
                  v-if="batchNeedsValue"
                  :label="
                    batchForm.adjustMode === 'PCT_UP' || batchForm.adjustMode === 'PCT_DOWN'
                      ? '百分比（%，如 10 表示 10%）'
                      : '金额（元，DELTA 可为负）'
                  "
                >
                  <el-input-number
                    v-model="batchForm.value"
                    :precision="2"
                    :step="1"
                    :controls="false"
                    placeholder="请输入调整数值"
                    class="mid-width"
                  />
                </el-form-item>

                <el-form-item v-if="batchNeedsExpire" label="过期时间">
                  <el-date-picker
                    v-model="batchForm.expireAt"
                    type="datetime"
                    placeholder="选择过期时间"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    class="mid-width"
                  />
                </el-form-item>

                <el-form-item>
                  <el-button
                    type="primary"
                    :loading="batchSubmitting"
                    :disabled="!selectedWholesalerId"
                    @click="onBatchSubmit"
                  >
                    执行批量调价
                  </el-button>
                </el-form-item>
              </el-form>

              <!-- 结果 -->
              <el-alert
                v-if="batchResult"
                class="batch-result"
                :type="batchResult.rejectedCount > 0 ? 'warning' : 'success'"
                :closable="false"
                show-icon
              >
                <template #title>
                  批次 {{ batchResult.batchNo }}：成功
                  {{ batchResult.affectedCount }} 条，失败 {{ batchResult.rejectedCount }} 条
                </template>
                <div v-if="batchResult.rejectedCount > 0" class="batch-result__rejected">
                  被拒 ID：{{ batchResult.rejectedIds.join('、') }}
                </div>
              </el-alert>
            </el-tab-pane>

            <!-- (c) 调价历史 -->
            <el-tab-pane label="调价历史">
              <div class="tab-head">
                <span class="tab-head__hint">按批次回溯公开价 / 专属价调整记录</span>
                <el-select
                  v-model="changeTypeFilter"
                  placeholder="全部类型"
                  clearable
                  class="log-filter"
                  @change="fetchChangeLogs"
                >
                  <el-option label="公开价" value="PUBLIC_PRICE" />
                  <el-option label="客户专属价" value="CUSTOMER_PRICE" />
                </el-select>
              </div>

              <el-table
                v-loading="logLoading"
                :data="changeLogs"
                stripe
                class="pricing-table"
                empty-text="暂无调价历史"
              >
                <el-table-column label="批次号" min-width="160">
                  <template #default="{ row }">
                    <span class="cell-muted">{{ row.batchNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="120">
                  <template #default="{ row }">
                    <StatusBadge
                      :variant="row.changeType === 'PUBLIC_PRICE' ? 'progress' : 'success'"
                      :text="changeTypeText(row.changeType)"
                      :dot="true"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="调整方式" width="130">
                  <template #default="{ row }">
                    <span>{{ adjustModeText(row.adjustMode) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="影响条数" width="100" align="right">
                  <template #default="{ row }">
                    <span class="cell-name">{{ row.affectedCount }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="目标" min-width="180">
                  <template #default="{ row }">
                    <span class="cell-muted">{{ row.targetSummary }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="时间" min-width="150">
                  <template #default="{ row }">
                    <span class="cell-muted">{{ formatDateTime(row.createdAt) }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </section>
      </main>
    </div>

    <!-- 设置 / 编辑专属价对话框 -->
    <el-dialog
      v-model="cpDialogVisible"
      :title="cpDialogTitle"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="cpFormRef"
        :model="cpForm"
        :rules="cpRules"
        label-position="top"
        @submit.prevent="onCpSubmit"
      >
        <el-form-item label="SKU" prop="skuId">
          <el-select
            v-model="cpForm.skuId"
            filterable
            placeholder="请选择 SKU"
            class="full-width"
            :disabled="!!cpEditingId"
          >
            <el-option
              v-for="s in skus"
              :key="String(s.id)"
              :label="s.name"
              :value="String(s.id)"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="RT 手机号" prop="rtPhone">
          <el-input
            v-model="cpForm.rtPhone"
            placeholder="11 位手机号"
            maxlength="11"
            :disabled="!!cpEditingId"
          />
        </el-form-item>

        <el-form-item label="专属单价（元）" prop="unitPrice">
          <el-input-number
            v-model="cpForm.unitPrice"
            :min="0.01"
            :precision="2"
            :step="1"
            :controls="false"
            placeholder="必填，大于 0"
            class="full-width"
          />
        </el-form-item>

        <el-form-item label="过期时间（可选，留空长期有效）">
          <el-date-picker
            v-model="cpForm.expireAt"
            type="datetime"
            placeholder="选择过期时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            class="full-width"
            clearable
          />
        </el-form-item>

        <el-form-item v-if="cpEditingId" label="状态">
          <el-radio-group v-model="cpForm.status">
            <el-radio value="ACTIVE">生效中</el-radio>
            <el-radio value="DISABLED">已停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="cpDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="cpSubmitting" @click="onCpSubmit">
          {{ cpEditingId ? '保存' : '设置' }}
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

/* ===== 卡片 + 工具栏 + 表格 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
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

.pricing-tabs {
  margin-top: var(--space-2);
}
.tab-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}
.tab-head__hint {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.log-filter {
  width: 180px;
}

.pricing-table {
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
.mid-width {
  width: 260px;
}

/* ===== 批量调价 ===== */
.batch-form {
  max-width: 560px;
}
.batch-result {
  max-width: 560px;
  margin-top: var(--space-2);
}
.batch-result__rejected {
  margin-top: var(--space-2);
  font-size: var(--font-size-caption);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .toolbar__select {
    width: 100%;
  }
  .mid-width {
    width: 100%;
  }
}
</style>
