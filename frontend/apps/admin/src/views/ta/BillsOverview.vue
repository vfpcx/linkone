<script setup lang="ts">
/**
 * TA 账单总览（P4 W5 · US-TA-08，独立总览页替代 /ta/bills → /st/bills 兼岗直连）
 *
 * 契约（backend BillsOverviewController + BillsOverviewVo，W5a 实测）：
 *  - GET /api/v1/tenant/bills-overview?month=   requireTa（ST 42001 / WE 42004 / WK·WA 42001）
 *  - month 可缺省 = 全部月份；应收/已收/未收/账单数 + 全局状态分布 + 逐商户行（未收降序）
 *
 * 页面：月份筛选 + 四汇总卡 + 状态分布徽章 + 商户行表（未收降序，后端已排好）；
 * 行点击 → /st/bills?wholesalerId=&month= 下钻单商户账单列表（TA 兼岗权限并集放行）。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Shop,
  User,
  Document,
  Coin,
  ChatLineSquare,
  Setting,
  TrendCharts,
  Stamp,
  Van,
  RefreshLeft,
  Checked,
  AlarmClock,
  Remove,
} from '@element-plus/icons-vue'
import { AppTopbar, MoneyDisplay, StatusBadge } from '@cangchu/ui-shared'
import type { BillsOverviewResponse, BillsOverviewRow } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { tenantApi } from '@/api/tenant'
import { accountApi } from '@/api/account'
import { BILL_STATUS_META, billStatusMeta } from '@/utils/billing'

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

// ============ 菜单（TA shell 惯例；本页挂在「账单总览」/ta/bills 入口下） ============
const activeMenu = ref('/ta/bills')

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
  if (key === '/ta/bills') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees' ||
    key === '/ta/wholesaler-applications' ||
    key === '/ta/approvals' ||
    key === '/ta/outbound' ||
    key === '/ta/returns' ||
    key === '/ta/stocktake' ||
    key === '/ta/batches' ||
    key === '/ta/clearance'
  ) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 总览数据 ============
const loading = ref(false)
const month = ref('') // '' = 全部月份（契约 month 可缺省）
const overview = ref<BillsOverviewResponse | null>(null)

const fetchOverview = async () => {
  loading.value = true
  try {
    overview.value = await tenantApi.getBillsOverview(
      month.value ? { month: month.value } : undefined,
    )
  } catch {
    // 42001 越权等：全局 toast 已提示
  } finally {
    loading.value = false
  }
}

onMounted(fetchOverview)

/** 状态分布按 6 态固定顺序展示（仅出现的状态有键） */
const orderedStatusCounts = (counts: Record<string, number> | null | undefined) =>
  Object.keys(BILL_STATUS_META)
    .filter((s) => (counts?.[s] ?? 0) > 0)
    .map((s) => ({ status: s, count: counts![s], meta: billStatusMeta(s) }))

const globalStatusCounts = computed(() => orderedStatusCounts(overview.value?.statusCounts))

// ============ 行下钻（→ ST 账单列表带商户过滤，TA 兼岗权限并集） ============
const openWholesalerBills = (row: BillsOverviewRow) => {
  router.push({
    path: '/st/bills',
    query: {
      wholesalerId: String(row.wholesalerId),
      ...(month.value ? { month: month.value } : {}),
    },
  })
}
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
      <main v-loading="loading" class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">账单总览</h2>
            <p class="page-head__sub">
              全部入驻商户的仓储费账单汇总；点击商户行可查看该商户账单明细
            </p>
          </div>
          <el-date-picker
            v-model="month"
            type="month"
            format="YYYY-MM"
            value-format="YYYY-MM"
            placeholder="账期（全部月份）"
            clearable
            class="month-picker"
            data-test="ta-overview-month"
            @change="fetchOverview"
          />
        </header>

        <!-- 四汇总卡 -->
        <div class="summary-cards" data-test="ta-overview-cards">
          <div class="summary-card">
            <span class="summary-card__label">应收</span>
            <MoneyDisplay :value="overview?.receivable ?? 0" size="lg" data-test="ta-overview-receivable" />
          </div>
          <div class="summary-card">
            <span class="summary-card__label">已收</span>
            <MoneyDisplay :value="overview?.received ?? 0" size="lg" data-test="ta-overview-received" />
          </div>
          <div class="summary-card">
            <span class="summary-card__label">未收</span>
            <MoneyDisplay :value="overview?.outstanding ?? 0" size="lg" data-test="ta-overview-outstanding" />
          </div>
          <div class="summary-card">
            <span class="summary-card__label">账单张数</span>
            <span class="summary-card__count" data-test="ta-overview-bill-count">
              {{ overview?.billCount ?? 0 }}
            </span>
          </div>
        </div>

        <!-- 全局状态分布 -->
        <section v-if="globalStatusCounts.length > 0" class="card status-card" data-test="ta-overview-status">
          <span class="status-card__label">状态分布</span>
          <StatusBadge
            v-for="s in globalStatusCounts"
            :key="s.status"
            :variant="s.meta.variant"
            :text="`${s.meta.label} ${s.count} 张`"
          />
        </section>

        <!-- 商户行表（未收降序，后端已排好） -->
        <section class="card">
          <el-table
            :data="overview?.rows ?? []"
            class="row-table"
            data-test="ta-overview-table"
            @row-click="(row: BillsOverviewRow) => openWholesalerBills(row)"
          >
            <el-table-column label="批发商" prop="wholesalerName" min-width="140" />
            <el-table-column label="应收" align="right" width="130">
              <template #default="{ row }"><MoneyDisplay :value="row.receivable" size="sm" /></template>
            </el-table-column>
            <el-table-column label="已收" align="right" width="130">
              <template #default="{ row }"><MoneyDisplay :value="row.received" size="sm" /></template>
            </el-table-column>
            <el-table-column label="未收" align="right" width="130">
              <template #default="{ row }"><MoneyDisplay :value="row.outstanding" size="sm" /></template>
            </el-table-column>
            <el-table-column label="账单数" align="right" width="90">
              <template #default="{ row }">{{ row.billCount }}</template>
            </el-table-column>
            <el-table-column label="状态分布" min-width="220">
              <template #default="{ row }">
                <span class="row-status">
                  <StatusBadge
                    v-for="s in orderedStatusCounts(row.statusCounts)"
                    :key="s.status"
                    :variant="s.meta.variant"
                    :text="`${s.meta.label} ${s.count}`"
                    size="sm"
                  />
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  text
                  type="primary"
                  size="small"
                  data-test="ta-overview-row-action"
                  @click.stop="openWholesalerBills(row as BillsOverviewRow)"
                >
                  查看账单
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty
                :description="month ? `${month} 暂无账单` : '暂无账单（每月 1 日凌晨自动生成上月账单）'"
                :image-size="72"
              />
            </template>
          </el-table>
        </section>
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
  display: flex;
  align-items: center;
}
.ta-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.ta-main {
  flex: 1;
  min-width: 0;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
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
.month-picker {
  width: 170px;
}

/* 四汇总卡 */
.summary-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}
.summary-card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-5);
  box-shadow: var(--shadow-base);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.summary-card__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.summary-card__count {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  font-variant-numeric: tabular-nums;
  color: var(--color-fg-1);
  line-height: 1.2;
}

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-base);
}
.status-card {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  padding: var(--space-3) var(--space-4);
}
.status-card__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  margin-right: var(--space-2);
}
.row-table {
  width: 100%;
  cursor: pointer;
}
.row-status {
  display: inline-flex;
  gap: var(--space-1);
  flex-wrap: wrap;
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .summary-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .ta-main {
    padding: var(--space-3);
    gap: var(--space-3);
  }
  .summary-cards {
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-2);
  }
  .month-picker {
    width: 100%;
  }
}
</style>
