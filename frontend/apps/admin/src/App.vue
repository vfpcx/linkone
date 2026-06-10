<script setup lang="ts">
// 顶层只渲染 router-view + 全局 dialog（如 WorkspaceSwitcher）
import WorkspaceSwitcher from '@/views/WorkspaceSwitcher.vue'
</script>

<template>
  <router-view />
  <WorkspaceSwitcher />
</template>

<style scoped></style>
