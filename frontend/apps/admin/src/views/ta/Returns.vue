<script setup lang="ts">
/**
 * WK 退货受理 + 登记（TA 端 · P3b T3-FE · 11 PRD §2.1/§2.4-B 线框）
 *
 * 契约（权威：TenantReturnController，据实查证）：
 *  - GET  /tenant/return-requests?wholesalerId=&status=（List 非分页；
 *      status=PENDING_ACCEPT 创建升序先到先受理；PENDING_ACCEPT/ACCEPTED 行附
 *      currentStock / suggestedPalletRelease——登记页免另拉库存接口，13 v1.2 备注 10）
 *  - POST /{id}/accept    受理（CAS 锁单防撤回；仍零库存——D-7）
 *  - POST /{id}/register  登记出货（D-7 此刻扣件数+释放托盘；在库不足 50251
 *      整体回滚，单据保持已受理——页面红条提示联系商户改单）
 *
 * 产品口径（11 §2.1）：
 *  - 登记页「当前在库 N 件 ✅ / 红条不足」；释放托盘默认按比例建议值，可覆盖含 0；
 *  - 登记可按实覆写件数（备注留痕）；登记后当日停止计费、不可逆。
 *
 * 视觉：沿用 ta/Outbound.vue 顶栏（AppTopbar + WarehouseSwitcher）+ 左侧菜单 shell。
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
} from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge } from '@cangchu/ui-shared'
import type { ReturnRequest, ReturnStatus, Sku, Wholesaler } from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@cangchu/error-codes'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { tenantReturnApi } from '@/api/returns'
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
  '/ta/stocktake',
  '/ta/batches',
  '/ta/clearance',
  '/ta/approvals',
  '/ta/messages',
])

const activeMenu = ref('/ta/returns')

const handleMenuSelect = (key: string) => {
  if (key === '/ta/returns') {
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

// ============ 名称回显（VO 只回 wholesalerId/skuId） ============
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
    // 全局 toast 已提示（回退展示 id）
  }
}

/** skuId → 名称（按行内商户懒加载合并；T1-BE 已放行 WK 读 SKU 列表） */
const skuNameMap = ref<Record<string, string>>({})
const loadedSkuWholesalers = new Set<string>()

const skuLabel = (id: unknown): string => skuNameMap.value[String(id)] || String(id)

const fillSkuNames = async (list: ReturnRequest[]) => {
  const wids = [...new Set(list.map((r) => String(r.wholesalerId)))].filter(
    (w) => !loadedSkuWholesalers.has(w),
  )
  if (wids.length === 0) return
  await Promise.all(
    wids.map(async (wid) => {
      try {
        const skus: Sku[] = await skuApi.list(wid)
        loadedSkuWholesalers.add(wid)
        const next = { ...skuNameMap.value }
        for (const s of skus) next[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
        skuNameMap.value = next
      } catch {
        // 静默：回退展示 skuId
      }
    }),
  )
}

// ============ 列表（待受理升序队列 / 已受理 / 历史） ============
const TAB_PENDING = 'PENDING_ACCEPT'
const TAB_ACCEPTED = 'ACCEPTED'
const TAB_HISTORY = 'ALL'
const activeTab = ref(TAB_PENDING)

const loading = ref(false)
const rows = ref<ReturnRequest[]>([])
/** 待受理角标 */
const pendingCount = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const list = await tenantReturnApi.list({
      status: activeTab.value === TAB_HISTORY ? undefined : activeTab.value,
    })
    rows.value = list
    if (activeTab.value === TAB_PENDING) pendingCount.value = list.length
    void fillSkuNames(list)
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

/** 角标独立拉取（当前页签非待受理时保持角标准确） */
const fetchPendingCount = async () => {
  try {
    const list = await tenantReturnApi.list({ status: TAB_PENDING })
    pendingCount.value = list.length
  } catch {
    /* 静默 */
  }
}

const refreshAll = () => Promise.all([fetchList(), fetchPendingCount()])

const onTabChange = () => {
  void fetchList()
}

// ============ 受理（CAS 锁单，仍零库存） ============
const acceptingId = ref('')

const onAccept = async (row: ReturnRequest) => {
  try {
    await ElMessageBox.confirm(
      `受理退货单 ${row.docNo}（${skuLabel(row.skuId)} × ${row.qty} 件）？受理后商户不可撤回；` +
        '受理不动库存，现场出货登记时才扣减。',
      '受理退货',
      { confirmButtonText: '受理', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  acceptingId.value = String(row.id)
  try {
    await tenantReturnApi.accept(String(row.id))
    ElMessage.success(`退货单 ${row.docNo} 已受理，请现场出货后登记`)
    await refreshAll()
  } catch (e) {
    if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      // 商户并发撤回 / 他人已受理：刷新回显
      await refreshAll()
    }
  } finally {
    acceptingId.value = ''
  }
}

// ============ 登记出货（D-7 此刻扣；线框 B 右页） ============
const registerVisible = ref(false)
const registerTarget = ref<ReturnRequest | null>(null)
const registerSubmitting = ref(false)
const registerForm = ref({
  actualQty: undefined as number | undefined,
  palletRelease: undefined as number | undefined,
  remark: '',
})
/** 后端 50251 拒绝后的红条回显（在库不足） */
const registerStockError = ref('')

const openRegister = (row: ReturnRequest) => {
  registerTarget.value = row
  registerForm.value = {
    actualQty: row.qty,
    palletRelease: row.suggestedPalletRelease ?? 0,
    remark: '',
  }
  registerStockError.value = ''
  registerVisible.value = true
}

/** 在库是否足够本次实退件数（行数据 currentStock 为受理链路附带值） */
const registerStockEnough = computed(() => {
  const t = registerTarget.value
  if (!t || t.currentStock === null || t.currentStock === undefined) return true
  const need = Number(registerForm.value.actualQty ?? t.qty)
  return t.currentStock >= need
})

const canRegister = computed(
  () =>
    registerForm.value.actualQty !== undefined &&
    Number(registerForm.value.actualQty) >= 1 &&
    registerStockEnough.value,
)

const onRegisterSubmit = async () => {
  const t = registerTarget.value
  if (!t || !canRegister.value) return
  const actual = Number(registerForm.value.actualQty)
  try {
    await ElMessageBox.confirm(
      `确认现场出货并登记退货 ${actual} 件？登记后库存 −${actual}、当日停止计费，不可逆。`,
      '登记出货',
      { confirmButtonText: '登记出货', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  registerSubmitting.value = true
  try {
    const updated = await tenantReturnApi.register(String(t.id), {
      actualQty: actual,
      ...(registerForm.value.palletRelease !== undefined &&
      registerForm.value.palletRelease !== null
        ? { palletRelease: Number(registerForm.value.palletRelease) }
        : {}),
      ...(registerForm.value.remark.trim() ? { remark: registerForm.value.remark.trim() } : {}),
    })
    registerVisible.value = false
    ElMessage.success(
      `退货单 ${updated.docNo} 已登记出货（实退 ${updated.qty} 件，释放托盘 ${updated.palletRelease ?? 0}）`,
    )
    await refreshAll()
  } catch (e) {
    if (e instanceof ApiError && e.code === ErrorCode.STATE_STOCK_NOT_ENOUGH) {
      // 50251：在库不足（单据保持已受理），弹窗内红条回显并刷新在库
      registerStockError.value =
        `当前在库不足退货 ${actual} 件，请联系批发商修改退货单`
      try {
        const list = await tenantReturnApi.list({ status: TAB_ACCEPTED })
        const fresh = list.find((r) => String(r.id) === String(t.id))
        if (fresh) {
          registerTarget.value = fresh
          registerStockError.value =
            `当前在库 ${fresh.currentStock ?? 0} 件不足退货 ${actual} 件，请联系批发商修改退货单`
        }
      } catch {
        /* 静默 */
      }
    } else if (
      e instanceof ApiError &&
      (e.code === ErrorCode.STATE_DOC_CAS_CONFLICT ||
        e.code === ErrorCode.STATE_DOC_TRANSITION_INVALID)
    ) {
      registerVisible.value = false
      await refreshAll()
    }
  } finally {
    registerSubmitting.value = false
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
              v-if="m.key === '/ta/returns'"
              :count="pendingCount"
              class="menu-badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">退货受理</h2>
            <p class="page-head__sub">
              受理与撤回互斥先到先得；受理/待受理期间货仍可售、库存不变，现场出货登记时才扣件数并释放托盘（当日停止计费）
            </p>
          </div>
          <div class="page-head__actions">
            <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          </div>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="return-tabs" @tab-change="onTabChange">
            <el-tab-pane :name="TAB_PENDING">
              <template #label>
                <span class="tab-label">
                  待受理
                  <NavCountBadge :count="pendingCount" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已受理待登记" :name="TAB_ACCEPTED" />
            <el-tab-pane label="全部" :name="TAB_HISTORY" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="rows"
            row-key="id"
            class="return-table"
            data-test="wk-return-table"
            :empty-text="activeTab === TAB_PENDING ? '暂无待受理退货单' : '暂无退货单'"
          >
            <el-table-column prop="docNo" label="退货单号" min-width="190">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商户" min-width="130">
              <template #default="{ row }">{{ wholesalerLabel(row.wholesalerId) }}</template>
            </el-table-column>
            <el-table-column label="商品" min-width="150">
              <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="退货件数" width="95" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="当前在库" width="95" align="right">
              <template #default="{ row }">
                <span
                  v-if="row.currentStock !== null && row.currentStock !== undefined"
                  :class="row.currentStock >= row.qty ? 'cell-muted' : 'stock-short'"
                >
                  {{ row.currentStock }}
                </span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="释放托盘" width="90" align="right">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ row.status === 'COMPLETED' ? (row.palletRelease ?? 0) : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" width="165">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING_ACCEPT'"
                  type="primary"
                  size="small"
                  :loading="acceptingId === String(row.id)"
                  data-test="accept-btn"
                  @click="onAccept(row as ReturnRequest)"
                >
                  受理
                </el-button>
                <el-button
                  v-else-if="row.status === 'ACCEPTED'"
                  type="primary"
                  size="small"
                  data-test="register-btn"
                  @click="openRegister(row as ReturnRequest)"
                >
                  退货登记
                </el-button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 退货登记（线框 B 右页） -->
    <el-dialog
      v-model="registerVisible"
      title="退货登记"
      width="500px"
      data-test="return-register-dialog"
      :close-on-click-modal="false"
    >
      <template v-if="registerTarget">
        <div class="reg-info">
          <div class="reg-info__row">
            <span class="reg-info__label">退货单号</span>
            <span class="cell-name">{{ registerTarget.docNo }}</span>
          </div>
          <div class="reg-info__row">
            <span class="reg-info__label">批发商</span>
            <span>{{ wholesalerLabel(registerTarget.wholesalerId) }}</span>
          </div>
          <div class="reg-info__row">
            <span class="reg-info__label">商品</span>
            <span>{{ skuLabel(registerTarget.skuId) }}</span>
          </div>
          <div class="reg-info__row">
            <span class="reg-info__label">申请退货件数</span>
            <span class="cell-name">{{ registerTarget.qty }}</span>
          </div>
          <div class="reg-info__row">
            <span class="reg-info__label">当前在库</span>
            <span
              :class="registerStockEnough ? 'stock-ok' : 'stock-short'"
              data-test="register-current-stock"
            >
              {{ registerTarget.currentStock ?? '—' }} 件
              <template v-if="registerStockEnough">✅</template>
            </span>
          </div>
        </div>

        <!-- 在库不足红条（前端预检 + 后端 50251 回显同条） -->
        <el-alert
          v-if="!registerStockEnough || registerStockError"
          type="error"
          :closable="false"
          show-icon
          class="reg-alert"
          data-test="register-stock-alert"
          :title="
            registerStockError ||
            `当前在库 ${registerTarget.currentStock ?? 0} 件不足退货 ${registerForm.actualQty ?? registerTarget.qty} 件，请联系批发商修改退货单`
          "
        />

        <el-form label-width="90px" label-position="right" @submit.prevent>
          <el-form-item label="实退件数" required>
            <el-input-number
              v-model="registerForm.actualQty"
              :min="1"
              :step="1"
              step-strictly
              controls-position="right"
              data-test="register-actual-qty"
            />
            <span
              v-if="registerForm.actualQty !== undefined && registerForm.actualQty !== registerTarget.qty"
              class="reg-diff-hint"
            >
              与申请件数不同，将自动留痕
            </span>
          </el-form-item>
          <el-form-item label="释放托盘">
            <el-input-number
              v-model="registerForm.palletRelease"
              :min="0"
              :step="1"
              step-strictly
              controls-position="right"
              data-test="register-pallet-release"
            />
            <span class="reg-pallet-hint" data-test="register-pallet-hint">
              默认按比例建议 {{ registerTarget.suggestedPalletRelease ?? 0 }} 托，可改（含 0）；落库前按在库托盘封顶
            </span>
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="registerForm.remark"
              type="textarea"
              :rows="2"
              maxlength="512"
              show-word-limit
              placeholder="选填"
              data-test="register-remark"
            />
          </el-form-item>
        </el-form>

        <el-alert
          type="warning"
          :closable="false"
          class="reg-alert"
          title="登记后库存立即扣减、当日停止计费，不可逆"
        />
      </template>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!canRegister"
          :loading="registerSubmitting"
          data-test="register-submit"
          @click="onRegisterSubmit"
        >
          登记出货
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

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
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
.stock-ok {
  color: var(--color-success);
  font-weight: var(--font-weight-medium);
}
.stock-short {
  color: var(--color-danger);
  font-weight: var(--font-weight-medium);
}

/* ===== 登记弹窗 ===== */
.reg-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.reg-info__row {
  display: flex;
  gap: var(--space-3);
}
.reg-info__label {
  width: 96px;
  flex-shrink: 0;
  color: var(--color-fg-3);
}
.reg-alert {
  margin-bottom: var(--space-4);
}
.reg-diff-hint {
  margin-left: var(--space-3);
  color: var(--color-warning);
  font-size: var(--font-size-caption);
}
.reg-pallet-hint {
  margin-left: var(--space-3);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
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
