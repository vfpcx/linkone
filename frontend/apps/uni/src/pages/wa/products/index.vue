<script setup lang="ts">
// WA/WE 我的商品（F3 · US-WE-01）：本批发商 SKU 列表 + 上下架切换（买家店铺可见性）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { Sku } from '@cangchu/api-types'
import { skuApi } from '../../../api/sku'
import { fmtMoney } from '../../../utils/format'
import { hasWaScope, readWork, type WaWork } from '../../../utils/session'

const work = ref<WaWork | null>(null)
const list = ref<Sku[]>([])
const loading = ref(true)
const togglingId = ref('')

const sorted = computed(() =>
  [...list.value].sort((a, b) => {
    if (a.listed !== b.listed) return a.listed ? -1 : 1
    return String(a.id).localeCompare(String(b.id))
  }),
)

function onTapToggle(sku: Sku): void {
  if (togglingId.value) return
  const target = !sku.listed
  uni.showModal({
    title: target ? '确认上架' : '确认下架',
    content: target
      ? `「${sku.name}」上架后，买家店铺将可浏览该商品`
      : `「${sku.name}」下架后买家店铺不再展示，已入仓库存不受影响`,
    confirmColor: target ? '#059669' : '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      togglingId.value = String(sku.id)
      try {
        const updated = await skuApi.toggleListing(sku.id, target)
        const hit = list.value.find((s) => String(s.id) === String(updated.id))
        if (hit) hit.listed = updated.listed
        uni.showToast({ title: updated.listed ? '已上架' : '已下架', icon: 'success' })
      } catch {
        /* request 已 toast */
      } finally {
        togglingId.value = ''
      }
    },
  })
}

async function load(): Promise<void> {
  if (!work.value?.wholesalerId) return
  loading.value = true
  try {
    list.value = await skuApi.listMine(work.value.wholesalerId)
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
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
    <view v-else-if="!list.length" class="hint">
      <text class="hint__t">暂无商品档案。商品（SKU）由仓库管理员在电脑端录入，录入后即可在此上下架管理</text>
    </view>

    <view v-else class="list">
      <view v-for="sku in sorted" :key="String(sku.id)" class="card">
        <view class="card__head">
          <view class="card__main">
            <view class="card__name-line">
              <text v-if="!sku.listed" class="badge badge--off">已下架</text>
              <text v-else class="badge badge--on">在售</text>
              <text class="card__name">{{ sku.name }}</text>
            </view>
            <text v-if="sku.spec" class="card__spec">{{ sku.spec }}</text>
            <view class="card__price">
              <text class="card__unit">{{ fmtMoney(sku.unitPrice) }}/件</text>
              <text v-if="sku.moqQty" class="card__moq">满 {{ sku.moqQty }} 件 {{ fmtMoney(sku.moqPrice ?? 0) }}</text>
            </view>
          </view>
          <view class="switch" :class="{ 'switch--on': sku.listed }" @click="onTapToggle(sku)">
            <view class="switch__dot" />
          </view>
        </view>
        <view class="card__tip">{{ sku.listed ? '买家可见，可被询价' : '买家不可见' }}</view>
      </view>
      <view v-if="togglingId" class="more"><text class="more__t">处理中…</text></view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.hint {
  padding: 140rpx 40rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-4;
    line-height: 1.7;
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

  &__spec {
    font-size: 23rpx;
    color: $cc-fg-3;
  }

  &__price {
    display: flex;
    align-items: baseline;
    gap: 16rpx;
    margin-top: 4rpx;
  }

  &__unit {
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-danger;
  }

  &__moq {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__tip {
    margin-top: 16rpx;
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

/* 自绘开关（点击先确认再切，避免原生 switch 闪烁） */
.switch {
  flex-shrink: 0;
  width: 88rpx;
  height: 52rpx;
  border-radius: 999rpx;
  background: $cc-border-2;
  position: relative;
  transition: background 0.2s;
  margin-top: 6rpx;

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

.more {
  padding: 20rpx;
  text-align: center;

  &__t {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}
</style>
