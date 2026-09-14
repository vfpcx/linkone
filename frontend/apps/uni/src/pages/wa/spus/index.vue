<script setup lang="ts">
// WA/WE 自建聚合商品（SPU）列表（P6）：本商户聚合 SPU + 状态概览 + 下架入口。
// 聚合 SPU = 规格模板 + 批量生成的 SKU 集合；单 SKU 上下架仍走「我的商品」。
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { MerchantSpu } from '@cangchu/api-types'
import { spuApi } from '../../../api/spu'
import { hasWaScope, readWork, type WaWork } from '../../../utils/session'

const work = ref<WaWork | null>(null)
const list = ref<MerchantSpu[]>([])
const loading = ref(true)
const offliningId = ref('')

/** 规格摘要：`包装×2 · 规格×3` */
function specSummary(s: MerchantSpu): string {
  return s.specSchema.map((d) => `${d.name}×${d.options.length}`).join(' · ')
}

async function load(): Promise<void> {
  const wh = work.value?.wholesalerId
  if (!wh) return
  loading.value = true
  try {
    list.value = await spuApi.listMine(wh)
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function onTapOffline(s: MerchantSpu): void {
  if (offliningId.value) return
  uni.showModal({
    title: '确认下架聚合商品',
    content: `「${s.name}」下架后将级联下架其全部在售规格商品，买家店铺不再展示；已入仓库存与单据不受影响。下架后不可恢复上架。`,
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      offliningId.value = String(s.id)
      try {
        await spuApi.offline(s.id)
        uni.showToast({ title: '已下架', icon: 'success' })
        await load()
      } catch {
        /* request 已 toast（50722 等） */
      } finally {
        offliningId.value = ''
      }
    },
  })
}

function goDetail(s: MerchantSpu): void {
  uni.navigateTo({ url: `/pages/wa/spus/detail?id=${String(s.id)}` })
}

function goCreate(): void {
  uni.navigateTo({ url: '/pages/wa/spus/create' })
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

onLoad(() => {
  if (!hasWaScope()) goLogin()
})

onShow(() => {
  work.value = readWork()
  if (!hasWaScope() || !work.value) return
  void load()
})

onPullDownRefresh(async () => {
  work.value = readWork()
  await load()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view v-if="loading && !list.length" class="hint"><text class="hint__t">加载中…</text></view>

    <template v-else-if="list.length">
      <view class="head">
        <text class="head__t">共 {{ list.length }} 个聚合商品（在架 {{ list.filter((s) => s.status === 'ACTIVE').length }}）</text>
      </view>
      <view v-for="s in list" :key="String(s.id)" class="card" @click="goDetail(s)">
        <view class="card__head">
          <view class="card__main">
            <view class="card__name-line">
              <text class="badge" :class="s.status === 'ACTIVE' ? 'badge--on' : 'badge--off'">
                {{ s.status === 'ACTIVE' ? '在架' : '已下架' }}
              </text>
              <text class="card__name">{{ s.name }}</text>
            </view>
            <text class="card__code">{{ s.spuCode }} · {{ s.categoryL1 }}/{{ s.categoryL2 }}</text>
            <text class="card__spec">{{ specSummary(s) }}</text>
          </view>
          <text v-if="s.status === 'ACTIVE' && offliningId !== String(s.id)" class="card__off" @click.stop="onTapOffline(s)">下架</text>
          <text v-else-if="offliningId === String(s.id)" class="card__off card__off--busy">处理中…</text>
        </view>
        <view class="card__foot">
          <text class="card__ref">已生成规格商品 {{ s.referencedSkuCount }} 个 · 点击查看详情与生成 ›</text>
        </view>
      </view>
    </template>

    <view v-else class="hint">
      <text class="hint__t">暂无聚合商品。聚合商品 = 一个商品 + 多规格模板（如 包装×容量），一次批量生成全部规格商品</text>
      <button class="btn-primary hint__btn" @click="goCreate">＋ 新建聚合商品</button>
    </view>

    <view v-if="list.length" class="foot">
      <button class="btn-primary foot__btn" @click="goCreate">＋ 新建聚合商品</button>
      <text class="foot__tip">单规格商品（无规格维度）请继续在电脑端由库管录入后，到「我的商品」上下架</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.head {
  padding: 4rpx 8rpx 16rpx;

  &__t {
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}

.hint {
  padding: 140rpx 40rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-4;
    line-height: 1.7;
  }

  &__btn {
    margin-top: 36rpx;
    height: 88rpx;
    font-size: 29rpx;
    font-weight: 600;
  }
}

.card {
  margin-bottom: 20rpx;
  padding: 26rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: flex-start;
    gap: 20rpx;
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8rpx;
  }

  &__name-line {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10rpx;
  }

  &__name {
    font-size: 29rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__code {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__spec {
    font-size: 23rpx;
    color: $cc-fg-3;
  }

  &__off {
    flex-shrink: 0;
    padding: 6rpx 20rpx;
    border-radius: 999rpx;
    background: $cc-danger-bg;
    color: $cc-danger;
    font-size: 22rpx;

    &--busy {
      background: $cc-bg-3;
      color: $cc-fg-4;
    }
  }

  &__foot {
    margin-top: 16rpx;
    padding-top: 14rpx;
    border-top: 2rpx solid $cc-bg-2;
  }

  &__ref {
    font-size: 20rpx;
    color: $cc-fg-4;
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

.foot {
  margin-top: 16rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;

  &__btn {
    height: 92rpx;
    font-size: 30rpx;
    font-weight: 600;
  }

  &__tip {
    font-size: 21rpx;
    color: $cc-fg-4;
    text-align: center;
    line-height: 1.6;
  }
}
</style>
