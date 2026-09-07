<script setup lang="ts">
// WA/WE 批发商移动端工作台（F3 · 登录后落地页）。
// 含：当前仓信息、待处理询价统计、功能入口、多仓切换、退出登录。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { LoginResponse } from '@cangchu/api-types'
import { inquiryApi } from '../../api/inquiry'
import { waInboundApi } from '../../api/waInbound'
import { accountApi } from '../../api/account'
import {
  clearAuth,
  hasWaScope,
  healWork,
  readAuth,
  readWork,
  roleLabel,
  workEntries,
  writeWork,
  type WaWork,
} from '../../utils/session'

const auth = ref<LoginResponse | null>(null)
const work = ref<WaWork | null>(null)
const pendingCount = ref(0)
const inboundPending = ref(0)
const statsLoading = ref(false)
const showSwitch = ref(false)
const loggingOut = ref(false)

const entries = computed(() => {
  const a = auth.value
  if (!a) return []
  return workEntries(a).map((e) => ({ ...e }))
})

const currentStoreName = computed(() => work.value?.storeName ?? '')

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
    const list = await inquiryApi.list()
    pendingCount.value = list.filter((i) => i.status === 'PENDING').length
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
    role: e.role as WaWork['role'],
    tenantId: e.tenantId,
    wholesalerId: e.wholesalerId,
    storeName: e.storeName ?? null,
  })
  work.value = readWork()
  showSwitch.value = false
  uni.showToast({ title: '已切换工作仓', icon: 'success' })
  void refreshStats()
}

function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前批发商账号？',
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
  if (!hasWaScope()) {
    goLogin()
    return
  }
  auth.value = readAuth()
  work.value = readWork()
  // 角色区自愈：账号若同时具备 ST/WA 多区角色，进入批发商区时保证 work 为 WA/WE 条目
  if (auth.value) work.value = healWork('wa', auth.value, work.value)
})

onShow(() => {
  auth.value = readAuth()
  work.value = readWork()
  if (auth.value) work.value = healWork('wa', auth.value, work.value)
  if (work.value) void refreshStats()
})

onPullDownRefresh(async () => {
  auth.value = readAuth()
  work.value = readWork()
  if (auth.value) work.value = healWork('wa', auth.value, work.value)
  await refreshStats()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view class="wa-flag">
      <text class="wa-flag__t">批发商移动工作台</text>
      <text class="wa-flag__sub">手机即可处理询价 · 客户 · 商品</text>
    </view>

    <!-- 当前仓卡 -->
    <view class="card work-card" @click="showSwitch = entries.length > 1">
      <view class="work-card__main">
        <text class="work-card__name">{{ currentStoreName || '未命名仓' }}</text>
        <view class="work-card__meta">
          <text class="chip" :class="work?.role === 'WA' ? 'chip--wa' : 'chip--we'">{{ work ? roleLabel(work.role) : '' }}</text>
          <text v-if="entries.length > 1" class="work-card__switch">切换 ›</text>
        </view>
      </view>
    </view>

    <!-- 待处理统计 -->
    <view class="card stat" @click="go('/pages/wa/inquiries/index')">
      <text class="stat__num">{{ statsLoading ? '…' : pendingCount }}</text>
      <view class="stat__main">
        <text class="stat__label">待确认询价单</text>
        <text class="stat__sub">买家已提交，尽快确认并结算</text>
      </view>
      <text class="stat__arrow">›</text>
    </view>

    <!-- 功能入口 -->
    <view class="group">
      <view class="cell" @click="go('/pages/wa/inquiries/index')">
        <view class="cell__icon cell__icon--blue">询</view>
        <text class="cell__label">询价处理</text>
        <text v-if="pendingCount > 0" class="cell__badge">{{ pendingCount }} 单待处理</text>
        <text class="cell__arrow">›</text>
      </view>
      <view class="cell" @click="go('/pages/wa/customers/index')">
        <view class="cell__icon cell__icon--green">客</view>
        <text class="cell__label">客户跟进</text>
        <text class="cell__hint">备注 · 提醒 · 回访</text>
        <text class="cell__arrow">›</text>
      </view>
      <view class="cell" @click="go('/pages/wa/products/index')">
        <view class="cell__icon cell__icon--amber">货</view>
        <text class="cell__label">我的商品</text>
        <text class="cell__hint">上架 / 下架管理</text>
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
        <view class="sheet__title">选择工作仓</view>
        <view class="sheet__desc">同一账号可入驻多个仓库，切换后将以所选仓库身份处理业务</view>
        <view
          v-for="e in entries"
          :key="`${e.role}-${e.tenantId}`"
          class="ws-row"
          :class="{ 'ws-row--on': work && e.tenantId === work.tenantId && e.role === work.role }"
          @click="switchTo(e)"
        >
          <view class="ws-row__main">
            <text class="ws-row__name">{{ e.storeName || '未命名仓' }}</text>
            <text class="chip" :class="e.role === 'WA' ? 'chip--wa' : 'chip--we'">{{ roleLabel(e.role) }}</text>
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

.wa-flag {
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

/* 工作仓 */
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

  &--wa {
    background: $cc-info-bg;
    color: $cc-accent;
  }

  &--we {
    background: $cc-bg-3;
    color: $cc-fg-2;
  }
}

/* 统计 */
.stat {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
  padding: 26rpx 28rpx;

  &__num {
    font-size: 56rpx;
    font-weight: 800;
    color: $cc-danger;
    width: 110rpx;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__label {
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sub {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__arrow {
    font-size: 40rpx;
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

    &--green {
      background: $cc-success;
    }

    &--amber {
      background: $cc-warning;
    }

    &--red {
      background: $cc-danger;
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
    background: $cc-success-bg;
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
