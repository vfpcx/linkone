<script setup lang="ts">
/**
 * TA 店铺工作台（PC）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §2.1
 *  - 视觉：MASTER §4.7 容量条 / §4.6 状态徽章 / Data-Dense Dashboard
 *  - 故事：US-TA-08（账单总览）、US-TA-10（容量公示）、US-TA-12（批次开关）
 *
 * 结构：
 *  - 顶栏：仓储云 · {店铺名} + 角色切换 + 🔔 + user 菜单
 *  - 左侧菜单 8 项
 *  - 主区：
 *    1. 本店概览（CapacityBar + 容量公示说明）
 *    2. 待处理 KPI 4 卡片
 *    3. 今日数据（入库 / 出库 / 询价 + 临期入口）
 *
 * 数据：mocks/dashboard.ts（后端接口真实联调前用）
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Notification,
  ArrowDown,
  Sunny,
  Switch,
  Bell,
  Box,
  Shop,
  User,
  Document,
  Coin,
  ChatLineSquare,
  Setting,
  TrendCharts,
} from '@element-plus/icons-vue'
import { CapacityBar, StatusBadge } from '@cangchu/ui-shared'
import { useAuthStore } from '@/stores/auth'
import { accountApi } from '@/api/account'
import { mockTenantDashboard, mockNotifications, mockMyRoles } from '@/mocks/dashboard'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(
  () => auth.currentStoreName || mockTenantDashboard.storeName,
)

const handleSwitchRole = () => {
  auth.showSwitcher()
}

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

// ============ 数据 ============
const dashboard = ref(mockTenantDashboard)
const notifications = ref(mockNotifications)
const unreadCount = computed(() => notifications.value.filter((n) => n.unread).length)
const loading = ref(false)

const fetchDashboard = async () => {
  loading.value = true
  try {
    // 后端真实接口对接前，用 mock 模拟加载
    await new Promise((r) => setTimeout(r, 300))
    dashboard.value = mockTenantDashboard
  } finally {
    loading.value = false
  }
}

// ============ 菜单 ============
const activeMenu = ref('/ta/dashboard')

interface MenuItem {
  key: string
  label: string
  icon: typeof Box
  badge?: number
}

const menus = computed<MenuItem[]>(() => [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  {
    key: '/ta/approvals',
    label: '单据审批',
    icon: Document,
    badge:
      dashboard.value.kpi.pendingInbound +
      dashboard.value.kpi.pendingCount +
      dashboard.value.kpi.pendingClearance +
      dashboard.value.kpi.pendingDispute,
  },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
])

const handleMenuSelect = (key: string) => {
  if (key === '/ta/dashboard') {
    activeMenu.value = key
    return
  }
  if (key === '/ta/settings') {
    router.push('/ta/settings')
    return
  }
  ElMessage.info(`「${menus.value.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ KPI 卡片 ============
const kpiCards = computed(() => [
  {
    key: 'pendingInbound',
    label: '入驻审批',
    value: dashboard.value.kpi.pendingInbound,
    color: 'progress' as const,
  },
  {
    key: 'pendingCount',
    label: '盘点审批',
    value: dashboard.value.kpi.pendingCount,
    color: 'warning' as const,
  },
  {
    key: 'pendingClearance',
    label: '清库审批',
    value: dashboard.value.kpi.pendingClearance,
    color: 'warning' as const,
  },
  {
    key: 'pendingDispute',
    label: '申诉处理',
    value: dashboard.value.kpi.pendingDispute,
    color: 'danger' as const,
  },
])

// ============ 容量可见性标签 ============
const visibilityLabel = computed(() => {
  const map = {
    PRIVATE: '不公开',
    WA_ONLY: '已入驻可见',
    PUBLIC: '全平台可见',
  } as const
  return map[dashboard.value.capacity.visibility]
})

const todayDate = computed(() => {
  const d = new Date()
  return `${d.getMonth() + 1}/${d.getDate()}`
})

onMounted(fetchDashboard)
</script>

<template>
  <div class="ta-shell" v-loading="loading">
    <!-- 顶栏 -->
    <header class="ta-topbar">
      <div class="ta-topbar__left">
        <span class="ta-topbar__brand">仓储云</span>
        <span class="ta-topbar__divider">·</span>
        <span class="ta-topbar__store">{{ storeNameDisplay }}</span>
      </div>

      <div class="ta-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>

        <el-popover trigger="click" placement="bottom-end" :width="320">
          <template #reference>
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="ta-topbar__bell">
              <el-button text :icon="Bell" />
            </el-badge>
          </template>
          <div class="notif">
            <div v-for="n in notifications" :key="n.id" class="notif__item" :class="{ unread: n.unread }">
              <span>{{ n.title }}</span>
              <span class="notif__time">{{ n.time }}</span>
            </div>
            <div v-if="notifications.length === 0" class="notif__empty">暂无通知</div>
          </div>
        </el-popover>

        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="ta-topbar__user">
            <el-avatar :size="28">U</el-avatar>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人资料</el-dropdown-item>
              <el-dropdown-item command="security">安全设置</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="ta-body">
      <!-- 左侧菜单 -->
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
            <el-badge
              v-if="m.badge && m.badge > 0"
              :value="m.badge"
              :max="99"
              class="ta-side__badge"
            />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <!-- 本店概览 -->
        <section class="ta-section">
          <header class="ta-section__head">
            <h3 class="ta-section__title">本店概览</h3>
            <StatusBadge :variant="dashboard.batchEnabled ? 'success' : 'default'"
              :text="dashboard.batchEnabled ? '批次启用' : '批次未启用'" />
          </header>

          <div class="overview">
            <div class="overview__cap">
              <CapacityBar
                label="件数"
                unit="件"
                :used="dashboard.capacity.usedQty"
                :total="dashboard.capacity.totalQty"
              />
              <CapacityBar
                label="托盘"
                unit="托"
                :used="dashboard.capacity.usedPallet"
                :total="dashboard.capacity.totalPallet"
              />
            </div>

            <div class="overview__meta">
              <div class="overview__row">
                <span class="overview__label">容量公示</span>
                <StatusBadge variant="progress" :text="visibilityLabel" />
              </div>
              <div class="overview__row">
                <span class="overview__label">利用率</span>
                <span class="overview__pct">{{ dashboard.capacity.utilization }}%</span>
              </div>
              <el-button size="small" plain @click="handleMenuSelect('/ta/settings')">
                改设置
              </el-button>
            </div>
          </div>
        </section>

        <!-- KPI 待处理 -->
        <section class="ta-section">
          <header class="ta-section__head">
            <h3 class="ta-section__title">待处理</h3>
            <el-button text type="primary" @click="handleMenuSelect('/ta/approvals')">
              查看全部 →
            </el-button>
          </header>

          <div class="kpi-row">
            <div
              v-for="card in kpiCards"
              :key="card.key"
              class="kpi-card"
              :class="`kpi-card--${card.color}`"
              @click="handleMenuSelect('/ta/approvals')"
            >
              <div class="kpi-card__value">{{ card.value }}</div>
              <div class="kpi-card__label">{{ card.label }}</div>
              <StatusBadge v-if="card.value > 0" :variant="card.color" :text="`${card.value} 待处理`" :dot="true" />
              <StatusBadge v-else variant="default" text="无" :dot="true" />
            </div>
          </div>
        </section>

        <!-- 今日数据 -->
        <section class="ta-section">
          <header class="ta-section__head">
            <h3 class="ta-section__title">今日 ({{ todayDate }})</h3>
          </header>

          <div class="today">
            <div class="today__item">
              <el-icon class="today__icon today__icon--accent"><Box /></el-icon>
              <div>
                <div class="today__num">{{ dashboard.today.inboundCount }}</div>
                <div class="today__label">入库单</div>
              </div>
            </div>
            <div class="today__divider" />
            <div class="today__item">
              <el-icon class="today__icon today__icon--success"><Document /></el-icon>
              <div>
                <div class="today__num">{{ dashboard.today.outboundCount }}</div>
                <div class="today__label">出库单</div>
              </div>
            </div>
            <div class="today__divider" />
            <div class="today__item">
              <el-icon class="today__icon today__icon--warning"><ChatLineSquare /></el-icon>
              <div>
                <div class="today__num">{{ dashboard.today.inquiryCount }}</div>
                <div class="today__label">询价单</div>
              </div>
            </div>

            <div v-if="dashboard.batchEnabled" class="today__divider" />
            <div v-if="dashboard.batchEnabled" class="today__item">
              <el-icon class="today__icon today__icon--danger"><Sunny /></el-icon>
              <div>
                <div class="today__num">{{ dashboard.today.expiringBatches }}</div>
                <div class="today__label">
                  临期 3 天内
                  <el-button v-if="dashboard.today.expiringBatches > 0" size="small" link type="warning">
                    查看
                  </el-button>
                </div>
              </div>
            </div>
          </div>

          <p v-if="!dashboard.batchEnabled" class="today__hint">
            <el-icon><Notification /></el-icon>
            批次未启用，临期预警停用（前往「店铺设置 → 批次管理」开启）
          </p>
        </section>

        <!-- 角色提示 -->
        <section class="ta-section ta-section--quiet">
          <p class="ta-help">
            身份：<strong>{{ mockMyRoles.find(r => r.role === auth.primaryRole)?.label ?? auth.primaryRole }}</strong>
            ｜ 当前数据为前端 mock，待后端 <code>GET /api/v1/tenant/dashboard</code> 联调
          </p>
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

/* ===== 顶栏 ===== */
.ta-topbar {
  height: 56px;
  background: var(--color-brand-primary);
  color: var(--color-brand-primary-on);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  position: sticky;
  top: 0;
  z-index: var(--z-fixed);
  box-shadow: var(--shadow-base);
}

.ta-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.ta-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.ta-topbar__divider {
  opacity: 0.5;
}
.ta-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}

.ta-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.ta-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.ta-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.ta-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}

.ta-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.ta-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
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

.ta-side__badge {
  margin-left: auto;
  margin-right: 12px;
}

/* ===== 主区 ===== */
.ta-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.ta-section {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}
.ta-section--quiet {
  background: transparent;
  box-shadow: none;
  padding: 0 var(--space-2);
}

.ta-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}
.ta-section__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0;
}

/* 概览 */
.overview {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: var(--space-6);
}
.overview__cap {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}
.overview__meta {
  background: var(--color-bg-2);
  border-radius: var(--radius-base);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.overview__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--font-size-body);
}
.overview__label {
  color: var(--color-fg-3);
}
.overview__pct {
  font-family: var(--font-family-mono);
  font-weight: var(--font-weight-bold);
  font-variant-numeric: tabular-nums;
  color: var(--color-fg-1);
}

/* KPI */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
}
.kpi-card {
  background: var(--color-bg-2);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  cursor: pointer;
  transition: all var(--duration-fast) var(--easing-standard);
  border: 1px solid transparent;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.kpi-card:hover {
  border-color: var(--color-brand-accent);
  background: var(--color-bg-1);
  box-shadow: var(--shadow-sm);
}
.kpi-card__value {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-display);
  font-weight: var(--font-weight-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--color-fg-1);
}
.kpi-card--warning .kpi-card__value {
  color: var(--color-warning);
}
.kpi-card--danger .kpi-card__value {
  color: var(--color-danger);
}
.kpi-card--progress .kpi-card__value {
  color: var(--color-brand-accent);
}
.kpi-card__label {
  font-size: var(--font-size-body);
  color: var(--color-fg-2);
}

/* 今日 */
.today {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}
.today__item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
}
.today__icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-base);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  background: var(--color-bg-3);
}
.today__icon--accent {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
}
.today__icon--success {
  background: var(--color-success-bg);
  color: var(--color-success);
}
.today__icon--warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
}
.today__icon--danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.today__divider {
  width: 1px;
  height: 32px;
  background: var(--color-border-1);
}
.today__num {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.today__label {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
}
.today__hint {
  margin: var(--space-3) 0 0;
  padding: var(--space-3);
  background: var(--color-warning-bg);
  color: #92400e;
  border-radius: var(--radius-base);
  font-size: var(--font-size-caption);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

/* 提示 */
.ta-help {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  margin: 0;
}
.ta-help code {
  background: var(--color-bg-3);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-family: var(--font-family-mono);
  font-size: 12px;
}

/* ===== 通知弹层 ===== */
.notif__item {
  display: flex;
  justify-content: space-between;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--font-size-body);
  color: var(--color-fg-2);
}
.notif__item.unread {
  background: var(--color-info-bg);
  color: var(--color-fg-1);
  font-weight: var(--font-weight-medium);
}
.notif__time {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.notif__empty {
  text-align: center;
  padding: var(--space-6);
  color: var(--color-fg-4);
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .overview {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .kpi-row {
    grid-template-columns: 1fr;
  }
  .today {
    flex-direction: column;
    align-items: stretch;
  }
  .today__divider {
    width: 100%;
    height: 1px;
  }
}
</style>
