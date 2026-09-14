<script setup lang="ts">
// WA/WE 新建 / 编辑聚合商品（P6）。
// 创建 = 名称 + 两级品类（平台字典同源）+ 规格模板（≤5 维 · 单维 ≤20 取值 · 组合 ≤60）；
// 编辑 = partial 更新（模板非空整体替换，不影响已生成 SKU 的 spec_key）。
// 聚合 SPU 的 SKU 一律由「批量生成」产生，不能手动挂接（50737）。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { SnowflakeId, SpuCategoryGroup } from '@cangchu/api-types'
import { spuApi } from '../../../api/spu'
import { hasWaScope, readWork } from '../../../utils/session'

interface DimDraft {
  name: string
  options: string[]
  input: string
}

const MAX_DIMS = 5
const MAX_OPTIONS = 20
const MAX_COMBOS = 60

const work = readWork()
const editId = ref<SnowflakeId | null>(null)
const isEdit = computed(() => editId.value !== null)
const submitting = ref(false)

// ===== 基础信息 =====
const name = ref('')
const brand = ref('')
const note = ref('')
const catGroups = ref<SpuCategoryGroup[]>([])
const l1Index = ref<number | null>(null)
const l2Index = ref<number | null>(null)

const l1s = computed(() => catGroups.value.map((g) => g.l1))
const l2s = computed(() => (l1Index.value === null ? [] : catGroups.value[l1Index.value]?.l2s ?? []))

const catL1 = computed(() => (l1Index.value === null ? '' : l1s.value[l1Index.value] ?? ''))
const catL2 = computed(() => (l2Index.value === null ? '' : l2s.value[l2Index.value] ?? ''))

// ===== 规格模板 =====
const dims = ref<DimDraft[]>([emptyDim()])

function emptyDim(): DimDraft {
  return { name: '', options: [], input: '' }
}

const specHint = computed(() => {
  const valid = dims.value.filter((d) => d.options.length > 0)
  if (!valid.length) return ''
  const n = valid.reduce((acc, d) => acc * d.options.length, 1)
  return `按当前模板将生成 ${n} 个规格商品（上限 ${MAX_COMBOS}）`
})

function addDim(): void {
  if (dims.value.length >= MAX_DIMS) {
    uni.showToast({ title: `最多 ${MAX_DIMS} 个规格维度`, icon: 'none' })
    return
  }
  dims.value.push(emptyDim())
}

function removeDim(i: number): void {
  dims.value.splice(i, 1)
  if (!dims.value.length) dims.value.push(emptyDim())
}

function addOption(i: number): void {
  const d = dims.value[i]
  const v = d.input.trim()
  if (!v) return
  if (d.options.includes(v)) {
    uni.showToast({ title: '取值已存在', icon: 'none' })
    return
  }
  if (d.options.length >= MAX_OPTIONS) {
    uni.showToast({ title: `单维度最多 ${MAX_OPTIONS} 个取值`, icon: 'none' })
    return
  }
  d.options.push(v)
  d.input = ''
}

function removeOption(i: number, j: number): void {
  dims.value[i].options.splice(j, 1)
}

// ===== 品类选择 =====
function onL1Change(e: { detail: { value: number } }): void {
  l1Index.value = Number(e.detail.value)
  l2Index.value = null
}

function onL2Change(e: { detail: { value: number } }): void {
  l2Index.value = Number(e.detail.value)
}

// ===== 校验与提交 =====
function validate(): boolean {
  if (!name.value.trim()) {
    uni.showToast({ title: '请输入聚合商品名称', icon: 'none' })
    return false
  }
  if (!catL1.value || !catL2.value) {
    uni.showToast({ title: '请选择两级品类', icon: 'none' })
    return false
  }
  const seen = new Set<string>()
  for (let i = 0; i < dims.value.length; i += 1) {
    const d = dims.value[i]
    const n = i + 1
    const nm = d.name.trim()
    if (!nm) {
      uni.showToast({ title: `第 ${n} 个规格维度请填名称（如：包装）`, icon: 'none' })
      return false
    }
    if (seen.has(nm)) {
      uni.showToast({ title: `规格维度「${nm}」重复`, icon: 'none' })
      return false
    }
    seen.add(nm)
    if (!d.options.length) {
      uni.showToast({ title: `维度「${nm}」至少 1 个取值`, icon: 'none' })
      return false
    }
  }
  const combos = dims.value.filter((d) => d.options.length).reduce((acc, d) => acc * d.options.length, 1)
  if (combos > MAX_COMBOS) {
    uni.showToast({ title: `组合数 ${combos} 超上限 ${MAX_COMBOS}，请精简取值`, icon: 'none' })
    return false
  }
  return true
}

async function doSubmit(): Promise<void> {
  if (submitting.value) return
  if (!validate()) return
  const wh = work?.wholesalerId
  if (!work) {
    uni.reLaunch({ url: '/pages/wa/login/index' })
    return
  }
  if (!isEdit.value && !wh) {
    uni.showToast({ title: '未找到商户信息，请重新登录', icon: 'none' })
    return
  }

  const schema = dims.value
    .filter((d) => d.options.length)
    .map((d) => ({ name: d.name.trim(), options: d.options.slice() }))
  const body = {
    name: name.value.trim(),
    categoryL1: catL1.value,
    categoryL2: catL2.value,
    brand: brand.value.trim() || undefined,
    note: note.value.trim() || undefined,
    specSchema: schema,
  }

  submitting.value = true
  try {
    if (isEdit.value && editId.value != null) {
      await spuApi.update(editId.value, body)
      uni.showToast({ title: '已保存', icon: 'success' })
    } else {
      await spuApi.create(wh as SnowflakeId, body)
      uni.showToast({ title: '已创建，可去详情页批量生成规格商品', icon: 'none' })
    }
    setTimeout(() => uni.navigateBack(), 700)
  } catch {
    /* request 已 toast（50730-50732 等） */
  } finally {
    submitting.value = false
  }
}

// ===== 初始化 =====

async function init(options: Record<string, string>): Promise<void> {
  if (!hasWaScope() || !work) {
    uni.reLaunch({ url: '/pages/wa/login/index' })
    return
  }
  try {
    const groups = await spuApi.categories()
    catGroups.value = groups
  } catch {
    /* request 已 toast */
  }
  // 编辑模式：详情预填（partial：未改字段回传原值等价保持）
  if (options.id) {
    editId.value = String(options.id) as SnowflakeId
    try {
      const s = await spuApi.detail(editId.value)
      name.value = s.name
      brand.value = s.brand ?? ''
      note.value = s.note ?? ''
      l1Index.value = l1s.value.indexOf(s.categoryL1)
      if (l1Index.value >= 0) {
        l2Index.value = l2s.value.indexOf(s.categoryL2)
      }
      dims.value = s.specSchema.map((d) => ({ name: d.name, options: d.options.slice(), input: '' }))
    } catch {
      /* request 已 toast（50735 等） */
    }
  }
}

onLoad((options) => {
  void init((options ?? {}) as unknown as Record<string, string>)
})
</script>

<template>
  <view class="page">
    <view class="banner">
      {{ isEdit ? '编辑聚合商品：修改保存后不影响已生成规格商品的规格与价格；模板变更后新组合以新模板为准。' : '聚合商品 = 一个商品 + 规格模板（如 包装 × 容量）。创建后可在详情页按模板一次生成全部规格商品。' }}
    </view>

    <!-- 基础信息 -->
    <view class="card">
      <view class="card__title">基础信息</view>
      <view class="form">
        <view class="form__row">
          <text class="form__label">商品名称</text>
          <input v-model="name" class="form__input" type="text" placeholder="必填（≤128）" placeholder-class="ph" maxlength="128" />
        </view>
        <view class="form__row">
          <text class="form__label">一级品类</text>
          <picker mode="selector" :range="l1s" :value="l1Index ?? 0" @change="onL1Change">
            <view class="form__picker" :class="{ ph: !catL1 }">{{ catL1 || '请选择' }}</view>
          </picker>
        </view>
        <view class="form__row">
          <text class="form__label">二级品类</text>
          <picker mode="selector" :range="l2s" :value="l2Index ?? 0" :disabled="l1Index === null" @change="onL2Change">
            <view class="form__picker" :class="{ ph: !catL2 }">{{ catL2 || (l1Index === null ? '先选一级品类' : '请选择') }}</view>
          </picker>
        </view>
        <view class="form__row">
          <text class="form__label">品牌</text>
          <input v-model="brand" class="form__input" type="text" placeholder="可空（≤64）" placeholder-class="ph" maxlength="64" />
        </view>
        <view class="form__row">
          <text class="form__label">备注</text>
          <input v-model="note" class="form__input" type="text" placeholder="可空（≤256）" placeholder-class="ph" maxlength="256" />
        </view>
      </view>
    </view>

    <!-- 规格模板 -->
    <view class="card">
      <view class="card__title-wrap">
        <text class="card__title">规格模板</text>
        <text class="card__limit">{{ dims.length }}/{{ MAX_DIMS }} 维</text>
      </view>
      <view v-for="(d, i) in dims" :key="i" class="dim">
        <view class="dim__head">
          <input v-model="d.name" class="dim__name" type="text" :placeholder="`维度 ${i + 1} 名称，如：包装`" placeholder-class="ph" maxlength="32" />
          <text v-if="dims.length > 1" class="dim__del" @click="removeDim(i)">删除</text>
        </view>
        <view class="dim__options">
          <view v-for="(o, j) in d.options" :key="o" class="tag">
            <text class="tag__t">{{ o }}</text>
            <text class="tag__x" @click="removeOption(i, j)">✕</text>
          </view>
          <view v-if="d.options.length < MAX_OPTIONS" class="tag-add">
            <input
              v-model="d.input"
              class="tag-add__input"
              type="text"
              placeholder="输入取值后添加"
              placeholder-class="ph"
              maxlength="32"
              @confirm="addOption(i)"
            />
            <text class="tag-add__btn" @click="addOption(i)">添加</text>
          </view>
        </view>
        <text class="dim__count">取值 {{ d.options.length }}/{{ MAX_OPTIONS }}</text>
      </view>
      <view class="dim-add" @click="addDim">
        <text class="dim-add__t">＋ 添加规格维度</text>
      </view>
      <view v-if="specHint" class="tip tip--info">{{ specHint }}</view>
    </view>

    <button class="btn-primary submit" :disabled="submitting" :loading="submitting" @click="doSubmit">
      {{ isEdit ? '保存修改' : '创建聚合商品' }}
    </button>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.banner {
  margin-bottom: 20rpx;
  padding: 14rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-info-bg;
  font-size: 22rpx;
  color: $cc-fg-3;
  line-height: 1.6;
}

.card {
  margin-bottom: 22rpx;
  padding: 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__title-wrap {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8rpx;
  }

  &__title {
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__limit {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

.form {
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16rpx 0;
    border-bottom: 2rpx solid $cc-bg-2;

    &:last-child {
      border-bottom: none;
    }
  }

  &__label {
    font-size: 25rpx;
    color: $cc-fg-3;
  }

  &__input {
    width: 58%;
    text-align: right;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__picker {
    width: 58%;
    text-align: right;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.dim {
  padding: 20rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &__head {
    display: flex;
    align-items: center;
    gap: 16rpx;
  }

  &__name {
    flex: 1;
    font-size: 27rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__del {
    padding: 4rpx 16rpx;
    border-radius: 999rpx;
    background: $cc-danger-bg;
    color: $cc-danger;
    font-size: 21rpx;
  }

  &__options {
    display: flex;
    flex-wrap: wrap;
    gap: 12rpx;
    margin-top: 16rpx;
  }

  &__count {
    display: block;
    margin-top: 12rpx;
    font-size: 20rpx;
    color: $cc-fg-4;
  }
}

.tag {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  background: $cc-bg-2;

  &__t {
    font-size: 24rpx;
    color: $cc-fg-1;
  }

  &__x {
    font-size: 20rpx;
    color: $cc-fg-4;
    padding: 0 4rpx;
  }
}

.tag-add {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 6rpx 8rpx 6rpx 16rpx;
  border-radius: 999rpx;
  border: 2rpx dashed $cc-border-2;
  flex: 1;
  min-width: 280rpx;

  &__input {
    flex: 1;
    font-size: 24rpx;
    color: $cc-fg-1;
  }

  &__btn {
    flex-shrink: 0;
    padding: 4rpx 18rpx;
    border-radius: 999rpx;
    background: $cc-accent;
    color: #fff;
    font-size: 22rpx;
  }
}

.dim-add {
  padding: 22rpx 0 6rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-accent;
  }
}

.tip {
  margin-top: 14rpx;
  padding: 12rpx 16rpx;
  border-radius: 10rpx;
  font-size: 21rpx;
  line-height: 1.6;

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }
}

.submit {
  margin-top: 10rpx;
  height: 96rpx;
  font-size: 31rpx;
  font-weight: 600;

  &[disabled] {
    opacity: 0.5;
  }
}

.ph {
  color: $cc-fg-4;
  font-weight: 400;
}
</style>
