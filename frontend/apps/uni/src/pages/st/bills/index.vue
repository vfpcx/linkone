<script setup lang="ts">
// ST 账单一览（F4 · 移动端）：月份 + 状态筛选、汇总条、分页列表、下拉刷新。
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { Bill } from '@cangchu/api-types'
import { stBillApi } from '../../../api/st'
import { readAuth, readWork } from '../../../utils/session'
import { billStatusLabel, billStatusTone, fmtMoney, recentMonths } from '../../../utils/billing'

/** 状态筛选项（值 = 后端 BillStatus；''=全部） */
const STATUS_TABS = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '待核对' },
  { value: 'DISPATCHED', label: '已下发' },
  { value: 'PENDING_PAYMENT', label: '待回款' },
  { value: 'PARTIAL_PAID', label: '部分回款' },
  { value: 'PAID', label: '已结清' },
  { value: 'DISPUTED', label: '争议中' },
]

const PAGE_SIZE = 15

const monthTabs = ref<string[]>(recentMonths(6))
const activeMonth = ref('') // ''=全部账期
const activeStatus = ref('')
const bills = ref<Bill[]>([])
const summary = ref({ receivable: 0, received: 0, outstanding: 0, total: 0 })
const page = ref(1)
const loading = ref(false)
const finished = ref(false)

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId)
}

async function fetchList(reset = false): Promise<void> {
  if (loading.value) return
  if (!guard()) {
    goLogin()
    return
  }
  if (reset) {
    page.value = 1
    finished.value = false
  }
  if (finished.value) return
  loading.value = true
  try {
    const res = await stBillApi.list({
      month: activeMonth.value || undefined,
      status: activeStatus.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    })
    bills.value = reset ? res.records : bills.value.concat(res.records)
    summary.value = {
      receivable: res.receivable,
      received: res.received,
      outstanding: res.outstanding,
      total: res.total,
    }
    finished.value = page.value * PAGE_SIZE >= res.total
    page.value += 1
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function pickMonth(m: string): void {
  if (activeMonth.value === m) return
  activeMonth.value = m
  void fetchList(true)
}

function pickStatus(s: string): void {
  if (activeStatus.value === s) return
  activeStatus.value = s
  void fetchList(true)
}

function goDetail(b: Bill): void {
  uni.navigateTo({ url: `/pages/st/bills/detail?id=${encodeURIComponent(String(b.id))}` })
}

function onReachBottom(): void {
  void fetchList(false)
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void fetchList(true)
})

onShow(() => {
  if (!guard()) {
    goLogin()
    return
  }
})

onPullDownRefresh(async () => {
  await fetchList(true)
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 筛选：月份 chips -->
    <scroll-view scroll-x class="filters" :show-scrollbar="false">
      <view class="filters__row">
        <view
          class="fchip"
          :class="{ 'fchip--on': activeMonth === '' }"
          @click="pickMonth('')"
        >全部账期</view>
        <view
          v-for="m in monthTabs"
          :key="m"
          class="fchip"
          :class="{ 'fchip--on': activeMonth === m }"
          @click="pickMonth(m)"
        >{{ m }}</view>
      </view>
      <view class="filters__row">
        <view
          v-for="t in STATUS_TABS"
          :key="t.value"
          class="fchip"
          :class="{ 'fchip--on': activeStatus === t.value }"
          @click="pickStatus(t.value)"
        >{{ t.label }}</view>
      </view>
    </scroll-view>

    <!-- 汇总条（按当前筛选） -->
    <view class="summary">
      <view class="summary__item">
        <text class="summary__num">{{ fmtMoney(summary.receivable) }}</text>
        <text class="summary__label">应收</text>
      </view>
      <view class="summary__item">
        <text class="summary__num summary__num--ok">{{ fmtMoney(summary.received) }}</text>
        <text class="summary__label">已收</text>
      </view>
      <view class="summary__item">
        <text class="summary__num summary__num--warn">{{ fmtMoney(summary.outstanding) }}</text>
        <text class="summary__label">未收</text>
      </view>
      <view class="summary__item">
        <text class="summary__num">{{ summary.total }}</text>
        <text class="summary__label">账单数</text>
      </view>
    </view>

    <!-- 账单卡片 -->
    <view v-if="bills.length" class="list">
      <view v-for="b in bills" :key="String(b.id)" class="bill" @click="goDetail(b)">
        <view class="bill__head">
          <text class="bill__ws">{{ b.wholesalerName }}</text>
          <text class="tag" :class="`tag--${billStatusTone(b.status)}`">{{ billStatusLabel(b.status) }}</text>
        </view>
        <view class="bill__no">
          <text class="mono">{{ b.billNo }}</text>
          <text class="bill__month">{{ b.billingMonth }} 账期</text>
        </view>
        <view class="bill__amounts">
          <view class="amt">
            <text class="amt__label">应收</text>
            <text class="amt__val">{{ fmtMoney(b.totalAmount) }}</text>
          </view>
          <view class="amt">
            <text class="amt__label">已收</text>
            <text class="amt__val amt__val--ok">{{ fmtMoney(b.paidAmount) }}</text>
          </view>
          <view class="amt amt--out">
            <text class="amt__label">未收</text>
            <text class="amt__val amt__val--warn">{{ fmtMoney(b.outstandingAmount) }}</text>
          </view>
        </view>
      </view>
      <view class="list__foot">
        <text v-if="loading" class="list__tip">加载中…</text>
        <text v-else-if="finished" class="list__tip">已加载全部 {{ summary.total }} 张账单</text>
        <text v-else class="list__tip">上拉加载更多</text>
      </view>
    </view>

    <!-- 空态 -->
    <view v-else-if="!loading" class="empty">
      <text class="empty__icon">账</text>
      <text class="empty__t">暂无账单</text>
      <text class="empty__s">试试切换账期或状态筛选</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  box-sizing: border-box;
}

/* 筛选 */
.filters {
  width: 100%;
  white-space: nowrap;

  &__row {
    display: flex;
    gap: 12rpx;
    padding: 4rpx 0;
    margin-bottom: 6rpx;
  }
}

.fchip {
  flex-shrink: 0;
  padding: 10rpx 24rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  font-size: 24rpx;
  color: $cc-fg-2;

  &--on {
    background: $cc-info-bg;
    border-color: $cc-accent;
    color: $cc-accent;
    font-weight: 600;
  }
}

/* 汇总条 */
.summary {
  display: flex;
  margin: 16rpx 0 20rpx;
  padding: 24rpx 8rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6rpx;
  }

  &__num {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-fg-1;

    &--ok {
      color: $cc-success;
    }

    &--warn {
      color: $cc-warning;
    }
  }

  &__label {
    font-size: 21rpx;
    color: $cc-fg-4;
  }
}

/* 账单卡 */
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.bill {
  padding: 24rpx 24rpx 20rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14rpx;
  }

  &__ws {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__no {
    display: flex;
    align-items: center;
    gap: 14rpx;
    margin-bottom: 18rpx;
  }

  &__month {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__amounts {
    display: flex;
    gap: 8rpx;
  }

  &__foot {
    padding: 20rpx 0 4rpx;
  }
}

.mono {
  font-family: monospace;
  font-size: 21rpx;
  color: $cc-fg-3;
}

.amt {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  padding: 14rpx 12rpx;
  border-radius: 10rpx;
  background: $cc-bg-2;

  &--out {
    background: $cc-warning-bg;
  }

  &__label {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__val {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-fg-1;

    &--ok {
      color: $cc-success;
    }

    &--warn {
      color: $cc-warning;
    }
  }
}

/* 状态标签（tag--* 语义色，F4 工作台/详情共用同套） */
.tag {
  font-size: 20rpx;
  padding: 4rpx 14rpx;
  border-radius: 6rpx;

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }

  &--ok {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--muted {
    background: $cc-bg-3;
    color: $cc-fg-3;
  }
}

.list__foot {
  padding-top: 20rpx;
  text-align: center;
}

.list__tip {
  font-size: 22rpx;
  color: $cc-fg-4;
}

/* 空态 */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 120rpx 0;

  &__icon {
    width: 96rpx;
    height: 96rpx;
    line-height: 96rpx;
    text-align: center;
    border-radius: 24rpx;
    background: $cc-bg-3;
    color: $cc-fg-4;
    font-size: 40rpx;
  }

  &__t {
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__s {
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}
</style>
