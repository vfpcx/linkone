<script setup lang="ts">
/**
 * 导航计数徽标 · NavCountBadge
 *
 * 视觉规范（MASTER §4.11）：
 *  - 用于导航 / 菜单 / 列表行内的「裸计数」——无被包裹子元素的纯数字。
 *  - 渲染为一枚静态 inline-flex 红色胶囊（pill），随 flex 行垂直居中，
 *    不使用 el-badge 的 .is-fixed 绝对定位上标（那会让裸数字浮到右上角、错位）。
 *  - count <= 0 时不渲染。count > max 时显示 `${max}+`。
 *
 * 何时用 el-badge（不用本组件）：
 *  - 需要作为「上标」浮在被包裹内容（图标/头像/按钮）右上角时，如顶栏铃铛。
 *
 * 用法：<NavCountBadge :count="6" />        → 红胶囊「6」
 *      <NavCountBadge :count="120" />      → 红胶囊「99+」
 *      <NavCountBadge :count="0" />        → 不渲染
 */

import { computed } from 'vue'

interface Props {
  /** 计数值；<= 0 时不渲染 */
  count: number
  /** 溢出上限，超过显示 `${max}+`，默认 99 */
  max?: number
}

const props = withDefaults(defineProps<Props>(), {
  max: 99,
})

const visible = computed(() => props.count > 0)
const display = computed(() =>
  props.count > props.max ? `${props.max}+` : String(props.count),
)
</script>

<template>
  <span v-if="visible" class="cc-navcount" :title="String(count)">{{ display }}</span>
</template>

<style scoped>
.cc-navcount {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--color-danger);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  font-weight: var(--font-weight-semibold);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
