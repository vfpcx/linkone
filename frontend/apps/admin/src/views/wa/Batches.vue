<script setup lang="ts">
/**
 * WA 批次临期（P3b 收口批 L-3 · PRD 11 §3.6-C 简版）
 *
 * 契约（权威：BatchController.listForWholesaler，据实查证）：
 *  - GET /wholesaler/batches?skuId=&status=  WA/WE 只读（本账号绑定商户全量批次，
 *      到效期升序；鉴权在 Service 内按 user_roles 推导，未绑定返回空列表）
 *
 * 范围（轻量只读视角）：
 *  - 默认页签「临期/待清理」= EXPIRING ∪ PENDING_CLEARANCE（与 WK 预警列表口径一致）；
 *    「全部」页签含在库/已售罄/已清库/已冻结；
 *  - 纯只读：清库/补录/通知均为仓库侧动作（TA/WK 端），本页不提供操作列；
 *  - 展示口径（PRD §3.3）：推算剩余标注「推算值 · 截至今日 02:00」；
 *    剩余天数可负=已过期红显；默认批次未补录到效期 → 待补录。
 *
 * 视觉：沿 wa/Inbound.vue 顶栏 + 左侧菜单 shell + el-table 风格；
 * 名称映射：BatchVo 不含 skuName（13 v1.4 备注 12），以 skuApi.list(本商户) 自建。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document,
  Refresh,
  Shop,
  User,
  Box,
  Warning as WarningIcon,
  Van,
  RefreshLeft,
  AlarmClock,
} from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge } from '@cangchu/ui-shared'
import type { Batch, BatchStatus, Sku } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { batchApi } from '@/api/batch'
import { skuApi } from '@/api/sku'
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
const activeMenu = ref('/wa/batches')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/outbound', label: '出库单', icon: Van },
  { key: '/wa/returns', label: '退货', icon: RefreshLeft },
  { key: '/wa/batches', label: '批次临期', icon: AlarmClock },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/batches') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 映射（ta/Batches.vue 同款口径） ============
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

/** 剩余天数展示（可负=已过期红显；无到效期=待补录，由仓库侧补录） */
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

// ============ 本商户 SKU 名称映射 ============
/** 本账号绑定商户（WA 或 WE 条目；列表鉴权由后端按登录态推导） */
const myWholesalerId = computed(() => {
  const entry = auth.roles?.find((r) => (r.role === 'WA' || r.role === 'WE') && r.wholesalerId)
  return entry?.wholesalerId ? String(entry.wholesalerId) : ''
})

const skus = ref<Sku[]>([])
const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.spec ? `${s.name}（${s.spec}）` : s.name
  return map
})
const skuLabel = (id: unknown): string => skuNameMap.value[String(id)] || String(id)

const fetchSkus = async () => {
  if (!myWholesalerId.value) return
  try {
    skus.value = await skuApi.list(myWholesalerId.value)
  } catch {
    // 全局 toast 已提示；名称回退展示 skuId
  }
}

// ============ 批次列表（单次全量拉取 + 页签客户端过滤） ============
const TAB_ALERT = 'alert'
const TAB_ALL = 'all'
const activeTab = ref<string>(TAB_ALERT)

const loading = ref(false)
const rows = ref<Batch[]>([])

/** 临期/待清理（预警口径，与仓库侧 /tenant/batches/expiring 一致） */
const alertRows = computed<Batch[]>(() =>
  rows.value.filter((b) => ['EXPIRING', 'PENDING_CLEARANCE'].includes(String(b.status))),
)

const displayRows = computed<Batch[]>(() =>
  activeTab.value === TAB_ALERT ? alertRows.value : rows.value,
)

const fetchBatches = async () => {
  loading.value = true
  try {
    const res = await batchApi.listForWholesaler()
    rows.value = res.list ?? []
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void fetchBatches()
  void fetchSkus()
})
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏（公共组件 + 站内信铃铛） -->
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
            <h2 class="page-head__title">批次临期</h2>
            <p class="page-head__sub">
              我方货物的批次与临期情况（只读）；批次剩余为先入先出离线推算值（截至今日
              02:00），可能与实物有 ≤1 天误差
            </p>
          </div>
          <div class="page-head__actions">
            <el-button :icon="Refresh" :loading="loading" @click="fetchBatches">刷新</el-button>
          </div>
        </header>

        <!-- 口径文案（只读视角：处理动作在仓库侧） -->
        <el-alert
          type="info"
          :closable="false"
          class="policy-alert"
          data-test="wa-batch-policy-copy"
        >
          临期与过期批次由仓库负责清理/处置并另行通知；如需提前出货或协商处理，请线下联系仓库。
        </el-alert>

        <section class="card">
          <el-tabs v-model="activeTab" data-test="wa-batch-tabs">
            <el-tab-pane :name="TAB_ALERT">
              <template #label>
                <span class="tab-label">
                  临期/待清理
                  <NavCountBadge :count="alertRows.length" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="全部批次" :name="TAB_ALL" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="displayRows"
            row-key="id"
            class="batch-table"
            data-test="wa-batch-table"
            :empty-text="
              activeTab === TAB_ALERT
                ? '暂无临期或待清理批次'
                : '暂无批次（店铺开启批次管理并入库后产生）'
            "
          >
            <el-table-column prop="batchNo" label="批次号" min-width="130" />
            <el-table-column label="商品" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ skuLabel(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="累计入库" width="90" align="right" prop="initialQty" />
            <el-table-column label="推算剩余" width="95" align="right">
              <template #default="{ row }">{{ row.remainingQty }} 件*</template>
            </el-table-column>
            <el-table-column label="生产日期" width="105">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatDate(row.productionDate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="到效期" width="105">
              <template #default="{ row }">{{ formatDate(row.expiryDate) }}</template>
            </el-table-column>
            <el-table-column label="剩余天数" width="95">
              <template #default="{ row }">
                <span :class="remainClass(row as Batch)" data-test="wa-remaining-days">
                  {{ remainText(row as Batch) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <span class="cell-muted">{{ sourceLabel(row.source) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <p class="table-note">* 推算值 · 截至今日 02:00，清理以仓库现场核数为准</p>
        </section>
      </main>
    </div>
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
.page-head__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

.policy-alert :deep(.el-alert__description) {
  margin: 0;
}

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.batch-table {
  width: 100%;
}
.cell-muted {
  color: var(--color-fg-3);
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
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

.table-note {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0; /* 表格内部滚动，不撑宽页面 */
  }
}
</style>
