<script setup lang="ts">
/**
 * 多角色工作空间切换器（登录后弹窗）
 *
 * 来源：
 *  - 线框：shared/product/06-page-wireframes.md §0.5.4
 *  - 视觉：shared/design-system/pages/auth.md（多角色切换器）
 *  - 故事：US-COMMON-02 多角色处理
 *
 * 优先级：TA > ST > WK > WA > WE（priority 数字越小越优先，10/20/30/40/50/60）
 *  默认推荐角色 = 排序后第一个（绿点）
 */

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElDialog } from 'element-plus'
import type { LoginRoleEntry, Role } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const visible = computed({
  get: () => auth.switcherVisible,
  set: (v) => (v ? auth.showSwitcher() : auth.hideSwitcher()),
})

const sortedRoles = computed(() => {
  return [...auth.roles].sort((a, b) => a.priority - b.priority)
})

const recommendedRole = computed(() => sortedRoles.value[0]?.role)

const ROLE_LABEL: Record<Role, string> = {
  OPS: '运维',
  TA: '租户管理员',
  WK: '库管员',
  ST: '结算员',
  WA: '批发商管理员',
  WE: '批发商员工',
  RT: '终端买家',
}

const ROLE_ICON: Record<Role, string> = {
  OPS: '🛠',
  TA: '🏢',
  ST: '💰',
  WK: '📦',
  WA: '🏪',
  WE: '👤',
  RT: '🛒',
}

const handleEnter = (entry: LoginRoleEntry) => {
  auth.switchActiveRole(entry)
  router.replace(auth.primaryRouter)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="您在多个工作空间，请选择进入"
    width="520px"
    :close-on-click-modal="false"
    :show-close="false"
    align-center
  >
    <div class="switcher">
      <div
        v-for="entry in sortedRoles"
        :key="`${entry.role}-${entry.tenantId ?? 'na'}`"
        class="switcher__item"
      >
        <div class="switcher__icon">{{ ROLE_ICON[entry.role] }}</div>
        <div class="switcher__info">
          <div class="switcher__title">
            {{ entry.storeName || ROLE_LABEL[entry.role] }}
            <span v-if="entry.role === recommendedRole" class="switcher__dot" />
          </div>
          <div class="switcher__sub">
            {{ ROLE_LABEL[entry.role] }}
            <span v-if="entry.pendingCount" class="switcher__pending">
              · {{ entry.pendingCount }} 个待办
            </span>
          </div>
        </div>
        <el-button type="primary" @click="handleEnter(entry)">进入</el-button>
      </div>

      <p class="switcher__priority">已选优先级: TA &gt; ST &gt; WK &gt; WA &gt; WE</p>
    </div>
  </el-dialog>
</template>

<style scoped>
.switcher {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.switcher__item {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  background: var(--color-bg-1);
  transition: all var(--duration-fast) var(--easing-standard);
}
.switcher__item:hover {
  border-color: var(--color-brand-accent);
  background: var(--color-bg-2);
}

.switcher__icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-3);
  border-radius: var(--radius-base);
  font-size: 22px;
  flex-shrink: 0;
}

.switcher__info {
  flex: 1;
  min-width: 0;
}

.switcher__title {
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.switcher__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-success);
  display: inline-block;
}

.switcher__sub {
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
  margin-top: 2px;
}

.switcher__pending {
  color: var(--color-warning);
}

.switcher__priority {
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
  text-align: center;
  margin: var(--space-4) 0 0;
}
</style>
