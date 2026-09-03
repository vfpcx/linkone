<script setup lang="ts">
// WA/WE 客户跟进列表（F3 · US-WE-04）：本仓客户 × 商户行，仅打码号；查全号走独立 reveal 留痕。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import type { WaCustomer } from '@cangchu/api-types'
import { customerApi } from '../../../api/customers'
import { piiApi } from '../../../api/pii'
import { fmtDateTime } from '../../../utils/format'
import { hasWaScope, readWork, type WaWork } from '../../../utils/session'

const SIZE = 20

const work = ref<WaWork | null>(null)
const rows = ref<WaCustomer[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)

const noMore = computed(() => rows.value.length >= Number(total) || rows.value.length === 0)

async function load(reset: boolean): Promise<void> {
  if (!work.value || loading.value) return
  const p = reset ? 1 : page.value
  loading.value = true
  try {
    const data = await customerApi.list({ page: p, size: SIZE })
    total.value = Number(data.total ?? 0)
    rows.value = p === 1 ? data.records ?? [] : rows.value.concat(data.records ?? [])
    page.value = p + 1
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function openDetail(row: WaCustomer): void {
  uni.navigateTo({
    url: `/pages/wa/customers/detail?customerKey=${encodeURIComponent(row.customerKey)}&wholesalerId=${encodeURIComponent(String(row.wholesalerId))}`,
  })
}

function revealPhoneOf(row: WaCustomer): void {
  if (!row.lastInquiryId) return
  piiApi
    .revealPhone('INQUIRY', String(row.lastInquiryId))
    .then(({ phone }) => {
      uni.showModal({
        title: '完整买家电话',
        content: phone,
        confirmText: '复制',
        cancelText: '关闭',
        success: (r) => {
          if (r.confirm) uni.setClipboardData({ data: phone })
        },
      })
    })
    .catch(() => {
      /* 越权/对象不存在 → request 已 toast */
    })
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
  void load(true)
})

onPullDownRefresh(async () => {
  work.value = readWork()
  await load(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => {
  if (!noMore.value) void load(false)
})
</script>

<template>
  <view class="page">
    <view v-if="loading && !rows.length" class="hint"><text class="hint__t">加载中…</text></view>
    <view v-else-if="!rows.length" class="hint">
      <text class="hint__t">暂无客户记录。买家提交过询价或成交后会自动出现在这里，方便你跟进回访</text>
    </view>

    <view v-else class="list">
      <view v-for="row in rows" :key="`${row.customerKey}-${row.wholesalerId}`" class="card" @click="openDetail(row)">
        <view class="card__head">
          <text class="card__phone">{{ row.maskedPhone }}</text>
          <view class="ops">
            <view class="ops__btn ops__btn--ghost" @click.stop="revealPhoneOf(row)">查全号</view>
            <view class="ops__btn ops__btn--main" @click.stop="openDetail(row)">跟进</view>
          </view>
        </view>
        <view class="card__meta">
          <text>{{ row.wholesalerName }}</text>
          <text>询价 {{ row.inquiryCount }} 次</text>
        </view>
        <view class="card__times">
          <text>最近询价 {{ fmtDateTime(row.lastInquiryAt) }}</text>
          <text v-if="row.lastConfirmedAt"> · 最近成交 {{ fmtDateTime(row.lastConfirmedAt) }}</text>
        </view>
        <view class="card__flags">
          <text v-if="row.remark" class="flag flag--remark">已备注</text>
          <text v-if="row.dueReminderCount > 0" class="flag flag--due">{{ row.dueReminderCount }} 条待提醒</text>
          <text v-else-if="row.nextReminderAt" class="flag flag--plan">下次 {{ row.nextReminderAt }}</text>
        </view>
      </view>
      <view v-if="loading" class="more"><text class="more__t">加载中…</text></view>
      <view v-else-if="noMore" class="more"><text class="more__t">已显示全部 {{ total }} 位客户</text></view>
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
    align-items: center;
    justify-content: space-between;
  }

  &__phone {
    font-size: 34rpx;
    font-weight: 700;
    color: $cc-fg-1;
    letter-spacing: 1rpx;
  }

  &__meta {
    display: flex;
    justify-content: space-between;
    margin-top: 10rpx;
    font-size: 25rpx;
    color: $cc-fg-2;
  }

  &__times {
    margin-top: 6rpx;
    font-size: 21rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }

  &__flags {
    display: flex;
    gap: 12rpx;
    margin-top: 16rpx;
  }
}

.ops {
  display: flex;
  gap: 14rpx;

  &__btn {
    height: 58rpx;
    line-height: 58rpx;
    padding: 0 24rpx;
    border-radius: 999rpx;
    font-size: 23rpx;

    &--ghost {
      background: $cc-bg-3;
      color: $cc-fg-2;
    }

    &--main {
      background: $cc-accent;
      color: #fff;
      font-weight: 600;
    }
  }
}

.flag {
  font-size: 20rpx;
  padding: 2rpx 14rpx;
  border-radius: 6rpx;

  &--remark {
    background: $cc-info-bg;
    color: $cc-info;
  }

  &--due {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--plan {
    background: $cc-bg-3;
    color: $cc-fg-3;
  }
}

.more {
  padding: 20rpx 0 40rpx;
  text-align: center;

  &__t {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}
</style>
