<script setup lang="ts">
/**
 * OPS 平台运营控制台（PC）— P5-C · 21-p5c-ops-console-design（原 /ops/dashboard 占位页转真实接口）
 *
 * 数据：GET /api/v1/ops/dashboard（requireOps，非 OPS → 42002；平台级统计）
 *  - platform  平台规模：营业仓库数 / 入驻绑定数 / 生效黑名单
 *  - pending   待办队列：待审租户 / 待裁客诉 / 公告草稿（点击跳对应管理页）
 *  - today     今日动态：今日新入驻仓库 / 今日新增客诉
 *
 * 视觉：沿用 OPS shell（顶栏 + 左侧菜单，本页为 5 项完整菜单锚点，D-OPS-6）；
 * 数据为空/失败时展示 0，工作台不阻塞（TA Dashboard 先例）。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Monitor,
  Stamp,
  CircleClose,
  Bell,
  ScaleToOriginal,
  Shop,
  UserFilled,
  WarningFilled,
  DocumentAdd,
  OfficeBuilding,
  Refresh,
} from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge } from '@cangchu/ui-shared'
import { useAuthStore } from '@/stores/auth'
import { opsApi } from '@/api/ops'
import { accountApi } from '@/api/account'
import type { OpsDashboardResponse } from '@cangchu/api-types'

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

// ============ 菜单（OPS 端 5 项统一锚点） ============
const activeMenu = ref('/ops/dashboard')

const menus = computed(() => [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor },
  { key: '/ops/tenant-audit', label: '租户审核', icon: Stamp, badge: data.value.pending.pendingTenantAudits },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose },
  { key: '/ops/announcements', label: '公告管理', icon: Bell, badge: data.value.pending.draftAnnouncements },
  { key: '/ops/arbitrations', label: '客诉仲裁', icon: ScaleToOriginal, badge: data.value.pending.pendingComplaints },
])

const handleMenuSelect = (key: string) => {
  if (key === '/ops/dashboard') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 控制台数据 ============
// 空默认：加载/失败期间展示 0，不阻断控制台（P5-C 真实接口先例）
const data = ref<OpsDashboardResponse>({
  platform: { activeTenantCount: 0, wholesalerBindingCount: 0, activeBlacklistCount: 0 },
  pending: { pendingTenantAudits: 0, pendingComplaints: 0, draftAnnouncements: 0 },
  today: { newTenantToday: 0, newComplaintsToday: 0 },
})
const loading = ref(false)

const fetchDashboard = async () => {
  loading.value = true
  try {
    data.value = await opsApi.getDashboard()
  } catch {
    // 全局 toast 已提示；保留空默认
  } finally {
    loading.value = false
  }
}

const refresh = async () => {
  await fetchDashboard()
}

onMounted(fetchDashboard)

// ============ 待办跳转 ============
const goTo = (path: string) => {
  router.push(path)
}

const goTenantAudit = () => goTo('/ops/tenant-audit')
const goArbitrations = () => goTo('/ops/arbitrations')
const goAnnouncements = () => goTo('/ops/announcements')

// ============ 展示 ============
const fmt = (n: number) => String(n ?? 0)
</script>

<template>
  <div class="ops-shell">
    <!-- 顶栏 -->
    <AppTopbar
      store-name="平台运营"
      avatar-text="O"
      @switch-role="handleSwitchRole"
      @profile-command="handleProfileMenu"
    />

    <div class="ops-body">
      <!-- 左侧菜单 -->
      <aside class="ops-side">
        <el-menu :default-active="activeMenu" class="ops-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
            <NavCountBadge v-if="m.badge !== undefined" :count="m.badge ?? 0" class="ops-side__badge" />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ops-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">平台运营控制台</h2>
            <p class="page-head__sub">
              平台规模 · 待办队列 · 今日动态 —— 数据为实时统计（GMT+8 今日 0 点起）
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" plain @click="refresh">刷新</el-button>
        </header>

        <!-- 1. 平台规模 -->
        <section class="dash-sec">
          <h3 class="dash-sec__title"><el-icon><OfficeBuilding /></el-icon>平台规模</h3>
          <div class="dash-grid dash-grid--3">
            <div class="stat-card">
              <div class="stat-card__icon stat-card__icon--brand"><el-icon><Shop /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.platform.activeTenantCount) }}</div>
              <div class="stat-card__label">营业仓库数</div>
              <div class="stat-card__hint">自助注册 + OPS 代建（ACTIVE）</div>
            </div>
            <div class="stat-card">
              <div class="stat-card__icon stat-card__icon--info"><el-icon><UserFilled /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.platform.wholesalerBindingCount) }}</div>
              <div class="stat-card__label">入驻绑定数</div>
              <div class="stat-card__hint">同一批发商入驻多仓计多次</div>
            </div>
            <div class="stat-card">
              <div class="stat-card__icon stat-card__icon--danger"><el-icon><CircleClose /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.platform.activeBlacklistCount) }}</div>
              <div class="stat-card__label">生效黑名单</div>
              <div class="stat-card__hint">手机号 / 营业执照号</div>
            </div>
          </div>
        </section>

        <!-- 2. 待办队列 -->
        <section class="dash-sec">
          <h3 class="dash-sec__title"><el-icon><Bell /></el-icon>我的待办</h3>
          <div class="dash-grid dash-grid--3">
            <button class="stat-card stat-card--action" @click="goTenantAudit">
              <div class="stat-card__icon stat-card__icon--brand"><el-icon><Stamp /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.pending.pendingTenantAudits) }}</div>
              <div class="stat-card__label">待审租户</div>
              <div class="stat-card__hint">新注册仓库等待审核 → 去处理</div>
            </button>
            <button class="stat-card stat-card--action" @click="goArbitrations">
              <div class="stat-card__icon stat-card__icon--danger"><el-icon><WarningFilled /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.pending.pendingComplaints) }}</div>
              <div class="stat-card__label">待裁客诉</div>
              <div class="stat-card__hint">批发商出库客诉等待裁决 → 去处理</div>
            </button>
            <button class="stat-card stat-card--action" @click="goAnnouncements">
              <div class="stat-card__icon stat-card__icon--info"><el-icon><DocumentAdd /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.pending.draftAnnouncements) }}</div>
              <div class="stat-card__label">公告草稿</div>
              <div class="stat-card__hint">草稿待发布 → 去处理</div>
            </button>
          </div>
        </section>

        <!-- 3. 今日动态 -->
        <section class="dash-sec">
          <h3 class="dash-sec__title"><el-icon><OfficeBuilding /></el-icon>今日动态</h3>
          <div class="dash-grid dash-grid--2">
            <div class="stat-card">
              <div class="stat-card__icon stat-card__icon--success"><el-icon><Shop /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.today.newTenantToday) }}</div>
              <div class="stat-card__label">今日新入驻仓库</div>
              <div class="stat-card__hint">今日 0 点起新注册 / 新入驻</div>
            </div>
            <div class="stat-card">
              <div class="stat-card__icon stat-card__icon--warning"><el-icon><WarningFilled /></el-icon></div>
              <div class="stat-card__num">{{ fmt(data.today.newComplaintsToday) }}</div>
              <div class="stat-card__label">今日新增客诉</div>
              <div class="stat-card__hint">今日 0 点起新发起的出库客诉</div>
            </div>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* OPS shell（与 ops/Announcements.vue 同一套骨架与变量） */
.ops-shell {
  min-height: 100vh;
  background: var(--color-page-bg, #f5f7fa);
}
.ops-body {
  display: flex;
  min-height: calc(100vh - 56px);
}
.ops-side {
  width: 220px;
  background: var(--color-panel-bg, #fff);
  border-right: 1px solid var(--color-border, #ebeef5);
  padding-top: 8px;
  flex-shrink: 0;
}
.ops-side__menu {
  border-right: none;
}
.ops-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ops-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
.ops-side__badge {
  margin-left: auto;
}
.ops-main {
  flex: 1;
  padding: 24px 28px 40px;
  overflow-y: auto;
}

/* 页头 */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-head__title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
.page-head__sub {
  margin: 6px 0 0;
  color: var(--color-text-secondary, #909399);
  font-size: 13px;
}

/* 区块 */
.dash-sec {
  margin-bottom: 24px;
}
.dash-sec__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 12px;
  color: var(--color-text-primary, #303133);
}
.dash-grid {
  display: grid;
  gap: 16px;
}
.dash-grid--3 {
  grid-template-columns: repeat(3, 1fr);
}
.dash-grid--2 {
  grid-template-columns: repeat(2, 1fr);
}

/* 统计卡 */
.stat-card {
  position: relative;
  display: block;
  width: 100%;
  text-align: left;
  background: var(--color-panel-bg, #fff);
  border: 1px solid var(--color-border, #ebeef5);
  border-radius: 10px;
  padding: 18px 20px;
  cursor: default;
  transition: box-shadow 0.2s, transform 0.2s;
  font-family: inherit;
}
.stat-card--action {
  cursor: pointer;
}
.stat-card--action:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
  border-color: var(--color-brand-accent);
}
.stat-card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  font-size: 20px;
  margin-bottom: 12px;
}
.stat-card__icon--brand { background: #ecf5ff; color: #409eff; }
.stat-card__icon--info { background: #f4f4f5; color: #606266; }
.stat-card__icon--danger { background: #fef0f0; color: #f56c6c; }
.stat-card__icon--success { background: #f0f9eb; color: #67c23a; }
.stat-card__icon--warning { background: #fdf6ec; color: #e6a23c; }
.stat-card__num {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--color-text-primary, #303133);
}
.stat-card__label {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary, #303133);
}
.stat-card__hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
}
</style>
