<script setup lang="ts">
/**
 * 结算员工作区外壳 · StShell（P4 W4）
 *
 * 结算员导航铁律（05 §5.4 / PRD 13-p4 §4.3）：不出现任何库存菜单——
 * 仅 结算工作台 / 账单 / 申诉处理 三项；库存明细页对结算员整域无入口。
 * 仓库老板兼岗（权限并集）通过「账单总览」菜单进入同一组页面。
 *
 * 角标：申诉处理菜单挂待处理申诉数（NavCountBadge 裸计数规范 MASTER §4.11）。
 */

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, TrendCharts, ChatLineSquare } from '@element-plus/icons-vue'
import { AppTopbar, NavCountBadge } from '@cangchu/ui-shared'
import { useAuthStore } from '@/stores/auth'
import { accountApi } from '@/api/account'
import { stBillApi } from '@/api/billing'
import NotificationBell from '@/components/NotificationBell.vue'

const props = defineProps<{
  /** 当前激活菜单 key（/st/dashboard | /st/bills | /st/disputes） */
  active: string
}>()

const router = useRouter()
const auth = useAuthStore()

const menus = [
  { key: '/st/dashboard', label: '结算工作台', icon: TrendCharts },
  { key: '/st/bills', label: '账单', icon: Coin },
  { key: '/st/disputes', label: '申诉处理', icon: ChatLineSquare },
]

/** 待处理申诉角标（失败静默；挂载拉一次） */
const pendingDisputes = ref(0)
onMounted(async () => {
  try {
    const list = await stBillApi.listDisputes('PENDING')
    pendingDisputes.value = list?.length ?? 0
  } catch {
    /* 无权限/网络失败不打扰 */
  }
})

const handleMenuSelect = (key: string) => {
  if (key === props.active) return
  router.push(key)
}

const handleSwitchRole = () => auth.showSwitcher()

const handleProfileMenu = async (cmd: string) => {
  switch (cmd) {
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
</script>

<template>
  <div class="st-shell">
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #bell><NotificationBell /></template>
    </AppTopbar>

    <div class="st-body">
      <aside class="st-side">
        <el-menu :default-active="active" class="st-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span class="st-side__label">
              {{ m.label }}
              <NavCountBadge v-if="m.key === '/st/disputes'" :count="pendingDisputes" />
            </span>
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="st-main">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.st-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}
.st-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}
.st-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.st-side__menu {
  border-right: none;
}
.st-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.st-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
.st-side__label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
.st-main {
  flex: 1;
  min-width: 0;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* D-P4-9 降档：<768px 移动视口收起侧栏（三核心页可用为准） */
@media (max-width: 768px) {
  .st-side {
    display: none;
  }
  .st-main {
    padding: var(--space-3);
    gap: var(--space-3);
  }
}
</style>
