<script setup lang="ts">
/**
 * 顶栏 Shell · AppTopbar（DEF-4 根治）
 *
 * 背景：TA/OPS/WA 各页面此前各自复制「品牌 + 店名 + 切换角色 + 通知 + 头像」顶栏，
 * TA/OPS 端缺窄屏收敛规则，375 断点出现品牌纵排竖字 / 店名与按钮重叠 / 头像溢出
 * （07-onboarding-e2e-report DEF-4）。现统一收编为公共组件，WA 端已验证的
 * 窄屏规则（品牌区单行省略、右区不收缩）随组件走。
 *
 * 用法：
 *  <AppTopbar store-name="平台运营" avatar-text="O"
 *             @switch-role="handleSwitchRole" @profile-command="handleProfileMenu" />
 *  TA 端店名区放仓库切换器：
 *  <AppTopbar @switch-role="..." @profile-command="...">
 *    <template #store><WarehouseSwitcher /></template>
 *  </AppTopbar>
 *  自定义通知入口（如 Dashboard 未读角标）走 #bell 插槽，
 *  插槽根元素请带 class="cc-topbar__bell" 以继承窄屏收起规则。
 *
 * 窄屏策略（375 复拍验收口径）：
 *  - 左区 min-width:0 + nowrap + overflow:hidden：品牌永不纵排，店名超长省略号；
 *  - 右区 flex-shrink:0：切换角色/通知/头像永不被挤出色块；
 *  - ≤480px：「切换角色」只留图标（title 提示）、通知铃收起，为店名让位。
 */

import {
  ElAvatar,
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
} from 'element-plus'
import { ArrowDown, Bell, Switch } from '@element-plus/icons-vue'

interface Props {
  /** 品牌名 */
  brand?: string
  /** 店名/身份文案（不用 #store 插槽时展示） */
  storeName?: string
  /** 头像占位字符 */
  avatarText?: string
}

withDefaults(defineProps<Props>(), {
  brand: '仓储云',
  storeName: '',
  avatarText: 'U',
})

const emit = defineEmits<{
  (e: 'switch-role'): void
  (e: 'profile-command', command: string): void
}>()

const onProfileCommand = (command: string | number | object) => {
  emit('profile-command', String(command))
}
</script>

<template>
  <header class="cc-topbar">
    <div class="cc-topbar__left">
      <span class="cc-topbar__brand">{{ brand }}</span>
      <span class="cc-topbar__divider">·</span>
      <slot name="store">
        <span class="cc-topbar__store">{{ storeName }}</span>
      </slot>
    </div>

    <div class="cc-topbar__right">
      <el-button text class="cc-topbar__switch" title="切换角色" @click="emit('switch-role')">
        <el-icon><Switch /></el-icon>
        <span class="cc-topbar__switch-text">切换角色</span>
      </el-button>
      <slot name="bell">
        <el-button text :icon="Bell" class="cc-topbar__bell" />
      </slot>
      <el-dropdown trigger="click" @command="onProfileCommand">
        <span class="cc-topbar__user">
          <el-avatar :size="28">{{ avatarText }}</el-avatar>
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
</template>

<style scoped>
.cc-topbar {
  height: 56px;
  background: var(--color-brand-primary);
  color: var(--color-brand-primary-on);
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 0 var(--space-6);
  position: sticky;
  top: 0;
  z-index: var(--z-fixed);
  box-shadow: var(--shadow-base);
}

/* 左区：允许收缩、单行、溢出隐藏 —— 根治品牌纵排竖字 */
.cc-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
}
.cc-topbar__left :slotted(*) {
  min-width: 0;
}
.cc-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
  flex-shrink: 0;
}
.cc-topbar__divider {
  opacity: 0.5;
  flex-shrink: 0;
}
.cc-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 右区：永不收缩 —— 根治按钮/头像被挤出顶栏 */
.cc-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}
.cc-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.cc-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.cc-topbar__switch :deep(.el-icon) {
  margin-right: 4px;
}
.cc-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.cc-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
}

/* ===== 窄屏收敛（WA 端已验证规则 + 375 加严） ===== */
@media (max-width: 768px) {
  .cc-topbar {
    padding: 0 var(--space-4);
  }
}
@media (max-width: 480px) {
  .cc-topbar {
    padding: 0 var(--space-3);
    gap: var(--space-2);
  }
  /* 「切换角色」只留图标（title 提示），通知铃为占位入口先收起，给店名让位 */
  .cc-topbar__switch-text {
    display: none;
  }
  .cc-topbar__switch :deep(.el-icon) {
    margin-right: 0;
  }
  .cc-topbar__right :deep(.cc-topbar__bell) {
    display: none;
  }
  .cc-topbar__user {
    padding: 0;
  }
}
</style>
