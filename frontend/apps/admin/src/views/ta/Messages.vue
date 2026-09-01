<script setup lang="ts">
/**
 * TA 消息中心（PC · P5-A W4）
 *
 * 来源：
 *  - 需求：18-p5-design.md §4.1（列表/未读筛选/全部已读/单条已读）+ §7 前端改动表（消息中心页）
 *  - 复用：ui-shared NotificationList 通用组件（admin 与司机端共用，逻辑内聚）
 *  - 视觉：沿用 Dashboard/Settings 的「顶栏 + 左侧菜单」TA shell
 *
 * 契约：
 *  - GET  /notifications?page&size&unreadOnly&group  分组 Tab 全由后端过滤
 *  - POST /notifications/read-all                    全部已读（幂等）
 *  - POST /notifications/{id}/read                   单条已读（幂等）
 *  - 公告类（PLATFORM_ANNOUNCEMENT）点击展开正文；业务类点按 emit('navigate') 跳转
 *
 * 中文规范：Tab/按钮/空态/角色码全中文；英文枚举码仅出现在 API 参数中。
 */

import { useRouter } from 'vue-router'
import {
  ChatLineSquare,
  Setting,
  Shop,
  Stamp,
  TrendCharts,
  User,
  Box,
  Van,
} from '@element-plus/icons-vue'
import { AppTopbar, NotificationList } from '@cangchu/ui-shared'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { notificationApi } from '@/api/notification'
import { accountApi } from '@/api/account'
import { ElMessage, ElMessageBox } from 'element-plus'

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
const activeMenu = '/ta/messages'

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
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees' ||
    key === '/ta/wholesaler-applications' ||
    key === '/ta/inbound' ||
    key === '/ta/outbound'
  ) {
    router.push(key)
    return
  }
  // 其它菜单页尚未实现，保持占位
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 站内信 ============
// 分组 Tab 由后端 group 参数过滤；单条/全部已读走专门端点（幂等）。
const loadList = (q: { page: number; size: number; unreadOnly: boolean; group: 'ALL' | 'BIZ' | 'ANNOUNCE' }) =>
  notificationApi.list({ ...q })

const markOne = (id: string) => notificationApi.markRead(id)

const markAll = () => notificationApi.readAll()

/** 业务类消息跳转（公告类不触发：NotificationList 内部展开正文） */
const onNavigate = () => {
  // 业务消息跳转目标随业务模块滚动补充；当前已读闭环，跳转占位提示避免误触
  ElMessage.info('该业务消息详情页随对应业务模块上线后支持跳转')
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
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">消息中心</h2>
            <p class="page-head__sub">平台公告与业务消息统一在这里查看</p>
          </div>
        </header>

        <section class="card">
          <NotificationList
            :fetch-list="loadList"
            :mark-read="markOne"
            :mark-read-all="markAll"
            data-test="notification-list"
            @navigate="onNavigate"
          />
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

.ta-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

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

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .ta-main {
    max-width: none;
  }
}
</style>
