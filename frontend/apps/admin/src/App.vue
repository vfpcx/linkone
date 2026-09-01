<script setup lang="ts">
// 顶层只渲染 router-view + 全局 dialog（如 WorkspaceSwitcher / LoginAnnouncementDialog）
import WorkspaceSwitcher from '@/views/WorkspaceSwitcher.vue'
import LoginAnnouncementDialog from '@/components/LoginAnnouncementDialog.vue'
</script>

<template>
  <router-view />
  <WorkspaceSwitcher />
  <LoginAnnouncementDialog />
</template>

<style scoped></style>
