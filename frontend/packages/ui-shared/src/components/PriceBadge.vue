<script setup lang="ts">
/**
 * 价格徽章 · PriceBadge
 *
 * 视觉规范（MASTER §4.9）：
 *  - 公开价：默认色，无标签
 *  - 专属价：金额前置 ✓ 图标 + 横放绿色"专属优惠"小 Badge
 *  - 起批价：小字提示「起批 ≥ {minQty}」
 *
 * 用法：
 *   <PriceBadge :value="120" />                        公开价
 *   <PriceBadge :value="110" source="CUSTOMER" />      专属价
 *   <PriceBadge :value="100" :min-qty="50" />          公开价 + 起批
 */

import MoneyDisplay from './MoneyDisplay.vue'

interface Props {
  value: number
  /** 价格来源 */
  source?: 'PUBLIC' | 'WHOLESALE' | 'CUSTOMER'
  minQty?: number
  unit?: string
  size?: 'sm' | 'base' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  source: 'PUBLIC',
  minQty: undefined,
  unit: '',
  size: 'base',
})

const isExclusive = props.source === 'CUSTOMER'
</script>

<template>
  <span class="cc-price" :class="{ 'cc-price--exclusive': isExclusive }">
    <span v-if="isExclusive" class="cc-price__check" aria-hidden="true">✓</span>
    <MoneyDisplay :value="value" :size="size" />
    <span v-if="unit" class="cc-price__unit">/{{ unit }}</span>
    <span v-if="isExclusive" class="cc-price__tag">专属优惠</span>
    <span v-if="minQty" class="cc-price__minqty">起批 ≥ {{ minQty }}</span>
  </span>
</template>

<style scoped>
.cc-price {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.cc-price__check {
  color: var(--color-success);
  font-weight: var(--font-weight-bold);
  font-size: 14px;
}

.cc-price__unit {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  margin-left: 2px;
}

.cc-price__tag {
  display: inline-block;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--color-success-bg);
  color: #065f46;
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  line-height: 1.6;
  margin-left: 4px;
}

.cc-price__minqty {
  color: var(--color-fg-4);
  font-size: 11px;
  margin-left: 4px;
}

.cc-price--exclusive {
  color: var(--color-success);
}
</style>
