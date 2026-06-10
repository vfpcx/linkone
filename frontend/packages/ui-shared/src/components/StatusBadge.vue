<script setup lang="ts">
/**
 * 状态徽章 · StatusBadge
 *
 * 视觉规范（MASTER §4.6）：
 *  - 圆点 + 文字 + 配色 三冗余（WCAG）
 *  - 6 种语义：default / progress / success / warning / danger / archived
 *
 * 用法：<StatusBadge variant="warning" text="待审批" />
 */

interface Props {
  variant?: 'default' | 'progress' | 'success' | 'warning' | 'danger' | 'archived'
  text: string
  /** 是否显示圆点 */
  dot?: boolean
  /** 紧凑模式 */
  size?: 'sm' | 'base'
}

withDefaults(defineProps<Props>(), {
  variant: 'default',
  dot: true,
  size: 'base',
})
</script>

<template>
  <span class="cc-badge" :class="[`cc-badge--${variant}`, `cc-badge--${size}`]">
    <span v-if="dot" class="cc-badge__dot" />
    <span class="cc-badge__text">{{ text }}</span>
  </span>
</template>

<style scoped>
.cc-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  white-space: nowrap;
}
.cc-badge--sm {
  padding: 0 6px;
  font-size: 11px;
}

.cc-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

/* 6 种语义 */
.cc-badge--default {
  background: var(--color-bg-3);
  color: var(--color-fg-2);
}
.cc-badge--progress {
  background: var(--color-info-bg);
  color: #1d4ed8;
}
.cc-badge--success {
  background: var(--color-success-bg);
  color: #065f46;
}
.cc-badge--warning {
  background: var(--color-warning-bg);
  color: #92400e;
}
.cc-badge--danger {
  background: var(--color-danger-bg);
  color: #991b1b;
}
.cc-badge--archived {
  background: #e2e8f0;
  color: var(--color-fg-3);
}
</style>
