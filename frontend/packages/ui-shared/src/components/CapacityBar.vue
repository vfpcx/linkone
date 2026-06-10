<script setup lang="ts">
/**
 * 容量进度条 · CapacityBar
 *
 * 视觉规范（MASTER §4.7）：
 *  - 件数 / 托盘 双维度
 *  - 利用率配色：< 70% 绿 / 70-90% 黄 / > 90% 红
 *  - 数字 tabular-nums，右对齐
 *  - 模糊档位（TIER）显示"余量充足/适中/紧张"标签
 *
 * 用法：<CapacityBar :used="14300" :total="20000" label="件数" />
 */

import { computed } from 'vue'
import { getCapacityColor } from '@cangchu/design-tokens'

interface Props {
  label: string
  unit?: string
  used?: number
  total?: number
  /** 模糊档位（TIER 精度）下展示标签 */
  tier?: 'LOW' | 'MEDIUM' | 'HIGH' | null
  tierLabel?: string
  /** 模式：精确 / 模糊 */
  precision?: 'EXACT' | 'TIER'
}

const props = withDefaults(defineProps<Props>(), {
  unit: '件',
  used: 0,
  total: 0,
  tier: null,
  tierLabel: '',
  precision: 'EXACT',
})

const utilization = computed(() => {
  if (!props.total) return 0
  return Math.min(100, Math.round((props.used / props.total) * 100))
})

const color = computed(() => getCapacityColor(utilization.value))

const remaining = computed(() => Math.max(0, props.total - props.used))

const formattedUsed = computed(() => props.used.toLocaleString('zh-CN'))
const formattedTotal = computed(() => props.total.toLocaleString('zh-CN'))
const formattedRemain = computed(() => remaining.value.toLocaleString('zh-CN'))
</script>

<template>
  <div class="cc-capacity">
    <div class="cc-capacity__head">
      <span class="cc-capacity__label">{{ label }}</span>
      <span v-if="precision === 'EXACT'" class="cc-capacity__pct" :style="{ color }">
        {{ utilization }}%
      </span>
      <span v-else class="cc-capacity__tier">{{ tierLabel || tier }}</span>
    </div>

    <div class="cc-capacity__track">
      <div
        v-if="precision === 'EXACT'"
        class="cc-capacity__fill"
        :style="{ width: `${utilization}%`, background: color }"
      />
      <div
        v-else
        class="cc-capacity__fill"
        :style="{
          width: tier === 'LOW' ? '20%' : tier === 'MEDIUM' ? '55%' : '85%',
          background: color,
        }"
      />
    </div>

    <div v-if="precision === 'EXACT'" class="cc-capacity__detail">
      <span class="cc-capacity__nums">
        {{ formattedUsed }} / {{ formattedTotal }} {{ unit }}
      </span>
      <span class="cc-capacity__remain">剩余 {{ formattedRemain }} {{ unit }}</span>
    </div>
  </div>
</template>

<style scoped>
.cc-capacity {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.cc-capacity__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: var(--font-size-body);
}

.cc-capacity__label {
  color: var(--color-fg-2);
  font-weight: var(--font-weight-medium);
}

.cc-capacity__pct {
  font-family: var(--font-family-mono);
  font-variant-numeric: tabular-nums;
  font-weight: var(--font-weight-bold);
  font-size: var(--font-size-h3);
}

.cc-capacity__tier {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-2);
  font-size: var(--font-size-body);
}

.cc-capacity__track {
  width: 100%;
  height: 8px;
  background: var(--color-bg-3);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.cc-capacity__fill {
  height: 100%;
  border-radius: var(--radius-full);
  transition: width var(--duration-base) var(--easing-standard);
}

.cc-capacity__detail {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-size-caption);
  color: var(--color-fg-3);
}

.cc-capacity__nums {
  font-family: var(--font-family-mono);
  font-variant-numeric: tabular-nums;
}
</style>
