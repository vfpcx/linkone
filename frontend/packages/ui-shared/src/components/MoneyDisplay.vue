<script setup lang="ts">
/**
 * 金额组件 · MoneyDisplay
 *
 * 视觉规范（MASTER §4.8）：
 *  - 整数粗 (700)、小数弱 (400 + 0.85em)
 *  - 千分位 ,
 *  - tabular-nums（等宽数字）
 *  - 负数（冲销条目）红色 + 前置 -
 *
 * 用法：<MoneyDisplay :value="2340.56" />  →  ¥ 2,340.56
 */

import { computed } from 'vue'

interface Props {
  /** 数值（建议传 number，避免后端 string） */
  value: number | string | null | undefined
  /** 货币符号 */
  currency?: string
  /** 小数位数 */
  decimals?: number
  /** 是否显示符号 */
  showSign?: boolean
  /** 紧凑模式（小程序/小屏） */
  size?: 'sm' | 'base' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  currency: '¥',
  decimals: 2,
  showSign: false,
  size: 'base',
})

const parsed = computed(() => {
  const raw = props.value
  if (raw === null || raw === undefined || raw === '') return null
  const n = typeof raw === 'string' ? Number(raw) : raw
  if (Number.isNaN(n)) return null
  return n
})

const isNegative = computed(() => (parsed.value ?? 0) < 0)

const parts = computed(() => {
  if (parsed.value === null) return { int: '—', dec: '' }
  const abs = Math.abs(parsed.value)
  const fixed = abs.toFixed(props.decimals)
  const [int, dec] = fixed.split('.')
  // 千分位
  const intWithComma = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return { int: intWithComma, dec: dec ?? '' }
})
</script>

<template>
  <span
    class="cc-money"
    :class="[`cc-money--${size}`, isNegative && 'cc-money--negative']"
  >
    <span v-if="parsed === null" class="cc-money__placeholder">—</span>
    <template v-else>
      <span v-if="isNegative || showSign" class="cc-money__sign">{{
        isNegative ? '-' : '+'
      }}</span>
      <span class="cc-money__currency">{{ currency }}</span>
      <span class="cc-money__int">{{ parts.int }}</span>
      <template v-if="parts.dec">
        <span class="cc-money__dot">.</span>
        <span class="cc-money__dec">{{ parts.dec }}</span>
      </template>
    </template>
  </span>
</template>

<style scoped>
.cc-money {
  display: inline-flex;
  align-items: baseline;
  font-family: var(--font-family-mono);
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
  color: var(--color-fg-1);
  white-space: nowrap;
}

.cc-money--sm {
  font-size: 12px;
}
.cc-money--base {
  font-size: 14px;
}
.cc-money--lg {
  font-size: 22px;
}

.cc-money--negative {
  color: var(--color-danger);
}

.cc-money__currency {
  font-weight: var(--font-weight-medium);
  margin-right: 2px;
}

.cc-money__int {
  font-weight: var(--font-weight-bold);
}

.cc-money__dot,
.cc-money__dec {
  font-weight: var(--font-weight-regular);
  font-size: 0.85em;
  opacity: 0.92;
}

.cc-money__sign {
  font-weight: var(--font-weight-medium);
  margin-right: 1px;
}

.cc-money__placeholder {
  color: var(--color-fg-4);
  font-family: var(--font-family-sans);
}
</style>
