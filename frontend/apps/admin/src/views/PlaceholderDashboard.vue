<script setup lang="ts">
import { useRoute } from 'vue-router'
import { roleLabel } from '@cangchu/ui-shared'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()
</script>

<template>
  <div class="placeholder">
    <h1 class="placeholder__title">{{ route.meta.title }}</h1>
    <p class="placeholder__hint">
      该功能模块正在建设中，敬请期待。
    </p>
    <p class="placeholder__role">当前身份：{{ auth.primaryRole ? roleLabel(auth.primaryRole) : '—' }}</p>
    <el-button @click="$router.push('/login')">返回登录</el-button>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  gap: var(--space-4);
  background: var(--color-bg-2);
}
.placeholder__title {
  font-size: var(--font-size-h1);
  color: var(--color-fg-1);
}
.placeholder__hint {
  color: var(--color-fg-3);
}
.placeholder__role {
  color: var(--color-fg-2);
  font-weight: var(--font-weight-medium);
}
</style>
