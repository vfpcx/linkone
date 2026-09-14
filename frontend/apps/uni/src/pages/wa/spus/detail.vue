<script setup lang="ts">
// WA/WE 聚合商品详情（P6）：规格模板展示 + 按模板批量生成 SKU（完整笛卡尔积 + 默认价）+ 下架。
// 逐组合覆盖价格（items 非空模式）后端已支持，移动端首版只出完整笛卡尔积（progress 记未做）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import type { MerchantSpu, SnowflakeId } from '@cangchu/api-types'
import { spuApi } from '../../../api/spu'
import { hasWaScope } from '../../../utils/session'

const spu = ref<MerchantSpu | null>(null)
const loading = ref(true)
const offlining = ref(false)

// ===== 生成 SKU 弹层 =====
const genOpen = ref(false)
const genSubmitting = ref(false)
const genPrice = ref('')
const genMoqPrice = ref('')
const genMoqQty = ref('1')
const genListed = ref(true)

const isActive = computed(() => spu.value?.status === 'ACTIVE')

/** 模板完整笛卡尔积组合数 */
const comboCount = computed(() => {
  const s = spu.value
  if (!s || !s.specSchema.length) return 0
  return s.specSchema.reduce((acc, d) => acc * Math.max(d.options.length, 1), 1)
})

async function load(id: SnowflakeId): Promise<void> {
  loading.value = true
  try {
    spu.value = await spuApi.detail(id)
  } catch {
    /* request 已 toast（50735 等） */
  } finally {
    loading.value = false
  }
}

function openGen(): void {
  if (!isActive.value) return
  genPrice.value = ''
  genMoqPrice.value = ''
  genMoqQty.value = '1'
  genListed.value = true
  genOpen.value = true
}

async function doGenerate(): Promise<void> {
  const s = spu.value
  if (!s || genSubmitting.value) return
  const price = Number(genPrice.value)
  if (!genPrice.value.trim() || !Number.isFinite(price) || price <= 0) {
    uni.showToast({ title: '请输入默认单价（>0）', icon: 'none' })
    return
  }
  const moqQty = Number(genMoqQty.value)
  if (!Number.isFinite(moqQty) || moqQty < 1 || !Number.isInteger(moqQty)) {
    uni.showToast({ title: '起批量需为 ≥1 整数', icon: 'none' })
    return
  }
  const moqPrice = genMoqPrice.value.trim()
  if (moqPrice && (!Number.isFinite(Number(moqPrice)) || Number(moqPrice) < 0)) {
    uni.showToast({ title: '起批价需 ≥0', icon: 'none' })
    return
  }
  uni.showModal({
    title: `确认生成 ${comboCount.value} 个规格商品`,
    content: '按模板完整组合生成，未覆盖的组合与已生成的重复时整单失败（需先处理存量）。',
    confirmText: '确认生成',
    confirmColor: '#4F46E5',
    success: async (r) => {
      if (!r.confirm) return
      genSubmitting.value = true
      try {
        const created = await spuApi.generateSkus(s.id, {
          unitPrice: price,
          moqQty,
          moqPrice: moqPrice === '' ? undefined : Number(moqPrice),
          listed: genListed.value,
        })
        genOpen.value = false
        uni.showToast({ title: `已生成 ${created.length} 个规格商品`, icon: 'success' })
        await load(s.id)
      } catch {
        /* request 已 toast（50733 组合重复等） */
      } finally {
        genSubmitting.value = false
      }
    },
  })
}

function onTapOffline(): void {
  const s = spu.value
  if (!s || offlining.value) return
  uni.showModal({
    title: '确认下架聚合商品',
    content: `「${s.name}」下架后将级联下架其全部在售规格商品；已入仓库存与单据不受影响。下架后不可恢复上架。`,
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      offlining.value = true
      try {
        await spuApi.offline(s.id)
        uni.showToast({ title: '已下架', icon: 'success' })
        await load(s.id)
      } catch {
        /* request 已 toast（50722 等） */
      } finally {
        offlining.value = false
      }
    },
  })
}

function goEdit(): void {
  const s = spu.value
  if (!s || !isActive.value) return
  uni.navigateTo({ url: `/pages/wa/spus/create?id=${String(s.id)}` })
}

async function init(options: Record<string, string>): Promise<void> {
  if (!hasWaScope()) {
    uni.reLaunch({ url: '/pages/wa/login/index' })
    return
  }
  if (!options.id) {
    uni.showToast({ title: '缺少商品参数', icon: 'none' })
    setTimeout(() => uni.navigateBack(), 600)
    return
  }
  await load(String(options.id) as SnowflakeId)
}

onLoad((options) => {
  void init((options ?? {}) as unknown as Record<string, string>)
})

onPullDownRefresh(async () => {
  const id = spu.value?.id
  if (id != null) await load(id)
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view v-if="loading && !spu" class="hint"><text class="hint__t">加载中…</text></view>

    <template v-else-if="spu">
      <view v-if="!isActive" class="banner banner--off">该聚合商品已下架，其规格商品已全部下架（买家不可见），不可再编辑或生成。</view>

      <!-- 基础信息 -->
      <view class="card">
        <view class="card__head">
          <text class="badge" :class="isActive ? 'badge--on' : 'badge--off'">{{ isActive ? '在架' : '已下架' }}</text>
          <text class="card__name">{{ spu.name }}</text>
        </view>
        <view class="kv">
          <text class="kv__k">编码</text>
          <text class="kv__v">{{ spu.spuCode }}</text>
        </view>
        <view class="kv">
          <text class="kv__k">品类</text>
          <text class="kv__v">{{ spu.categoryL1 }} / {{ spu.categoryL2 }}</text>
        </view>
        <view v-if="spu.brand" class="kv">
          <text class="kv__k">品牌</text>
          <text class="kv__v">{{ spu.brand }}</text>
        </view>
        <view v-if="spu.note" class="kv">
          <text class="kv__k">备注</text>
          <text class="kv__v">{{ spu.note }}</text>
        </view>
        <view class="kv">
          <text class="kv__k">已生成规格商品</text>
          <text class="kv__v">{{ spu.referencedSkuCount }} 个</text>
        </view>
      </view>

      <!-- 规格模板 -->
      <view class="card">
        <view class="card__title-wrap">
          <text class="card__title">规格模板</text>
          <text class="card__limit">{{ spu.specSchema.length }} 维 · {{ comboCount }} 组合</text>
        </view>
        <view v-for="d in spu.specSchema" :key="d.name" class="dim">
          <text class="dim__name">{{ d.name }}</text>
          <view class="dim__options">
            <view v-for="o in d.options" :key="o" class="tag"><text class="tag__t">{{ o }}</text></view>
          </view>
        </view>
        <view class="tip">组合示例：{{ spu.specSchema.map((d) => d.options[0]).join(' / ') || '—' }}；生成后每个组合一个规格商品，在「我的商品」逐个上下架。</view>
      </view>

      <!-- 操作 -->
      <view v-if="isActive" class="ops">
        <button class="btn-primary ops__gen" :loading="genSubmitting" @click="openGen">按模板生成规格商品</button>
        <view class="ops__row">
          <button class="btn-ghost ops__edit" @click="goEdit">编辑信息 / 模板</button>
          <button class="btn-ghost ops__off" :loading="offlining" @click="onTapOffline">下架</button>
        </view>
      </view>
    </template>

    <!-- 生成弹层 -->
    <view v-if="genOpen" class="mask" @click="genOpen = false">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">生成 {{ comboCount }} 个规格商品</text>
          <text class="sheet__close" @click="genOpen = false">✕</text>
        </view>
        <view class="sheet__body">
          <view class="form">
            <view class="form__row">
              <text class="form__label">默认单价（元）</text>
              <input v-model="genPrice" class="form__input" type="digit" placeholder="必填 >0" placeholder-class="ph" />
            </view>
            <view class="form__row">
              <text class="form__label">起批量（件）</text>
              <input v-model="genMoqQty" class="form__input" type="number" placeholder="缺省 1" placeholder-class="ph" />
            </view>
            <view class="form__row">
              <text class="form__label">起批价（元）</text>
              <input v-model="genMoqPrice" class="form__input" type="digit" placeholder="可空 ≥0" placeholder-class="ph" />
            </view>
            <view class="form__row">
              <text class="form__label">生成后上架</text>
              <view class="switch" :class="{ 'switch--on': genListed }" @click="genListed = !genListed">
                <view class="switch__dot" />
              </view>
            </view>
          </view>
          <view class="tip tip--info">按模板完整组合生成，全部使用上述默认价；不同规格需各自定价请生成后到「我的商品」联系仓库调整，或在电脑端逐组合生成。</view>
          <button class="btn-primary gen-btn" :disabled="genSubmitting" :loading="genSubmitting" @click="doGenerate">确认生成</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.hint {
  padding: 140rpx 40rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-4;
  }
}

.banner {
  margin-bottom: 20rpx;
  padding: 14rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-info-bg;
  font-size: 22rpx;
  color: $cc-fg-3;
  line-height: 1.6;

  &--off {
    background: $cc-bg-3;
    color: $cc-fg-4;
  }
}

.card {
  margin-bottom: 22rpx;
  padding: 26rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12rpx;
    margin-bottom: 10rpx;
  }

  &__name {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

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

.kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
  padding: 12rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &:last-child {
    border-bottom: none;
  }

  &__k {
    flex-shrink: 0;
    font-size: 24rpx;
    color: $cc-fg-4;
  }

  &__v {
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-fg-1;
    text-align: right;
    word-break: break-all;
  }
}

.dim {
  padding: 18rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &:last-of-type {
    border-bottom: none;
  }

  &__name {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__options {
    display: flex;
    flex-wrap: wrap;
    gap: 12rpx;
    margin-top: 12rpx;
  }
}

.tag {
  padding: 6rpx 18rpx;
  border-radius: 999rpx;
  background: $cc-bg-2;

  &__t {
    font-size: 24rpx;
    color: $cc-fg-1;
  }
}

.badge {
  font-size: 18rpx;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;

  &--on {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--off {
    background: $cc-bg-3;
    color: $cc-fg-4;
  }
}

.tip {
  margin-top: 14rpx;
  padding: 12rpx 16rpx;
  border-radius: 10rpx;
  background: $cc-bg-3;
  font-size: 21rpx;
  color: $cc-fg-4;
  line-height: 1.6;

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }
}

.ops {
  display: flex;
  flex-direction: column;
  gap: 20rpx;

  &__gen {
    height: 96rpx;
    font-size: 31rpx;
    font-weight: 600;
  }

  &__row {
    display: flex;
    gap: 20rpx;
  }

  &__edit,
  &__off {
    flex: 1;
    height: 88rpx;
    font-size: 28rpx;
  }

  &__off {
    color: $cc-danger;
  }
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(2, 6, 23, 0.45);
  z-index: 20;
  display: flex;
  align-items: flex-end;
}

.sheet {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  display: flex;
  flex-direction: column;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 26rpx 28rpx 18rpx;
    border-bottom: 1rpx solid $cc-border-1;
  }

  &__title {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__close {
    font-size: 30rpx;
    color: $cc-fg-4;
    padding: 4rpx 8rpx;
  }

  &__body {
    padding: 24rpx 28rpx calc(40rpx + env(safe-area-inset-bottom));
    box-sizing: border-box;
  }
}

.form {
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18rpx 0;
    border-bottom: 2rpx solid $cc-bg-2;
  }

  &__label {
    font-size: 26rpx;
    color: $cc-fg-3;
  }

  &__input {
    width: 55%;
    text-align: right;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

/* 自绘开关 */
.switch {
  flex-shrink: 0;
  width: 88rpx;
  height: 52rpx;
  border-radius: 999rpx;
  background: $cc-border-2;
  position: relative;
  transition: background 0.2s;

  &__dot {
    position: absolute;
    top: 4rpx;
    left: 4rpx;
    width: 44rpx;
    height: 44rpx;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 2rpx 6rpx rgba(2, 6, 23, 0.2);
    transition: left 0.2s;
  }

  &--on {
    background: $cc-success;

    .switch__dot {
      left: 40rpx;
    }
  }
}

.gen-btn {
  margin-top: 26rpx;
  height: 92rpx;
  font-size: 30rpx;
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
