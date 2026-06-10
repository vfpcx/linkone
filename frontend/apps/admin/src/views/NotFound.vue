<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="nf">
    <div class="nf__code">404</div>
    <div class="nf__msg">页面不存在</div>
    <el-button type="primary" @click="router.replace('/login')">返回登录</el-button>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  gap: var(--space-4);
  background: var(--color-bg-2);
}
.nf__code {
  font-size: 96px;
  font-weight: var(--font-weight-bold);
  color: var(--color-brand-primary);
  font-family: var(--font-family-mono);
}
.nf__msg {
  font-size: var(--font-size-h2);
  color: var(--color-fg-3);
}
</style>
