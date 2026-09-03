<script setup lang="ts">
// WK 库管工作台（F5-W1）：仓卡 + 出入库待办统计 + 作业入口。
// 现场出入库在「出入库作业」办理；库存/批次/盘点核对（W2）仍在电脑端，占位提示。
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { LoginResponse } from '@cangchu/api-types'
import { wkApi } from '../../api/wk'
import type { WaWork, WorkRole } from '../../utils/session'
import { clearAuth, healWork, readAuth, readWork, roleLabel, writeWork, wkEntries } from '../../utils/session'

const auth = ref<LoginResponse | null>(null)
const entries = ref<LoginResponse['roles']>([])
const current = ref<WaWork | null>(null)
const loading = ref(false)

const statInboundPending = ref(0)
const statOutboundPending = ref(0)

const showSwitch = ref(false)

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

async function loadStats(): Promise<void> {
  try {
    // 入库待办 = 正向链 待受理/待登记（list 非分页，直接取全量统计）
    const [inb, outPend, outPrinted] = await Promise.all([
      wkApi.inbound.list(),
      wkApi.outbound.list('PENDING_ACCEPT', 1, 1),
      wkApi.outbound.list('PRINTED', 1, 1),
    ])
    statInboundPending.value = inb.filter(
      (d) => d.source === 'WA_SUBMIT' && (d.status === 'SUBMITTED' || d.status === 'ACCEPTED'),
    ).length
    statOutboundPending.value = Number(outPend.total) + Number(outPrinted.total)
  } catch {
    /* request 已 toast */
  }
}

function refresh(): void {
  if (!current.value) return
  loading.value = true
  loadStats().finally(() => {
    loading.value = false
  })
}

function goInbound(): void {
  uni.navigateTo({ url: '/pages/wk/inbound/index' })
}

function goOutbound(): void {
  uni.navigateTo({ url: '/pages/wk/outbound/index' })
}

function goCreateOut(): void {
  uni.navigateTo({ url: '/pages/wk/outbound/create' })
}

function openSwitch(): void {
  if (entries.value.length > 1) showSwitch.value = true
}

function toWork(a: LoginResponse, e: LoginResponse['roles'][number]): WaWork {
  return {
    userId: a.userId,
    role: e.role as WorkRole,
    tenantId: e.tenantId as string,
    wholesalerId: e.wholesalerId,
    storeName: e.storeName ?? null,
  }
}

function switchTo(e: LoginResponse['roles'][number]): void {
  if (!auth.value) return
  const w = toWork(auth.value, e)
  writeWork(w)
  current.value = w
  showSwitch.value = false
  uni.showToast({ title: `已切换：${e.storeName ?? '仓库'}`, icon: 'none' })
  refresh()
}

function doLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '退出后需重新登录才能办理出入库作业。',
    confirmText: '退出',
    success: (r) => {
      if (!r.confirm) return
      clearAuth()
      uni.reLaunch({ url: '/pages/wa/login/index' })
    },
  })
}

function guardZone(): WaWork | null {
  const a = readAuth()
  const work = readWork()
  auth.value = a
  if (!a) {
    goLogin()
    return null
  }
  const healed = healWork('wk', a, work)
  if (!healed) {
    entries.value = wkEntries(a)
    if (!wkEntries(a).length) goLogin()
    return null
  }
  entries.value = wkEntries(a)
  current.value = healed
  return healed
}

onLoad(() => {
  if (guardZone()) refresh()
})

onShow(() => {
  if (guardZone()) refresh()
})

onPullDownRefresh(async () => {
  await loadStats()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view v-if="current" class="page">
    <!-- 仓卡 -->
    <view class="hero">
      <view class="hero__row">
        <view class="hero__logo">仓</view>
        <view class="hero__info">
          <view class="hero__name-line">
            <text class="hero__name">{{ current.storeName || '本仓' }}</text>
            <view class="chip-role" @click="openSwitch">
              <text>{{ roleLabel(current.role) }}</text>
              <text v-if="entries.length > 1" class="chip-role__caret">切换 ▾</text>
            </view>
          </view>
          <text class="hero__sub">{{ roleLabel(current.role) }}工作台 · 现场出入库作业</text>
        </view>
      </view>
      <view class="hero__stats">
        <view class="stat">
          <text class="stat__num">{{ statInboundPending }}</text>
          <text class="stat__label">入库待办</text>
        </view>
        <view class="stat">
          <text class="stat__num">{{ statOutboundPending }}</text>
          <text class="stat__label">出库待办</text>
        </view>
        <view class="stat">
          <text class="stat__num">{{ statInboundPending + statOutboundPending }}</text>
          <text class="stat__label">合计待办</text>
        </view>
      </view>
    </view>

    <!-- 入口 -->
    <view class="grid">
      <view class="entry" @click="goInbound">
        <view class="entry__icon entry__icon--in">收</view>
        <text class="entry__name">入库作业</text>
        <text class="entry__desc">受理 · 登记入库</text>
        <view v-if="statInboundPending" class="entry__badge">{{ statInboundPending }}</view>
      </view>
      <view class="entry" @click="goOutbound">
        <view class="entry__icon entry__icon--out">发</view>
        <text class="entry__name">出库作业</text>
        <text class="entry__desc">打印 · 登记出库</text>
        <view v-if="statOutboundPending" class="entry__badge">{{ statOutboundPending }}</view>
      </view>
      <view class="entry" @click="goCreateOut">
        <view class="entry__icon entry__icon--wk">直</view>
        <text class="entry__name">代建出库</text>
        <text class="entry__desc">现场卖货直接出库</text>
      </view>
      <view class="entry entry--dim">
        <view class="entry__icon entry__icon--more">…</view>
        <text class="entry__name">库存 / 盘点</text>
        <text class="entry__desc">请在电脑端办理</text>
      </view>
    </view>

    <view class="hint">
      <text class="hint__t">作业提示：出入库登记需在商户申请单上进行；数量差异请如实登记并填写原因，系统将自动留痕。</text>
    </view>

    <view class="footer">
      <view class="footer__btn" @click="goCreateOut">＋ 现场代建出库</view>
      <view class="footer__btn footer__btn--plain" @click="doLogout">退出登录</view>
    </view>

    <!-- 多仓切换 -->
    <view v-if="showSwitch" class="mask" @click="showSwitch = false">
      <view class="sheet" @click.stop>
        <view class="sheet__title">切换仓库</view>
        <view
          v-for="e in entries"
          :key="String(e.role) + '-' + String(e.tenantId)"
          class="sheet__row"
          :class="{ 'sheet__row--on': e.tenantId === current.tenantId }"
          @click="switchTo(e)"
        >
          <text class="sheet__name">{{ e.storeName || '本仓' }}</text>
          <text class="sheet__role">{{ roleLabel(e.role) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  box-sizing: border-box;
}

.hero {
  padding: 30rpx 26rpx;
  border-radius: $cc-card-radius;
  background: linear-gradient(135deg, #0f766e 0%, #134e4a 100%);
  color: #fff;
  margin-bottom: 20rpx;

  &__row {
    display: flex;
    align-items: center;
    gap: 18rpx;
  }

  &__logo {
    width: 88rpx;
    height: 88rpx;
    line-height: 88rpx;
    text-align: center;
    border-radius: 22rpx;
    background: rgba(255, 255, 255, 0.16);
    font-size: 44rpx;
    font-weight: 700;
  }

  &__info {
    flex: 1;
  }

  &__name-line {
    display: flex;
    align-items: center;
    gap: 12rpx;
  }

  &__name {
    font-size: 34rpx;
    font-weight: 700;
  }

  &__sub {
    display: block;
    margin-top: 6rpx;
    font-size: 22rpx;
    color: rgba(255, 255, 255, 0.7);
  }

  &__stats {
    display: flex;
    margin-top: 26rpx;
    padding-top: 22rpx;
    border-top: 2rpx solid rgba(255, 255, 255, 0.14);
  }
}

.chip-role {
  display: flex;
  align-items: center;
  gap: 6rpx;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.18);
  font-size: 20rpx;

  &__caret {
    font-size: 18rpx;
  }
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;

  &__num {
    font-size: 40rpx;
    font-weight: 800;
  }

  &__label {
    font-size: 20rpx;
    color: rgba(255, 255, 255, 0.75);
  }
}

.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.entry {
  position: relative;
  width: calc(50% - 8rpx);
  padding: 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  box-sizing: border-box;

  &--dim {
    opacity: 0.55;
  }

  &__icon {
    width: 64rpx;
    height: 64rpx;
    line-height: 64rpx;
    text-align: center;
    border-radius: 16rpx;
    font-size: 28rpx;
    font-weight: 700;
    margin-bottom: 10rpx;

    &--in {
      background: $cc-success-bg;
      color: $cc-success;
    }

    &--out {
      background: $cc-warning-bg;
      color: $cc-warning;
    }

    &--wk {
      background: $cc-info-bg;
      color: $cc-accent;
    }

    &--more {
      background: $cc-bg-3;
      color: $cc-fg-3;
    }
  }

  &__name {
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__desc {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__badge {
    position: absolute;
    top: 14rpx;
    right: 14rpx;
    min-width: 34rpx;
    height: 34rpx;
    line-height: 34rpx;
    padding: 0 8rpx;
    text-align: center;
    border-radius: 999rpx;
    background: $cc-danger;
    color: #fff;
    font-size: 20rpx;
  }
}

.hint {
  margin-top: 20rpx;
  padding: 18rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-info-bg;
  color: $cc-accent;
  font-size: 22rpx;
  line-height: 1.7;
}

.footer {
  margin-top: 40rpx;
  display: flex;
  gap: 16rpx;

  &__btn {
    flex: 1;
    height: 84rpx;
    line-height: 84rpx;
    text-align: center;
    border-radius: 14rpx;
    background: $cc-brand-primary;
    color: #fff;
    font-size: 28rpx;
    font-weight: 600;

    &--plain {
      background: $cc-bg-1;
      color: $cc-fg-3;
      border: 2rpx solid $cc-border-1;
    }
  }
}

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
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
    margin-bottom: 20rpx;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 24rpx 8rpx;
    border-bottom: 2rpx solid $cc-bg-2;

    &--on {
      color: $cc-accent;

      .sheet__name {
        color: $cc-accent;
        font-weight: 700;
      }
    }

    &:last-child {
      border-bottom: none;
    }
  }

  &__name {
    font-size: 28rpx;
    color: $cc-fg-1;
  }

  &__role {
    font-size: 22rpx;
    color: $cc-fg-3;
  }
}
</style>
