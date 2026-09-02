<script setup lang="ts">
/**
 * WA 出库单（P3 FE-W2 · 12 §3.1/§3.4 + 09 PRD §3）
 *
 * 契约（权威：WholesalerOutboundController，据实查证）：
 *  - GET  /wholesaler/outbound-requests?status=&source=&page=&size=（MpPage<OutboundRequest>）
 *  - POST /wholesaler/outbound-requests            手动出库申请（提交即扣，不足 50251 整体回滚）
 *  - POST /{id}/withdraw   R4 撤回（待受理直撤回补 / 已打印置 flag 待仓库二次确认 / 已出库 50335）
 *  - POST /{id}/complain   30 天客诉（仅仓库代建且已出库；超窗 50339）→ 客诉处理中 + KS- 仲裁单
 *
 * 产品口径：
 *  - 「已确认（代建）」队列 = source=WK_CREATED 过滤；
 *  - 撤回分状态两路：待受理立即撤销并回补；已打印需仓库现场核对纸质单后确认（回显「撤回待仓库确认」）；
 *  - 客诉仅判责不动库存/账单，结论为线下赔偿依据（D43）；一单仅可诉一次。
 *
 * 视觉：沿用 wa/Inbound.vue 顶栏 + 左侧菜单 shell + el-table 风格。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ChatDotRound,
  Document,
  Refresh,
  Shop,
  User,
  Box,
  Van,
  RefreshLeft,
  Plus,
  Warning as WarningIcon,
  AlarmClock,
  Coin,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type {
  OutboundRequest,
  OutboundStatus,
  OutboundSource,
  OutboundSubmitRequest,
  OutboundComplainRequest,
  Sku,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { waOutboundApi } from '@/api/outbound'
import { skuApi } from '@/api/sku'
import { accountApi } from '@/api/account'
import NotificationBell from '@/components/NotificationBell.vue'
import OutboundComplainDialog from './OutboundComplainDialog.vue'

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
const activeMenu = ref('/wa/outbound')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  // P5-D C3 客户跟进（US-WE-04 · WE/WA 可见：WA 全量，WE 本商户直连）
  { key: '/wa/customers', label: '客户跟进', icon: ChatDotRound },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/outbound', label: '出库单', icon: Van },
  { key: '/wa/returns', label: '退货', icon: RefreshLeft },
  { key: '/wa/batches', label: '批次临期', icon: AlarmClock },
  // P4：账单仅批发商管理员可见（员工整域无入口，05 §5.4）
  ...(auth.roles?.some((r) => r.role === 'WA')
    ? [{ key: '/wa/bills', label: '账单', icon: Coin }]
    : []),
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/outbound') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

/** 本账号绑定的商户（WA 角色条目；WE 不开放出库操作——后端 requireWaRole） */
const myWholesalerId = computed(() => {
  const entry = auth.roles?.find((r) => r.role === 'WA' && r.wholesalerId)
  return entry?.wholesalerId ? String(entry.wholesalerId) : ''
})

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
  WA_SUBMIT: '我方提交',
  WK_CREATED: '仓库代建',
}
const sourceLabel = (s: string | null) =>
  s ? (SOURCE_LABEL[s as OutboundSource] ?? s) : '—'

const STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部状态' },
  ...(Object.keys(STATUS_META) as OutboundStatus[]).map((k) => ({
    value: k,
    label: STATUS_META[k].label,
  })),
]

const SOURCE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部来源' },
  ...(Object.keys(SOURCE_LABEL) as OutboundSource[]).map((k) => ({
    value: k,
    label: SOURCE_LABEL[k],
  })),
]

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

// ============ 列表（状态/来源筛选） ============
const loading = ref(false)
const rows = ref<OutboundRequest[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
const statusFilter = ref('')
const sourceFilter = ref('')

const fetchList = async () => {
  loading.value = true
  try {
    const data = await waOutboundApi.list({
      status: statusFilter.value || undefined,
      source: sourceFilter.value || undefined,
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

const onFilterChange = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

// ============ 手动出库申请 ============
const submitVisible = ref(false)
const submitting = ref(false)
const submitFormRef = ref<FormInstance>()

const submitForm = reactive({
  skuId: '' as string,
  qty: undefined as number | undefined,
  palletQty: undefined as number | undefined,
})

const submitRules: FormRules = {
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

// SKU 选择器（本商户 SKU，开放实体集 → EntityPickerDialog）
const skus = ref<Sku[]>([])

const fetchSkus = async () => {
  if (!myWholesalerId.value) {
    skus.value = []
    return
  }
  try {
    skus.value = await skuApi.list(myWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  }
}

const skuPickerColumns: EntityPickerColumn<Sku>[] = [
  { label: '商品名称', prop: 'name', minWidth: 160 },
  { label: '规格', formatter: (s) => s.spec || '—', minWidth: 100 },
  { label: '状态', formatter: (s) => (s.listed ? '在售' : '已下架'), width: 90 },
]

const fetchSkuPage = makeClientPickerFetch<Sku>(
  () => skus.value,
  (s, kw) => s.name.toLowerCase().includes(kw) || (s.spec ?? '').toLowerCase().includes(kw),
)

const selectedSkuLabel = computed(() => {
  const s = skus.value.find((x) => String(x.id) === submitForm.skuId)
  if (!s) return ''
  return s.spec ? `${s.name}（${s.spec}）` : s.name
})

const openSubmitDialog = () => {
  if (!myWholesalerId.value) {
    ElMessage.warning('当前账号未绑定商户，无法发起出库申请')
    return
  }
  submitForm.skuId = ''
  submitForm.qty = undefined
  submitForm.palletQty = undefined
  submitVisible.value = true
  submitFormRef.value?.clearValidate()
  if (skus.value.length === 0) void fetchSkus()
}

const onSubmit = async () => {
  if (!submitFormRef.value) return
  const valid = await submitFormRef.value.validate().catch(() => false)
  if (!valid) return
  try {
    await ElMessageBox.confirm(
      `确认提交出库申请（${selectedSkuLabel.value || 'SKU'} × ${submitForm.qty} 件）？提交后立即扣减库存，仓库打印纸质单后按单出库。`,
      '提交出库申请',
      { confirmButtonText: '确认提交', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  const payload: OutboundSubmitRequest = {
    wholesalerId: myWholesalerId.value,
    skuId: submitForm.skuId,
    qty: Number(submitForm.qty),
  }
  if (submitForm.palletQty !== undefined && submitForm.palletQty !== null) {
    payload.palletQty = Number(submitForm.palletQty)
  }
  submitting.value = true
  try {
    const created = await waOutboundApi.submit(payload)
    submitVisible.value = false
    ElMessage.success(`出库申请已提交（单号 ${created.docNo}），库存已扣减`)
    statusFilter.value = ''
    sourceFilter.value = ''
    page.value = 1
    await fetchList()
  } catch {
    // 50251 库存不足等由全局拦截器 toast
  } finally {
    submitting.value = false
  }
}

// ============ R4 撤回（分状态两路） ============
const withdrawingId = ref('')

const onWithdraw = async (row: OutboundRequest) => {
  const printed = row.status === 'PRINTED'
  try {
    await ElMessageBox.confirm(
      printed
        ? `出库单 ${row.docNo} 已打印，撤回申请需仓库现场核对纸质单后确认，确认前单据继续履约。是否申请撤回？`
        : `撤回出库单 ${row.docNo}（${row.qty} 件）？撤回后单据撤销，库存立即回补。`,
      printed ? '申请撤回' : '撤回出库单',
      {
        confirmButtonText: printed ? '申请撤回' : '确认撤回',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  withdrawingId.value = String(row.id)
  try {
    const updated = await waOutboundApi.withdraw(String(row.id))
    if (updated.status === 'WITHDRAWN') {
      ElMessage.success(`出库单 ${updated.docNo} 已撤回，库存已回补`)
    } else {
      ElMessage.success(`撤回申请已提交，等待仓库确认（出库单 ${updated.docNo}）`)
    }
    await fetchList()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_OUTBOUND_NOT_WITHDRAWABLE ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      await fetchList()
    }
  } finally {
    withdrawingId.value = ''
  }
}

// ============ 30 天客诉（仅仓库代建且已出库） ============
const COMPLAINT_WINDOW_MS = 30 * 24 * 3600 * 1000

/** 是否在 30 天客诉窗口内（completedAt 锚点，缺失回退 createdAt——对齐后端口径） */
const inComplaintWindow = (row: OutboundRequest): boolean => {
  const anchor = row.completedAt ?? row.createdAt
  if (!anchor) return false
  const t = new Date(String(anchor)).getTime()
  return Number.isFinite(t) && Date.now() - t < COMPLAINT_WINDOW_MS
}

const complainable = (row: OutboundRequest): boolean =>
  row.status === 'COMPLETED' && row.source === 'WK_CREATED' && inComplaintWindow(row)

/** 已出库的代建单但超窗（展示「已过客诉期」灰字） */
const complaintExpired = (row: OutboundRequest): boolean =>
  row.status === 'COMPLETED' && row.source === 'WK_CREATED' && !inComplaintWindow(row)

const complainVisible = ref(false)
const complainTarget = ref<OutboundRequest | null>(null)
const complainSubmitting = ref(false)

const onComplain = (row: OutboundRequest) => {
  complainTarget.value = row
  complainVisible.value = true
}

const onComplainSubmit = async (payload: OutboundComplainRequest) => {
  const row = complainTarget.value
  if (!row) return
  complainSubmitting.value = true
  try {
    const updated = await waOutboundApi.complain(String(row.id), payload)
    complainVisible.value = false
    ElMessage.success(
      `客诉已提交（出库单 ${updated.docNo}），等待平台运维仲裁；结论将通过站内信通知`,
    )
    await fetchList()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_OUTBOUND_COMPLAINT_WINDOW_CLOSED ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      complainVisible.value = false
      await fetchList()
    }
  } finally {
    complainSubmitting.value = false
  }
}

onMounted(() => {
  void fetchList()
  void fetchSkus()
})
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏 -->
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
            <h2 class="page-head__title">出库单</h2>
            <p class="page-head__sub">
              询价确认、手动申请与仓库代建的出库单在此跟踪；代建出库可在出库后 30 天内发起客诉
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              type="primary"
              :icon="Plus"
              data-test="submit-outbound-btn"
              @click="openSubmitDialog"
            >
              手动出库申请
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <!-- 筛选（有限枚举 → el-select，UX 规范） -->
          <div class="toolbar">
            <el-select
              v-model="statusFilter"
              class="toolbar__select"
              placeholder="全部状态"
              data-test="status-filter"
              @change="onFilterChange"
            >
              <el-option
                v-for="opt in STATUS_OPTIONS"
                :key="opt.value"
                :value="opt.value"
                :label="opt.label"
              />
            </el-select>
            <el-select
              v-model="sourceFilter"
              class="toolbar__select"
              placeholder="全部来源"
              data-test="source-filter"
              @change="onFilterChange"
            >
              <el-option
                v-for="opt in SOURCE_OPTIONS"
                :key="opt.value"
                :value="opt.value"
                :label="opt.label"
              />
            </el-select>
          </div>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="outbound-table"
            data-test="wa-outbound-table"
            empty-text="暂无出库单"
          >
            <el-table-column prop="docNo" label="出库单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
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
            <el-table-column label="状态" width="180">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
                <el-tag
                  v-if="row.status === 'PRINTED' && row.withdrawRequested === 1"
                  type="warning"
                  size="small"
                  effect="plain"
                  class="flag-tag"
                  data-test="withdraw-pending-tag"
                >
                  撤回待仓库确认
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="出库时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.completedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <!-- 待受理：直撤 -->
                <el-button
                  v-if="row.status === 'PENDING_ACCEPT'"
                  type="warning"
                  size="small"
                  plain
                  :loading="withdrawingId === String(row.id)"
                  data-test="withdraw-btn"
                  @click="onWithdraw(row as OutboundRequest)"
                >
                  撤回
                </el-button>

                <!-- 已打印：申请撤回（已申请则只读回显） -->
                <template v-else-if="row.status === 'PRINTED'">
                  <el-button
                    v-if="row.withdrawRequested !== 1"
                    type="warning"
                    size="small"
                    plain
                    :loading="withdrawingId === String(row.id)"
                    data-test="request-withdraw-btn"
                    @click="onWithdraw(row as OutboundRequest)"
                  >
                    申请撤回
                  </el-button>
                  <span v-else class="cell-muted">等待仓库确认</span>
                </template>

                <!-- 已出库代建单：30 天窗口内可客诉 -->
                <el-button
                  v-else-if="complainable(row as OutboundRequest)"
                  type="danger"
                  size="small"
                  plain
                  data-test="complain-btn"
                  @click="onComplain(row as OutboundRequest)"
                >
                  客诉
                </el-button>
                <span
                  v-else-if="complaintExpired(row as OutboundRequest)"
                  class="cell-muted"
                  data-test="complaint-expired"
                >
                  已过客诉期
                </span>

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

    <!-- 手动出库申请弹窗 -->
    <el-dialog
      v-model="submitVisible"
      title="手动出库申请"
      width="520px"
      :close-on-click-modal="false"
      data-test="submit-dialog"
    >
      <el-alert type="warning" :closable="false" class="submit-alert">
        提交后立即扣减库存（不足将整体驳回）；仓库打印纸质单后按单作业，打印前可随时撤回。
      </el-alert>
      <el-form
        ref="submitFormRef"
        :model="submitForm"
        :rules="submitRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="商品 SKU" prop="skuId">
          <EntityPickerDialog
            v-model="submitForm.skuId"
            title="选择商品"
            placeholder="点击选择商品"
            :columns="skuPickerColumns"
            :fetch="fetchSkuPage"
            :selected-label="selectedSkuLabel"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="出库数量" prop="qty">
          <el-input-number
            v-model="submitForm.qty"
            :min="1"
            :precision="0"
            :step="1"
            :controls="false"
            placeholder="必填，大于 0 的整数"
            class="full-width"
            data-test="submit-qty"
          />
        </el-form-item>
        <el-form-item label="托盘数（可选）" prop="palletQty">
          <el-input-number
            v-model="submitForm.palletQty"
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
        <el-button :disabled="submitting" @click="submitVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          data-test="submit-confirm"
          @click="onSubmit"
        >
          提交申请
        </el-button>
      </template>
    </el-dialog>

    <!-- 客诉表单弹窗（复用 FE-W1 附件+理由弹窗模式） -->
    <OutboundComplainDialog
      v-model="complainVisible"
      :row="complainTarget"
      :submitting="complainSubmitting"
      @submit="onComplainSubmit"
    />
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

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.toolbar {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}
.toolbar__select {
  width: 160px;
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
.flag-tag {
  margin-left: var(--space-1);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

.submit-alert {
  margin-bottom: var(--space-4);
}
.full-width {
  width: 100%;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0;
  }
  .toolbar__select {
    width: 100%;
  }
}
</style>
