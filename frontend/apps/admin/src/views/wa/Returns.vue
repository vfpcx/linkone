<script setup lang="ts">
/**
 * WA 退货申请（P3b T3-FE · 11 PRD §2.1/§2.4-A 线框）
 *
 * 契约（权威：WholesalerReturnController，据实查证）：
 *  - POST /wholesaler/return-requests            发起（D-7：提交零库存；软校验在库，超量 50251）
 *  - GET  /wholesaler/return-requests?status=    我的退货单（MpPage；WE 只读）
 *  - POST /{id}/withdraw                         撤回（仅待受理，reason 必填；受理后 50330）
 *
 * 产品口径（11 §2.1）：
 *  - D-7 登记时扣：登记出货前货仍可售、库存不变；登记完成当日停止计费（固定政策文案）；
 *  - SKU 选择器仅列在库 >0，列表项「SKU 名（在库 N 件）」；退货件数 > 在库 → 红字实时提示；
 *  - D-9：发起/撤回仅批发商管理员，员工只读（入口隐藏）。
 *
 * 视觉：沿用 wa/Outbound.vue 顶栏 + 左侧菜单 shell + el-table 风格。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document,
  Shop,
  User,
  Box,
  Plus,
  Refresh,
  RefreshLeft,
  Van,
  Warning as WarningIcon,
} from '@element-plus/icons-vue'
import {
  AppTopbar,
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type { InventoryItem, ReturnRequest, ReturnStatus, Sku } from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import { waReturnApi } from '@/api/returns'
import { skuApi } from '@/api/sku'
import { inventoryApi } from '@/api/inventory'
import { accountApi } from '@/api/account'
import NotificationBell from '@/components/NotificationBell.vue'

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
const activeMenu = ref('/wa/returns')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/outbound', label: '出库单', icon: Van },
  { key: '/wa/returns', label: '退货', icon: RefreshLeft },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/returns') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 归属与角色（D-9：发起/撤回仅 WA，员工只读） ============

/** 本账号绑定商户（WA 或 WE 条目；后端归属校验仍是权威） */
const myWholesalerId = computed(() => {
  const entry = auth.roles?.find((r) => (r.role === 'WA' || r.role === 'WE') && r.wholesalerId)
  return entry?.wholesalerId ? String(entry.wholesalerId) : ''
})

/** 是否批发商管理员本人（仅员工身份 → 只读，入口隐藏） */
const isWaAdmin = computed(() =>
  Boolean(auth.roles?.some((r) => r.role === 'WA' && r.wholesalerId)),
)

// ============ 状态映射 ============
type BadgeType = 'warning' | 'primary' | 'success' | 'info' | 'danger'
const STATUS_META: Record<ReturnStatus, { label: string; type: BadgeType }> = {
  PENDING_ACCEPT: { label: '待受理', type: 'warning' },
  ACCEPTED: { label: '已受理', type: 'primary' },
  COMPLETED: { label: '已退货', type: 'success' },
  WITHDRAWN: { label: '已撤回', type: 'info' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as ReturnStatus] ?? { label: s, type: 'info' as BadgeType }

const formatTime = (v: string | null): string =>
  v ? String(v).replace('T', ' ').slice(0, 19) : '—'

// ============ 列表 ============
const TABS: Array<{ name: string; label: string }> = [
  { name: 'ALL', label: '全部' },
  { name: 'PENDING_ACCEPT', label: '待受理' },
  { name: 'ACCEPTED', label: '已受理' },
  { name: 'COMPLETED', label: '已退货' },
  { name: 'WITHDRAWN', label: '已撤回' },
]
const activeTab = ref('ALL')
const loading = ref(false)
const rows = ref<ReturnRequest[]>([])
const page = ref(1)
const size = 20
const total = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await waReturnApi.list({
      status: activeTab.value === 'ALL' ? undefined : activeTab.value,
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

// ============ SKU 与在库（选择器数据源 + 名称回显） ============
const skus = ref<Sku[]>([])
const inventories = ref<InventoryItem[]>([])

const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
  return map
})
const skuLabel = (id: unknown): string => skuNameMap.value[String(id)] || String(id)

/** skuId → 当前在库件数 */
const stockMap = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const inv of inventories.value) map[String(inv.skuId)] = Number(inv.qty) || 0
  return map
})

const fetchSkusAndStock = async () => {
  if (!myWholesalerId.value) return
  try {
    const [skuList, invList] = await Promise.all([
      skuApi.list(myWholesalerId.value),
      inventoryApi.query({ wholesalerId: myWholesalerId.value }),
    ])
    skus.value = skuList
    inventories.value = invList
  } catch {
    // 全局 toast 已提示（回退展示 skuId）
  }
}

// ============ 发起退货（线框 A） ============
const createVisible = ref(false)
const submitting = ref(false)
const createForm = reactive({
  skuId: '',
  qty: undefined as number | undefined,
  remark: '',
})

/** 选择器仅列在库 >0 的 SKU（05 §14b.10），行含在库件数 */
interface PickableSku extends Record<string, unknown> {
  id: string
  name: string
  spec: string
  stock: number
}
const pickableSkus = computed<PickableSku[]>(() =>
  skus.value
    .map((s) => ({
      id: String(s.id),
      name: s.name,
      spec: s.spec ?? '',
      stock: stockMap.value[String(s.id)] ?? 0,
    }))
    .filter((s) => s.stock > 0),
)

const skuPickerColumns: EntityPickerColumn<PickableSku>[] = [
  { label: '商品名称', prop: 'name', minWidth: 160 },
  { label: '规格', formatter: (s) => s.spec || '—', minWidth: 100 },
  { label: '当前在库', formatter: (s) => `${s.stock} 件`, width: 100, align: 'right' },
]

const fetchSkuPage = makeClientPickerFetch<PickableSku>(
  () => pickableSkus.value,
  (s, kw) => s.name.toLowerCase().includes(kw) || s.spec.toLowerCase().includes(kw),
)

/** 已选 SKU 回显：「名称（在库 N 件）」（线框 A 口径） */
const selectedSkuLabel = computed(() => {
  if (!createForm.skuId) return ''
  const base = skuNameMap.value[createForm.skuId] || createForm.skuId
  const stock = stockMap.value[createForm.skuId]
  return stock !== undefined ? `${base}（在库 ${stock} 件）` : base
})

/** 所选 SKU 当前在库（发起软校验红字用） */
const selectedStock = computed(() =>
  createForm.skuId ? (stockMap.value[createForm.skuId] ?? 0) : 0,
)

/** 前端实时校验：退货件数 ≤ 当前在库（后端软校验仍是权威） */
const qtyExceeds = computed(
  () =>
    Boolean(createForm.skuId) &&
    createForm.qty !== undefined &&
    Number(createForm.qty) > selectedStock.value,
)

const canSubmitCreate = computed(
  () =>
    Boolean(createForm.skuId) &&
    createForm.qty !== undefined &&
    Number(createForm.qty) >= 1 &&
    !qtyExceeds.value,
)

const openCreate = () => {
  if (!myWholesalerId.value) {
    ElMessage.warning('当前账号未绑定商户，无法发起退货申请')
    return
  }
  createForm.skuId = ''
  createForm.qty = undefined
  createForm.remark = ''
  createVisible.value = true
  void fetchSkusAndStock()
}

const onCreateSubmit = async () => {
  if (!canSubmitCreate.value) return
  submitting.value = true
  try {
    const created = await waReturnApi.create({
      skuId: createForm.skuId,
      qty: Number(createForm.qty),
      ...(createForm.remark.trim() ? { remark: createForm.remark.trim() } : {}),
    })
    createVisible.value = false
    ElMessage.success(`退货申请已提交（单号 ${created.docNo}），等待库管员受理；登记出货前货仍可售`)
    activeTab.value = 'PENDING_ACCEPT'
    page.value = 1
    await fetchList()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_STOCK_NOT_ENOUGH) {
      // 软校验拒绝：刷新在库回显（可能被并发出库扣减）
      await fetchSkusAndStock()
    }
  } finally {
    submitting.value = false
  }
}

// ============ 撤回（仅待受理，理由必填） ============
const withdrawVisible = ref(false)
const withdrawTarget = ref<ReturnRequest | null>(null)
const withdrawReason = ref('')
const withdrawSubmitting = ref(false)

const openWithdraw = (row: ReturnRequest) => {
  withdrawTarget.value = row
  withdrawReason.value = ''
  withdrawVisible.value = true
}

const onWithdrawSubmit = async () => {
  if (!withdrawTarget.value) return
  if (!withdrawReason.value.trim()) {
    ElMessage.warning('请填写撤回理由')
    return
  }
  withdrawSubmitting.value = true
  try {
    await waReturnApi.withdraw(String(withdrawTarget.value.id), {
      reason: withdrawReason.value.trim(),
    })
    withdrawVisible.value = false
    ElMessage.success(`退货单 ${withdrawTarget.value.docNo} 已撤回`)
    await fetchList()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID ||
        e.code === ErrorCode.STATE_DOC_CAS_CONFLICT)
    ) {
      // 已被受理/状态漂移：关弹窗刷新回显
      withdrawVisible.value = false
      await fetchList()
    }
  } finally {
    withdrawSubmitting.value = false
  }
}

onMounted(() => {
  void fetchList()
  void fetchSkusAndStock()
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

      <main class="wa-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">退货</h2>
            <p class="page-head__sub" data-test="return-policy-copy">
              登记出货前货仍可售、库存不变；库管员登记出货完成当日停止计费
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              v-if="isWaAdmin"
              type="primary"
              :icon="Plus"
              data-test="new-return-btn"
              @click="openCreate"
            >
              新建退货申请
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="return-tabs" @tab-change="onTabChange">
            <el-tab-pane v-for="t in TABS" :key="t.name" :label="t.label" :name="t.name" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="return-table"
            data-test="wa-return-table"
            empty-text="暂无退货单"
          >
            <el-table-column prop="docNo" label="退货单号" min-width="190">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商品" min-width="160">
              <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="退货件数" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="退货时间" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.completedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ row.status === 'WITHDRAWN' ? row.withdrawReason || '—' : row.remark || '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column v-if="isWaAdmin" label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING_ACCEPT'"
                  type="warning"
                  size="small"
                  plain
                  data-test="withdraw-btn"
                  @click="openWithdraw(row as ReturnRequest)"
                >
                  撤回
                </el-button>
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

    <!-- 新建退货申请（线框 A） -->
    <el-dialog
      v-model="createVisible"
      title="新建退货申请"
      width="480px"
      data-test="return-create-dialog"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="create-alert"
        title="登记出货前货仍可售、不影响库存；库管员登记完成当日停止计费"
      />
      <el-form label-width="90px" label-position="right" @submit.prevent>
        <el-form-item label="商品" required>
          <EntityPickerDialog
            v-model="createForm.skuId"
            title="选择退货商品（仅列在库 > 0）"
            placeholder="点击选择商品"
            :columns="skuPickerColumns"
            :fetch="fetchSkuPage"
            :selected-label="selectedSkuLabel"
            empty-text="暂无在库商品可退"
          />
        </el-form-item>
        <el-form-item label="退货件数" required>
          <div class="qty-cell">
            <el-input-number
              v-model="createForm.qty"
              :min="1"
              :step="1"
              step-strictly
              controls-position="right"
              data-test="return-qty"
            />
            <span v-if="qtyExceeds" class="qty-exceed" data-test="return-stock-warn">
              在库仅 {{ selectedStock }} 件
            </span>
          </div>
        </el-form-item>
        <el-form-item label="退货原因">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="选填，≤512 字"
            data-test="return-remark"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!canSubmitCreate"
          :loading="submitting"
          data-test="return-submit"
          @click="onCreateSubmit"
        >
          提交退货
        </el-button>
      </template>
    </el-dialog>

    <!-- 撤回（理由必填） -->
    <el-dialog
      v-model="withdrawVisible"
      title="撤回退货申请"
      width="440px"
      data-test="return-withdraw-dialog"
      :close-on-click-modal="false"
    >
      <p class="withdraw-copy">
        撤回退货单 {{ withdrawTarget?.docNo }}（{{ skuLabel(withdrawTarget?.skuId) }} ×
        {{ withdrawTarget?.qty }} 件）？仅待受理可撤回，撤回后不可恢复。
      </p>
      <el-input
        v-model="withdrawReason"
        type="textarea"
        :rows="3"
        maxlength="512"
        show-word-limit
        placeholder="撤回理由（必填）"
        data-test="return-withdraw-reason"
      />
      <template #footer>
        <el-button @click="withdrawVisible = false">取消</el-button>
        <el-button
          type="warning"
          :loading="withdrawSubmitting"
          data-test="return-withdraw-submit"
          @click="onWithdrawSubmit"
        >
          确认撤回
        </el-button>
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

.return-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

/* ===== 弹窗 ===== */
.create-alert {
  margin-bottom: var(--space-4);
}
.qty-cell {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.qty-exceed {
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}
.withdraw-copy {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
  line-height: 1.6;
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
}
</style>
