<script setup lang="ts">
// WA/WE 客户详情（F3 · US-WE-04）：档案备注（覆盖式）+ 跟进提醒（增删）。
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { WaCustomerDetail, WaFollowupReminder } from '@cangchu/api-types'
import { customerApi } from '../../../api/customers'
import { piiApi } from '../../../api/pii'
import { fmtDateTime } from '../../../utils/format'
import { hasWaScope } from '../../../utils/session'

const customerKey = ref('')
const wholesalerId = ref('')
const detail = ref<WaCustomerDetail | null>(null)
const loading = ref(false)

// 备注
const remarkDraft = ref('')
const remarkSaving = ref(false)

// 新增提醒
const remContent = ref('')
const remDate = ref('')
const remTime = ref('09:00')
const remSaving = ref(false)

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

function toDateStr(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function initRemDate(): void {
  const d = new Date(Date.now() + 24 * 60 * 60 * 1000)
  remDate.value = toDateStr(d)
}

function remindAt(): string {
  return `${remDate.value || toDateStr(new Date())} ${remTime.value || '09:00'}`
}

async function load(): Promise<void> {
  if (!customerKey.value || !wholesalerId.value) return
  loading.value = true
  try {
    detail.value = await customerApi.detail(customerKey.value, wholesalerId.value)
    remarkDraft.value = detail.value.remark ?? ''
    initRemDate()
    uni.setNavigationBarTitle({ title: detail.value.wholesalerName || '客户详情' })
  } catch {
    // 越权/清档（50840）→ 返回并刷新列表
    uni.showToast({ title: '无法读取客户档案', icon: 'none' })
    setTimeout(() => uni.navigateBack({ fail: () => uni.navigateBack() }), 600)
  } finally {
    loading.value = false
  }
}

async function saveRemark(): Promise<void> {
  if (remarkSaving.value) return
  remarkSaving.value = true
  try {
    await customerApi.saveRemark(customerKey.value, {
      wholesalerId: wholesalerId.value,
      remark: remarkDraft.value.trim(),
    })
    uni.showToast({ title: remarkDraft.value.trim() ? '备注已保存' : '备注已清除', icon: 'success' })
    await load()
  } catch {
    /* request 已 toast */
  } finally {
    remarkSaving.value = false
  }
}

async function addReminder(): Promise<void> {
  const content = remContent.value.trim()
  if (!content) {
    uni.showToast({ title: '请填写提醒内容', icon: 'none' })
    return
  }
  if (remSaving.value) return
  remSaving.value = true
  try {
    await customerApi.addReminder(customerKey.value, {
      wholesalerId: wholesalerId.value,
      content,
      remindAt: remindAt(),
    })
    remContent.value = ''
    uni.showToast({ title: '提醒已添加', icon: 'success' })
    await load()
  } catch {
    /* 50841 等 request 已 toast */
  } finally {
    remSaving.value = false
  }
}

function removeReminder(r: WaFollowupReminder): void {
  uni.showModal({
    title: '删除提醒',
    content: `确定删除「${r.content.slice(0, 20)}${r.content.length > 20 ? '…' : ''}」？`,
    confirmColor: '#dc2626',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await customerApi.deleteReminder(customerKey.value, wholesalerId.value, String(r.id))
        uni.showToast({ title: '已删除', icon: 'none' })
        await load()
      } catch {
        /* request 已 toast */
      }
    },
  })
}

function onRemDateChange(e: any): void {
  if (e?.detail?.value) remDate.value = e.detail.value
}

function onRemTimeChange(e: any): void {
  if (e?.detail?.value) remTime.value = e.detail.value
}

function revealPhone(): void {
  const d = detail.value
  if (!d?.lastInquiryId) return
  piiApi
    .revealPhone('INQUIRY', String(d.lastInquiryId))
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
      /* request 已 toast */
    })
}

onLoad((options) => {
  if (!hasWaScope()) {
    uni.reLaunch({ url: '/pages/wa/login/index' })
    return
  }
  customerKey.value = decodeURIComponent((options?.customerKey as string) ?? '')
  wholesalerId.value = decodeURIComponent((options?.wholesalerId as string) ?? '')
  if (!customerKey.value || !wholesalerId.value) {
    uni.showToast({ title: '缺少客户定位参数', icon: 'none' })
    setTimeout(() => uni.navigateBack({ fail: () => uni.navigateBack() }), 600)
    return
  }
  void load()
})
</script>

<template>
  <view class="page">
    <view v-if="loading || !detail" class="hint"><text class="hint__t">加载中…</text></view>

    <template v-else>
      <!-- 客户信息 -->
      <view class="card info">
        <view class="info__row">
          <view class="info__main">
            <text class="info__phone">{{ detail.maskedPhone }}</text>
            <view class="info__meta">
              <text>{{ detail.wholesalerName }}</text>
              <text>询价 {{ detail.inquiryCount }} 次</text>
            </view>
            <view class="info__times">
              <text>最近询价 {{ fmtDateTime(detail.lastInquiryAt) }}</text>
              <text v-if="detail.lastConfirmedAt"> · 最近成交 {{ fmtDateTime(detail.lastConfirmedAt) }}</text>
            </view>
          </view>
          <view class="info__btn" @click="revealPhone">查全号</view>
        </view>
        <view class="info__notice">查全号将留痕审计，用于线下联系买家确认订单</view>
      </view>

      <!-- 档案备注 -->
      <view class="card sec">
        <view class="sec__head">
          <text class="sec__title">客户备注</text>
          <text class="sec__sub">仅本商户可见，买家不感知</text>
        </view>
        <textarea
          v-model="remarkDraft"
          class="sec__textarea"
          placeholder="记录偏好、常购品类、欠款约定等…（留空保存即清除）"
          placeholder-class="ph"
          :maxlength="200"
          auto-height
        />
        <button class="btn-primary sec__btn" :loading="remarkSaving" @click="saveRemark">保存备注</button>
      </view>

      <!-- 跟进提醒 -->
      <view class="card sec">
        <view class="sec__head">
          <text class="sec__title">跟进提醒</text>
          <text class="sec__sub">到点后管理员电脑端与站内信提醒</text>
        </view>

        <view class="rem-form">
          <textarea
            v-model="remContent"
            class="sec__textarea"
            placeholder="如：3 天后回访确认上次询价是否成交"
            placeholder-class="ph"
            :maxlength="100"
            auto-height
          />
          <view class="rem-form__row">
            <picker mode="date" :value="remDate" :start="toDateStr(new Date())" @change="onRemDateChange">
              <view class="picker">{{ remDate }}</view>
            </picker>
            <picker mode="time" :value="remTime" @change="onRemTimeChange">
              <view class="picker">{{ remTime }}</view>
            </picker>
            <button class="btn-primary rem-form__btn" :loading="remSaving" @click="addReminder">添加</button>
          </view>
        </view>

        <view v-if="detail.reminders.length" class="rem-list">
          <view v-for="r in detail.reminders" :key="String(r.id)" class="rem">
            <view class="rem__main">
              <text class="rem__content">{{ r.content }}</text>
              <text class="rem__time">{{ r.remindedAt ? `已提醒 · ${r.remindedAt}` : `待提醒 · ${r.remindAt}` }}</text>
            </view>
            <text class="rem__del" @click="removeReminder(r)">删除</text>
          </view>
        </view>
        <view v-else class="rem-empty"><text class="rem-empty__t">暂未设置跟进提醒</text></view>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.hint {
  padding: 200rpx 40rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-4;
  }
}

.card {
  background: $cc-bg-1;
  border-radius: $cc-card-radius;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);
}

.info {
  padding: 28rpx;

  &__row {
    display: flex;
    align-items: center;
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8rpx;
  }

  &__phone {
    font-size: 40rpx;
    font-weight: 800;
    color: $cc-fg-1;
    letter-spacing: 2rpx;
  }

  &__meta {
    display: flex;
    gap: 20rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__times {
    font-size: 21rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }

  &__btn {
    flex-shrink: 0;
    margin-left: 20rpx;
    padding: 14rpx 26rpx;
    border-radius: 999rpx;
    background: $cc-accent;
    color: #fff;
    font-size: 24rpx;
    font-weight: 600;
  }

  &__notice {
    margin-top: 18rpx;
    font-size: 21rpx;
    color: $cc-fg-4;
  }
}

.sec {
  margin-top: 20rpx;
  padding: 28rpx;

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 16rpx;
  }

  &__title {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__sub {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__textarea {
    width: 100%;
    min-height: 120rpx;
    padding: 20rpx;
    box-sizing: border-box;
    border: 2rpx solid $cc-border-1;
    border-radius: 12rpx;
    background: $cc-bg-2;
    font-size: 26rpx;
    color: $cc-fg-1;
    line-height: 1.6;
  }

  &__btn {
    margin-top: 18rpx;
    height: 88rpx;
    font-size: 29rpx;
  }
}

.ph {
  color: $cc-fg-4;
}

.rem-form {
  &__row {
    display: flex;
    align-items: center;
    gap: 12rpx;
    margin-top: 14rpx;
  }
}

.picker {
  padding: 12rpx 18rpx;
  border-radius: 10rpx;
  background: $cc-bg-2;
  border: 2rpx solid $cc-border-1;
  font-size: 24rpx;
  color: $cc-fg-2;
}

.rem-form__btn {
  margin-left: auto;
  width: 160rpx;
  height: 80rpx;
  font-size: 27rpx;
}

.rem-list {
  margin-top: 20rpx;
  border-top: 2rpx solid $cc-bg-3;
}

.rem {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__content {
    font-size: 26rpx;
    color: $cc-fg-1;
    line-height: 1.5;
  }

  &__time {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__del {
    flex-shrink: 0;
    font-size: 23rpx;
    color: $cc-danger;
    padding: 8rpx;
  }
}

.rem-empty {
  padding: 30rpx 0 6rpx;
  text-align: center;

  &__t {
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}
</style>
