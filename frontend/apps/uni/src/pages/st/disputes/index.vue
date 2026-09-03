<script setup lang="ts">
// ST 申诉处理（F4 · 移动端）：三态分段 + 待处理先到先办；处理弹层（成立/不成立 + 说明必填留痕）。
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { BillDispute } from '@cangchu/api-types'
import { stBillApi } from '../../../api/st'
import { readAuth, readWork } from '../../../utils/session'
import { disputeStatusLabel, disputeStatusTone, fmtDateTime } from '../../../utils/billing'

const STATUS_TABS = [
  { value: 'PENDING', label: '待处理' },
  { value: 'RESOLVED', label: '已成立' },
  { value: 'REJECTED', label: '已驳回' },
]

const activeStatus = ref('PENDING')
const disputes = ref<BillDispute[]>([])
const loading = ref(false)

const showResolve = ref(false)
const resolveTarget = ref<BillDispute | null>(null)
const resolveConclusion = ref<'RESOLVED' | 'REJECTED'>('RESOLVED')
const resolveNote = ref('')
const submitting = ref(false)

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId)
}

async function fetchList(): Promise<void> {
  if (!guard()) {
    goLogin()
    return
  }
  loading.value = true
  try {
    disputes.value = await stBillApi.listDisputes(activeStatus.value)
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function pickStatus(s: string): void {
  if (activeStatus.value === s) return
  activeStatus.value = s
  void fetchList()
}

function openResolve(d: BillDispute): void {
  resolveTarget.value = d
  resolveConclusion.value = 'RESOLVED'
  resolveNote.value = ''
  showResolve.value = true
}

function closeResolve(): void {
  if (submitting.value) return
  showResolve.value = false
  resolveTarget.value = null
}

function pickConclusion(c: 'RESOLVED' | 'REJECTED'): void {
  resolveConclusion.value = c
}

async function submitResolve(): Promise<void> {
  const d = resolveTarget.value
  if (!d) return
  if (!resolveNote.value.trim()) {
    uni.showToast({ title: '请填写处理说明', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    await stBillApi.resolveDispute(d.id, {
      conclusion: resolveConclusion.value,
      resolution: resolveNote.value.trim(),
    })
    uni.showToast({
      title: resolveConclusion.value === 'RESOLVED' ? '申诉成立，已标记' : '已驳回',
      icon: 'success',
    })
    closeResolve()
    await fetchList()
  } catch {
    // request 已 toast
  } finally {
    submitting.value = false
  }
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void fetchList()
})

onShow(() => {
  if (!guard()) {
    goLogin()
    return
  }
})

onPullDownRefresh(async () => {
  await fetchList()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 分段 -->
    <view class="tabs">
      <view
        v-for="t in STATUS_TABS"
        :key="t.value"
        class="tabs__item"
        :class="{ 'tabs__item--on': activeStatus === t.value }"
        @click="pickStatus(t.value)"
      >{{ t.label }}</view>
    </view>

    <!-- 列表 -->
    <view v-if="disputes.length" class="list">
      <view
        v-for="d in disputes"
        :key="String(d.id)"
        class="card"
        :class="{ 'card--tap': d.status === 'PENDING' }"
        @click="d.status === 'PENDING' && openResolve(d)"
      >
        <view class="card__head">
          <text class="tag" :class="`tag--${disputeStatusTone(d.status)}`">{{ disputeStatusLabel(d.status) }}</text>
          <text class="card__time">{{ fmtDateTime(d.createdAt) }}</text>
        </view>
        <view class="card__ws">
          <text class="card__name">{{ d.wholesalerName }}</text>
          <text class="card__no mono">{{ d.billNo }}</text>
        </view>
        <text class="card__reason">{{ d.reason }}</text>
        <view v-if="d.status !== 'PENDING'" class="card__res">
          <text class="card__res-label">处理说明</text>
          <text class="card__res-text">{{ d.resolution }}</text>
        </view>
        <view v-if="d.status === 'PENDING'" class="card__act">
          <text>去处理 ›</text>
        </view>
      </view>
    </view>

    <!-- 空态 -->
    <view v-else-if="!loading" class="empty">
      <text class="empty__icon">申</text>
      <text class="empty__t">暂无{{ STATUS_TABS.find((t) => t.value === activeStatus)?.label }}申诉</text>
    </view>

    <!-- 处理弹层 -->
    <view v-if="showResolve && resolveTarget" class="mask" @click="closeResolve">
      <view class="sheet" @click.stop>
        <view class="sheet__title">处理申诉</view>
        <view class="sheet__ws">
          <text class="sheet__name">{{ resolveTarget.wholesalerName }}</text>
          <text class="sheet__no mono">{{ resolveTarget.billNo }}</text>
        </view>
        <view class="sheet__reason">商户申诉：{{ resolveTarget.reason }}</view>

        <view class="row row--col">
          <text class="row__label">结论</text>
          <view class="chips">
            <view class="chip chip--ok" :class="{ 'chip--on': resolveConclusion === 'RESOLVED' }" @click="pickConclusion('RESOLVED')">成立</view>
            <view class="chip chip--no" :class="{ 'chip--on': resolveConclusion === 'REJECTED' }" @click="pickConclusion('REJECTED')">不成立</view>
          </view>
        </view>

        <view class="row row--col">
          <text class="row__label">处理说明（必填）</text>
          <textarea
            v-model="resolveNote"
            class="row__textarea"
            :placeholder="resolveConclusion === 'RESOLVED' ? '说明调整/冲销等处理动作，如：行 A 金额有误已冲销' : '说明驳回理由'"
            placeholder-class="ph"
            :maxlength="200"
          />
        </view>

        <button class="btn-primary sheet__submit" :loading="submitting" @click="submitResolve">确认提交</button>
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

.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 20rpx;

  &__item {
    flex: 1;
    text-align: center;
    padding: 16rpx 0;
    border-radius: 12rpx;
    background: $cc-bg-1;
    border: 2rpx solid $cc-border-1;
    font-size: 26rpx;
    color: $cc-fg-2;

    &--on {
      background: $cc-info-bg;
      border-color: $cc-accent;
      color: $cc-accent;
      font-weight: 600;
    }
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.card {
  padding: 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &--tap {
    border: 2rpx solid $cc-warning;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12rpx;
  }

  &__time {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__ws {
    display: flex;
    align-items: baseline;
    gap: 14rpx;
    margin-bottom: 10rpx;
  }

  &__name {
    font-size: 29rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__no {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__reason {
    display: block;
    font-size: 25rpx;
    color: $cc-fg-2;
    line-height: 1.6;
  }

  &__res {
    margin-top: 12rpx;
    padding: 14rpx;
    border-radius: 10rpx;
    background: $cc-bg-2;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__res-label {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__res-text {
    font-size: 23rpx;
    color: $cc-fg-2;
    line-height: 1.5;
  }

  &__act {
    margin-top: 14rpx;
    text-align: right;
    font-size: 24rpx;
    color: $cc-warning;
    font-weight: 600;
  }
}

.mono {
  font-family: monospace;
}

.tag {
  font-size: 20rpx;
  padding: 4rpx 14rpx;
  border-radius: 6rpx;

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }

  &--ok {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--muted {
    background: $cc-bg-3;
    color: $cc-fg-3;
  }
}

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

  &__ws {
    display: flex;
    align-items: baseline;
    gap: 14rpx;
    margin-top: 16rpx;
  }

  &__name {
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__no {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__reason {
    margin: 12rpx 0 24rpx;
    padding: 16rpx;
    border-radius: 10rpx;
    background: $cc-bg-2;
    font-size: 24rpx;
    color: $cc-fg-2;
    line-height: 1.6;
  }

  &__submit {
    height: 96rpx;
    margin-top: 24rpx;
    font-size: 31rpx;
    font-weight: 600;
  }
}

.row {
  &--col {
    display: flex;
    flex-direction: column;
    gap: 14rpx;
    margin-bottom: 24rpx;
  }

  &__label {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__textarea {
    width: 100%;
    height: 150rpx;
    padding: 16rpx 20rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 10rpx;
    font-size: 26rpx;
    box-sizing: border-box;
  }
}

.ph {
  color: $cc-fg-4;
}

.chips {
  display: flex;
  gap: 12rpx;
}

.chip {
  flex: 1;
  text-align: center;
  padding: 14rpx 0;
  border-radius: 10rpx;
  border: 2rpx solid $cc-border-1;
  font-size: 26rpx;
  background: $cc-bg-1;

  &--ok {
    color: $cc-success;
  }

  &--no {
    color: $cc-fg-3;
  }

  &--on {
    border-color: currentColor;
    font-weight: 700;
    background: $cc-bg-1;
  }
}
</style>
