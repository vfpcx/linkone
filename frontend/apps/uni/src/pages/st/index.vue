<script setup lang="ts">
// ST 结算员移动端工作台（F4 · 登录后落地页）。
// 含：当前仓、本月应收/已收/未收汇总、功能入口（账单一览/申诉处理）、多仓切换、退出登录。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { LoginResponse } from '@cangchu/api-types'
import { stBillApi } from '../../api/st'
import { accountApi } from '../../api/account'
import {
  clearAuth,
  hasStScope,
  healWork,
  readAuth,
  readWork,
  roleLabel,
  stEntries,
  writeWork,
  type WaWork,
} from '../../utils/session'
import { currentMonth, fmtMoney } from '../../utils/billing'

const auth = ref<LoginResponse | null>(null)
const work = ref<WaWork | null>(null)
const month = ref(currentMonth())
const sum = ref({ receivable: 0, received: 0, outstanding: 0 })
const disputePending = ref(0)
const statsLoading = ref(false)
const showSwitch = ref(false)
const loggingOut = ref(false)

const entries = computed(() => {
  const a = auth.value
  if (!a) return []
  return stEntries(a).map((e) => ({ ...e }))
})

const tenantName = computed(() => work.value?.storeName || auth.value?.tenantInfo?.tenantName || '未命名仓')

function go(path: string): void {
  uni.navigateTo({ url: path })
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

async function refreshStats(): Promise<void> {
  if (!work.value) return
  statsLoading.value = true
  try {
    const [list, disputes] = await Promise.all([
      stBillApi.list({ month: month.value, page: 1, size: 1 }),
      stBillApi.listDisputes('PENDING'),
    ])
    sum.value = {
      receivable: list.receivable,
      received: list.received,
      outstanding: list.outstanding,
    }
    disputePending.value = disputes.length
  } catch {
    // request 已 toast；统计失败不阻塞页面
  } finally {
    statsLoading.value = false
  }
}

function switchTo(e: LoginResponse['roles'][number]): void {
  const a = auth.value
  if (!a || !e.tenantId) return
  writeWork({
    userId: a.userId,
    role: 'ST',
    tenantId: e.tenantId,
    wholesalerId: null,
    storeName: e.storeName ?? null,
  })
  work.value = readWork()
  showSwitch.value = false
  uni.showToast({ title: '已切换结算仓', icon: 'success' })
  void refreshStats()
}

function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前结算员账号？',
    confirmColor: '#dc2626',
    success: (r) => {
      if (!r.confirm) return
      loggingOut.value = true
      accountApi
        .logout()
        .catch(() => {
          /* 服务端登出失败不影响本地清理 */
        })
        .finally(() => {
          clearAuth()
          uni.showToast({ title: '已退出', icon: 'none' })
          setTimeout(goLogin, 400)
          loggingOut.value = false
        })
    },
  })
}

function goBuyer(): void {
  uni.reLaunch({ url: '/pages/index/index' })
}

onLoad(() => {
  if (!hasStScope()) {
    goLogin()
    return
  }
  auth.value = readAuth()
  work.value = readWork()
  if (auth.value) work.value = healWork('st', auth.value, work.value)
})

onShow(() => {
  auth.value = readAuth()
  work.value = readWork()
  if (auth.value) work.value = healWork('st', auth.value, work.value)
  if (work.value) void refreshStats()
})

onPullDownRefresh(async () => {
  auth.value = readAuth()
  work.value = readWork()
  if (auth.value) work.value = healWork('st', auth.value, work.value)
  await refreshStats()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view class="st-flag">
      <text class="st-flag__t">结算移动工作台</text>
      <text class="st-flag__sub">{{ month.replace('-', ' 年 ') }} 月 · 手机即可核对账单与登记回款</text>
    </view>

    <!-- 当前仓卡 -->
    <view class="card work-card" @click="showSwitch = entries.length > 1">
      <view class="work-card__main">
        <text class="work-card__name">{{ tenantName }}</text>
        <view class="work-card__meta">
          <text class="chip chip--st">{{ roleLabel('ST') }}</text>
          <text v-if="entries.length > 1" class="work-card__switch">切换 ›</text>
        </view>
      </view>
    </view>

    <!-- 本月汇总 -->
    <view class="sum-card" @click="go('/pages/st/bills/index')">
      <view class="sum-card__head">
        <text class="sum-card__title">本月应收（{{ month }}）</text>
        <text class="sum-card__link">全部账单 ›</text>
      </view>
      <view class="sum-row">
        <view class="sum-item">
          <text class="sum-item__num">{{ statsLoading ? '…' : fmtMoney(sum.receivable) }}</text>
          <text class="sum-item__label">应收</text>
        </view>
        <view class="sum-item">
          <text class="sum-item__num sum-item__num--ok">{{ statsLoading ? '…' : fmtMoney(sum.received) }}</text>
          <text class="sum-item__label">已收</text>
        </view>
        <view class="sum-item">
          <text class="sum-item__num sum-item__num--warn">{{ statsLoading ? '…' : fmtMoney(sum.outstanding) }}</text>
          <text class="sum-item__label">未收</text>
        </view>
      </view>
    </view>

    <!-- 功能入口 -->
    <view class="group">
      <view class="cell" @click="go('/pages/st/bills/index')">
        <view class="cell__icon cell__icon--blue">账</view>
        <text class="cell__label">账单一览</text>
        <text class="cell__hint">核对 · 下发 · 登记回款</text>
        <text class="cell__arrow">›</text>
      </view>
      <view class="cell" @click="go('/pages/st/disputes/index')">
        <view class="cell__icon cell__icon--amber">申</view>
        <text class="cell__label">申诉处理</text>
        <text v-if="disputePending > 0" class="cell__badge">{{ disputePending }} 条待处理</text>
        <text class="cell__arrow">›</text>
      </view>
    </view>

    <!-- 底部操作 -->
    <view class="foot">
      <button class="btn-ghost foot__logout" :loading="loggingOut" @click="confirmLogout">退出登录</button>
      <text class="foot__buyer" @click="goBuyer">以买家身份逛店 ›</text>
    </view>

    <!-- 多仓切换 -->
    <view v-if="showSwitch" class="mask" @click="showSwitch = false">
      <view class="sheet" @click.stop>
        <view class="sheet__title">选择结算仓</view>
        <view class="sheet__desc">同一账号可服务多个仓库，切换后将以所选仓库身份处理账单</view>
        <view
          v-for="e in entries"
          :key="`${e.role}-${e.tenantId}`"
          class="ws-row"
          :class="{ 'ws-row--on': work && e.tenantId === work.tenantId && e.role === work.role }"
          @click="switchTo(e)"
        >
          <view class="ws-row__main">
            <text class="ws-row__name">{{ e.storeName || '未命名仓' }}</text>
            <text class="chip chip--st">{{ roleLabel(e.role) }}</text>
          </view>
          <text class="ws-row__check">{{ work && e.tenantId === work.tenantId && e.role === work.role ? '✓' : '' }}</text>
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

.st-flag {
  padding: 8rpx 8rpx 20rpx;

  &__t {
    display: block;
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__sub {
    margin-top: 6rpx;
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}

.card {
  background: $cc-bg-1;
  border-radius: $cc-card-radius;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);
}

.work-card {
  padding: 30rpx 28rpx;

  &__main {
    display: flex;
    flex-direction: column;
    gap: 14rpx;
  }

  &__name {
    font-size: 36rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 16rpx;
  }

  &__switch {
    font-size: 24rpx;
    color: $cc-accent;
  }
}

.chip {
  font-size: 20rpx;
  padding: 2rpx 14rpx;
  border-radius: 6rpx;

  &--st {
    background: #ede9fe;
    color: #6d28d9;
  }
}

/* 本月汇总 */
.sum-card {
  margin-top: 20rpx;
  padding: 28rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 24rpx;
  }

  &__title {
    font-size: 27rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__link {
    font-size: 23rpx;
    color: $cc-accent;
  }
}

.sum-row {
  display: flex;
}

.sum-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;

  &__num {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-fg-1;
    word-break: break-all;
    text-align: center;

    &--ok {
      color: $cc-success;
    }

    &--warn {
      color: $cc-warning;
    }
  }

  &__label {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

/* 功能入口 */
.group {
  margin-top: 20rpx;
  border-radius: $cc-card-radius;
  overflow: hidden;
}

.cell {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 30rpx 28rpx;
  background: $cc-bg-1;

  & + & {
    border-top: 2rpx solid $cc-bg-2;
  }

  &__icon {
    width: 64rpx;
    height: 64rpx;
    line-height: 64rpx;
    text-align: center;
    border-radius: 14rpx;
    color: #fff;
    font-size: 28rpx;
    font-weight: 700;

    &--blue {
      background: $cc-accent;
    }

    &--amber {
      background: $cc-warning;
    }
  }

  &__label {
    font-size: 29rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__hint {
    margin-left: auto;
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__badge {
    margin-left: auto;
    font-size: 22rpx;
    color: $cc-danger;
    background: $cc-danger-bg;
    padding: 4rpx 14rpx;
    border-radius: 8rpx;
  }

  &__arrow {
    font-size: 34rpx;
    color: $cc-fg-4;
  }
}

.foot {
  margin-top: 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;

  &__logout {
    width: 100%;
    height: 92rpx;
    font-size: 30rpx;
    color: $cc-fg-2;
  }

  &__buyer {
    font-size: 24rpx;
    color: $cc-fg-3;
    text-decoration: underline;
  }
}

/* 弹层 */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  background: rgba(2, 6, 23, 0.5);
}

.sheet {
  width: 100%;
  padding: 36rpx 32rpx;
  padding-bottom: calc(36rpx + env(safe-area-inset-bottom));
  border-radius: 28rpx 28rpx 0 0;
  background: $cc-bg-1;
  box-sizing: border-box;

  &__title {
    font-size: 34rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__desc {
    margin: 12rpx 0 24rpx;
    font-size: 23rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }
}

.ws-row {
  display: flex;
  align-items: center;
  padding: 26rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;
  margin-bottom: 16rpx;

  &--on {
    background: #ede9fe;
  }

  &__main {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 16rpx;
  }

  &__name {
    font-size: 29rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__check {
    font-size: 32rpx;
    color: $cc-success;
  }
}
</style>
