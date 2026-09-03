<script setup lang="ts">
// ST 账单详情（F4 · 移动端 P0 操作集）：
// 只读核对（三金额 + 明细 + 回款 + 申诉）；操作按状态机开放——
// DRAFT→下发；DISPATCHED→撤回；PENDING_PAYMENT/PARTIAL_PAID→登记回款；回款行可冲销（R12 二次确认）。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { Bill, BillDetail, PaymentRecord } from '@cangchu/api-types'
import { stBillApi } from '../../../api/st'
import { readAuth, readWork } from '../../../utils/session'
import {
  DISPUTED_BANNER,
  PAY_METHOD_LABELS,
  billItemTypeLabel,
  billStatusLabel,
  billStatusTone,
  disputeStatusLabel,
  disputeStatusTone,
  fmtDateTime,
  fmtMoney,
  payMethodLabel,
  toCents,
} from '../../../utils/billing'

const PAY_METHOD_KEYS = ['BANK_TRANSFER', 'CASH', 'WX', 'ALIPAY', 'OTHER'] as const

const billId = ref('')
const detail = ref<BillDetail | null>(null)
const loading = ref(false)

const bill = computed<Bill | null>(() => detail.value?.bill ?? null)
const items = computed(() => detail.value?.items ?? [])
const payments = computed(() => detail.value?.payments ?? [])
const disputes = computed(() => detail.value?.disputes ?? [])

const canDispatch = computed(() => bill.value?.status === 'DRAFT')
const canWithdraw = computed(() => bill.value?.status === 'DISPATCHED')
const canRegister = computed(() => bill.value?.status === 'PENDING_PAYMENT' || bill.value?.status === 'PARTIAL_PAID')
const frozen = computed(() => bill.value?.status === 'DISPUTED')
const settled = computed(() => bill.value?.status === 'PAID')

// ---- 回款登记 sheet ----
const showPay = ref(false)
const payAmount = ref('')
const payDate = ref('')
const payMethod = ref<string>('BANK_TRANSFER')
const payRemark = ref('')
const paying = ref(false)

// ---- 回款冲销 sheet ----
const reverseTarget = ref<PaymentRecord | null>(null)
const reverseReason = ref('')
const reverseConfirmed = ref(false)
const reversing = ref(false)

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId)
}

async function fetchDetail(): Promise<void> {
  if (!billId.value) return
  loading.value = true
  try {
    detail.value = await stBillApi.detail(billId.value)
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function itemText(it: { itemType: string | null; skuName?: string | null; periodStart?: string | null; periodEnd?: string | null; description?: string | null }): string {
  if (it.itemType === 'STORAGE') {
    return `${it.skuName ?? '货品'} · ${(it.periodStart ?? '').slice(5)} ~ ${(it.periodEnd ?? '').slice(5)}`
  }
  return it.description ?? ''
}

function payMethodLabelOf(m: string | null | undefined): string {
  return payMethodLabel(m)
}

function itemTypeLabel(t: string | null | undefined): string {
  return billItemTypeLabel(t)
}

// ---- 下发 / 撤回 ----
function onDispatch(): void {
  const b = bill.value
  if (!b) return
  uni.showModal({
    title: '下发账单',
    content: `将「${b.billingMonth} 账期 · ${b.wholesalerName}」账单下发，商户将收到核对通知。`,
    confirmText: '确认下发',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await stBillApi.dispatch(b.id)
        uni.showToast({ title: '已下发，等待商户核对', icon: 'success' })
        await fetchDetail()
      } catch {
        // request 已 toast
      }
    },
  })
}

function onWithdraw(): void {
  const b = bill.value
  if (!b) return
  uni.showModal({
    title: '撤回下发',
    content: '撤回后账单回到「待核对」。商户已确认或已有回款的账单不可撤回。',
    confirmText: '确认撤回',
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await stBillApi.withdraw(b.id)
        uni.showToast({ title: '已撤回', icon: 'success' })
        await fetchDetail()
      } catch {
        // request 已 toast
      }
    },
  })
}

// ---- 回款登记 ----
function openPaySheet(): void {
  const b = bill.value
  if (!b) return
  payAmount.value = (b.outstandingAmount ?? 0).toFixed(2)
  const now = new Date()
  const pad = (n: number): string => String(n).padStart(2, '0')
  payDate.value = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  payMethod.value = 'BANK_TRANSFER'
  payRemark.value = ''
  showPay.value = true
}

function onPayDateChange(e: { detail: { value: string } }): void {
  payDate.value = e.detail.value
}

function pickPayMethod(m: string): void {
  payMethod.value = m
}

async function submitPay(): Promise<void> {
  const b = bill.value
  if (!b) return
  const cents = toCents(payAmount.value)
  const remainCents = toCents(b.outstandingAmount ?? 0)
  if (!Number.isFinite(cents) || cents <= 0) {
    uni.showToast({ title: '请输入正确的收款金额', icon: 'none' })
    return
  }
  if (cents > remainCents) {
    uni.showToast({ title: `金额不能超过未收 ${fmtMoney(b.outstandingAmount)}`, icon: 'none' })
    return
  }
  if (!payDate.value) {
    uni.showToast({ title: '请选择收款日期', icon: 'none' })
    return
  }
  if (paying.value) return
  paying.value = true
  try {
    await stBillApi.registerPayment(b.id, {
      amount: Math.round(cents) / 100,
      payAt: `${payDate.value}T00:00:00`,
      payMethod: payMethod.value as 'BANK_TRANSFER' | 'CASH' | 'WX' | 'ALIPAY' | 'OTHER',
      remark: payRemark.value.trim() || undefined,
    })
    uni.showToast({ title: '回款已登记', icon: 'success' })
    showPay.value = false
    await fetchDetail()
  } catch {
    // request 已 toast
  } finally {
    paying.value = false
  }
}

// ---- 回款冲销 ----
function openReverseSheet(p: PaymentRecord): void {
  reverseTarget.value = p
  reverseReason.value = ''
  reverseConfirmed.value = false
}

function closeReverseSheet(): void {
  if (reversing.value) return
  reverseTarget.value = null
  reverseReason.value = ''
  reverseConfirmed.value = false
}

function toggleReverseConfirmed(): void {
  reverseConfirmed.value = !reverseConfirmed.value
}

async function submitReverse(): Promise<void> {
  const p = reverseTarget.value
  if (!p) return
  if (!reverseReason.value.trim()) {
    uni.showToast({ title: '请填写冲销理由', icon: 'none' })
    return
  }
  if (!reverseConfirmed.value) {
    uni.showToast({ title: '请勾选二次确认', icon: 'none' })
    return
  }
  if (reversing.value) return
  reversing.value = true
  try {
    await stBillApi.reversePayment(p.id, { reason: reverseReason.value.trim(), confirmed: true })
    uni.showToast({ title: '回款已冲销', icon: 'success' })
    closeReverseSheet()
    await fetchDetail()
  } catch {
    // request 已 toast
  } finally {
    reversing.value = false
  }
}

onLoad((options) => {
  if (!guard()) {
    goLogin()
    return
  }
  billId.value = String(options?.id ?? '')
  if (billId.value) void fetchDetail()
})
</script>

<template>
  <view class="page" :class="{ 'page--with-bar': !settled && !frozen }">
    <!-- 头部卡 -->
    <view v-if="bill" class="head">
      <view class="head__top">
        <text class="head__ws">{{ bill.wholesalerName }}</text>
        <text class="tag" :class="`tag--${billStatusTone(bill.status)}`">{{ billStatusLabel(bill.status) }}</text>
      </view>
      <text class="head__no mono">{{ bill.billNo }}</text>
      <view class="head__meta">
        <text>{{ bill.billingMonth }} 账期（{{ bill.periodStart }} ~ {{ bill.periodEnd }}）</text>
      </view>
    </view>

    <!-- 争议冻结横幅 -->
    <view v-if="frozen" class="banner">{{ DISPUTED_BANNER }}</view>

    <!-- 金额卡 -->
    <view v-if="bill" class="card">
      <view class="amt-row">
        <text class="amt-row__label">仓储费小计</text>
        <text class="amt-row__val">{{ fmtMoney(bill.subtotalAmount) }}</text>
      </view>
      <view class="amt-row">
        <text class="amt-row__label">调整合计</text>
        <text class="amt-row__val" :class="{ 'amt-row__val--minus': (bill.adjustAmount ?? 0) < 0 }">{{ fmtMoney(bill.adjustAmount) }}</text>
      </view>
      <view class="amt-row amt-row--total">
        <text class="amt-row__label">应收</text>
        <text class="amt-row__val amt-row__val--strong">{{ fmtMoney(bill.totalAmount) }}</text>
      </view>
      <view class="amt-row">
        <text class="amt-row__label">已收</text>
        <text class="amt-row__val amt-row__val--ok">{{ fmtMoney(bill.paidAmount) }}</text>
      </view>
      <view class="amt-row amt-row--out">
        <text class="amt-row__label">未收</text>
        <text class="amt-row__val amt-row__val--warn">{{ fmtMoney(bill.outstandingAmount) }}</text>
      </view>
    </view>

    <!-- 明细 -->
    <view class="card">
      <view class="card__title">账单明细（{{ items.length }}）</view>
      <view v-if="items.length">
        <view
          v-for="it in items"
          :key="String(it.id)"
          class="item"
          :class="{ 'item--rev': it.reversed, 'item--neg': (it.amount ?? 0) < 0 }"
        >
          <view class="item__main">
            <text class="item__type">{{ itemTypeLabel(it.itemType) }}</text>
            <text v-if="it.reversed" class="item__rev-mark">已冲销</text>
            <text class="item__desc">{{ itemText(it) }}</text>
          </view>
          <text class="item__amount">{{ fmtMoney(it.amount) }}</text>
        </view>
      </view>
      <view v-else class="card__empty">暂无明细</view>
    </view>

    <!-- 回款记录 -->
    <view class="card">
      <view class="card__title">回款记录（{{ payments.length }}）</view>
      <view v-if="payments.length">
        <view v-for="p in payments" :key="String(p.id)" class="pay" :class="{ 'pay--rev': p.status === 'REVERSED' }">
          <view class="pay__main">
            <view class="pay__line1">
              <text class="pay__method">{{ payMethodLabelOf(p.payMethod) }}</text>
              <text class="pay__time">{{ fmtDateTime(p.payAt) }}</text>
            </view>
            <view class="pay__line2">
              <text v-if="p.remark" class="pay__remark">{{ p.remark }}</text>
              <text v-if="p.status === 'REVERSED'" class="pay__rev-tag">已冲销{{ p.reverseReason ? `：${p.reverseReason}` : '' }}</text>
            </view>
          </view>
          <view class="pay__right">
            <text class="pay__amount" :class="{ 'pay__amount--rev': p.status === 'REVERSED' }">{{ fmtMoney(p.amount) }}</text>
            <text v-if="p.status === 'EFFECTIVE'" class="pay__act" @click="openReverseSheet(p)">冲销</text>
          </view>
        </view>
      </view>
      <view v-else class="card__empty">暂无回款记录</view>
    </view>

    <!-- 申诉记录 -->
    <view class="card">
      <view class="card__title">申诉记录（{{ disputes.length }}）</view>
      <view v-if="disputes.length">
        <view v-for="d in disputes" :key="String(d.id)" class="dispute">
          <view class="dispute__head">
            <text class="tag" :class="`tag--${disputeStatusTone(d.status)}`">{{ disputeStatusLabel(d.status) }}</text>
            <text class="dispute__time">{{ fmtDateTime(d.createdAt) }}</text>
          </view>
          <text class="dispute__reason">商户申诉：{{ d.reason }}</text>
          <text v-if="d.resolution" class="dispute__res">处理说明：{{ d.resolution }}</text>
        </view>
      </view>
      <view v-else class="card__empty">暂无申诉</view>
    </view>

    <view class="page__pad" />

    <!-- 底部操作栏 -->
    <view v-if="bill && !settled && !frozen" class="actionbar">
      <view class="actionbar__remain">
        <text class="actionbar__label">未收</text>
        <text class="actionbar__val">{{ fmtMoney(bill.outstandingAmount) }}</text>
      </view>
      <button v-if="canDispatch" class="btn-primary actionbar__btn" @click="onDispatch">下发账单</button>
      <button v-else-if="canWithdraw" class="btn-primary actionbar__btn actionbar__btn--danger" @click="onWithdraw">撤回下发</button>
      <button v-else-if="canRegister" class="btn-primary actionbar__btn" @click="openPaySheet">登记回款</button>
    </view>

    <!-- 回款登记 sheet -->
    <view v-if="showPay" class="mask" @click="showPay = false">
      <view class="sheet" @click.stop>
        <view class="sheet__title">登记回款</view>
        <view class="sheet__sub">
          {{ bill?.wholesalerName }} · {{ bill?.billingMonth }} 账期 · 未收 {{ fmtMoney(bill?.outstandingAmount) }}
        </view>

        <view class="pay-block">
          <text class="pay-block__label">收款金额（元）</text>
          <view class="pay-block__money">
            <text class="pay-block__cur">¥</text>
            <input v-model="payAmount" class="pay-block__input" type="digit" placeholder="0.00" placeholder-class="ph" />
          </view>
        </view>

        <view class="row">
          <text class="row__label">收款日期</text>
          <picker mode="date" :value="payDate" @change="onPayDateChange">
            <view class="row__picker">{{ payDate }}</view>
          </picker>
        </view>

        <view class="row row--col">
          <text class="row__label">收款方式</text>
          <view class="chips">
            <view
              v-for="m in PAY_METHOD_KEYS"
              :key="m"
              class="chip"
              :class="{ 'chip--on': payMethod === m }"
              @click="pickPayMethod(m)"
            >{{ PAY_METHOD_LABELS[m] }}</view>
          </view>
        </view>

        <view class="row row--col">
          <text class="row__label">备注（可选）</text>
          <input v-model="payRemark" class="row__input" placeholder="转账流水号等" placeholder-class="ph" maxlength="100" />
        </view>

        <button class="btn-primary sheet__submit" :loading="paying" @click="submitPay">确认登记</button>
      </view>
    </view>

    <!-- 回款冲销 sheet -->
    <view v-if="reverseTarget" class="mask" @click="closeReverseSheet">
      <view class="sheet" @click.stop>
        <view class="sheet__title">冲销这笔回款</view>
        <view class="sheet__sub">
          {{ payMethodLabelOf(reverseTarget.payMethod) }} {{ fmtMoney(reverseTarget.amount) }} · {{ fmtDateTime(reverseTarget.payAt) }}
        </view>
        <view class="row row--col">
          <text class="row__label">冲销理由（必填）</text>
          <textarea
            v-model="reverseReason"
            class="row__textarea"
            placeholder="登记错误 / 商户实际未到账等"
            placeholder-class="ph"
            :maxlength="200"
          />
        </view>
        <view class="confirm-line" @click="toggleReverseConfirmed">
          <view class="confirm-line__box" :class="{ 'confirm-line__box--on': reverseConfirmed }">
            {{ reverseConfirmed ? '✓' : '' }}
          </view>
          <text class="confirm-line__t">我确认冲销该笔回款，冲销后不可恢复</text>
        </view>
        <button class="btn-primary sheet__submit sheet__submit--danger" :loading="reversing" @click="submitReverse">
          确认冲销
        </button>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx;
  box-sizing: border-box;

  &--with-bar {
    padding-bottom: calc(150rpx + env(safe-area-inset-bottom));
  }

  &__pad {
    height: 20rpx;
  }
}

.head {
  padding: 26rpx 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);
  margin-bottom: 20rpx;

  &__top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12rpx;
  }

  &__ws {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__no {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__meta {
    margin-top: 10rpx;
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

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

.banner {
  padding: 20rpx 22rpx;
  margin-bottom: 20rpx;
  border-radius: 12rpx;
  background: $cc-danger-bg;
  color: $cc-danger;
  font-size: 23rpx;
  line-height: 1.6;
}

.card {
  padding: 26rpx 24rpx;
  margin-bottom: 20rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__title {
    margin-bottom: 12rpx;
    font-size: 27rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__empty {
    padding: 24rpx 0 8rpx;
    text-align: center;
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}

/* 金额 */
.amt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 0;

  & + & {
    border-top: 2rpx solid $cc-bg-2;
  }

  &--total {
    border-top: none;
    padding-top: 4rpx;
  }

  &--out {
    border-top: none;
    background: $cc-warning-bg;
    margin: 8rpx -10rpx 0;
    padding: 12rpx 10rpx;
    border-radius: 10rpx;
  }

  &__label {
    font-size: 25rpx;
    color: $cc-fg-3;
  }

  &__val {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-1;

    &--strong {
      font-size: 30rpx;
    }

    &--ok {
      color: $cc-success;
    }

    &--warn {
      color: $cc-warning;
    }

    &--minus {
      color: $cc-danger;
    }
  }
}

/* 明细 */
.item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &--rev {
    opacity: 0.55;
    text-decoration: line-through;
  }

  &--neg &__amount {
    color: $cc-danger;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__type {
    font-size: 21rpx;
    color: $cc-accent;
  }

  &__rev-mark {
    align-self: flex-start;
    font-size: 18rpx;
    padding: 0 8rpx;
    border-radius: 4rpx;
    background: $cc-bg-3;
    color: $cc-fg-3;
  }

  &__desc {
    font-size: 24rpx;
    color: $cc-fg-2;
    line-height: 1.5;
    word-break: break-all;
  }

  &__amount {
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

/* 回款 */
.pay {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &:last-child {
    border-bottom: none;
  }

  &--rev {
    opacity: 0.6;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__line1 {
    display: flex;
    align-items: center;
    gap: 14rpx;
  }

  &__method {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__time {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__line2 {
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__remark {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__rev-tag {
    font-size: 21rpx;
    color: $cc-danger;
  }

  &__right {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 8rpx;
  }

  &__amount {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-success;

    &--rev {
      color: $cc-fg-3;
      text-decoration: line-through;
    }
  }

  &__act {
    font-size: 22rpx;
    color: $cc-danger;
    padding: 4rpx 12rpx;
    border: 2rpx solid $cc-danger;
    border-radius: 6rpx;
  }
}

/* 申诉 */
.dispute {
  padding: 18rpx 0;
  border-bottom: 2rpx solid $cc-bg-2;

  &:last-child {
    border-bottom: none;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8rpx;
  }

  &__time {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__reason {
    display: block;
    font-size: 24rpx;
    color: $cc-fg-2;
    line-height: 1.5;
  }

  &__res {
    display: block;
    margin-top: 6rpx;
    font-size: 22rpx;
    color: $cc-fg-3;
    line-height: 1.5;
  }
}

/* 底部操作栏 */
.actionbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background: $cc-bg-1;
  box-shadow: 0 -2rpx 16rpx rgba(2, 6, 23, 0.06);

  &__remain {
    display: flex;
    flex-direction: column;
    gap: 2rpx;
    min-width: 150rpx;
  }

  &__label {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__val {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-warning;
  }

  &__btn {
    flex: 1;
    height: 92rpx;
    font-size: 30rpx;
    font-weight: 600;

    &--danger {
      background: $cc-danger;
    }
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

  &__sub {
    margin: 10rpx 0 24rpx;
    font-size: 23rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }

  &__submit {
    height: 96rpx;
    margin-top: 30rpx;
    font-size: 31rpx;
    font-weight: 600;

    &--danger {
      background: $cc-danger;
    }
  }
}

.pay-block {
  padding: 22rpx;
  margin-bottom: 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;

  &__label {
    display: block;
    font-size: 22rpx;
    color: $cc-fg-3;
    margin-bottom: 10rpx;
  }

  &__money {
    display: flex;
    align-items: baseline;
    gap: 8rpx;
  }

  &__cur {
    font-size: 36rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__input {
    flex: 1;
    font-size: 48rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;

  &--col {
    flex-direction: column;
    align-items: stretch;
    gap: 14rpx;
  }

  &__label {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__picker {
    font-size: 26rpx;
    color: $cc-accent;
    padding: 8rpx 0;
  }

  &__input {
    height: 76rpx;
    padding: 0 20rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 10rpx;
    font-size: 26rpx;
    box-sizing: border-box;
  }

  &__textarea {
    width: 100%;
    height: 140rpx;
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
  flex-wrap: wrap;
  gap: 12rpx;
}

.chip {
  padding: 12rpx 24rpx;
  border-radius: 999rpx;
  background: $cc-bg-2;
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

.confirm-line {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  margin-top: 6rpx;
  padding: 16rpx;
  border-radius: 10rpx;
  background: $cc-danger-bg;

  &__box {
    flex-shrink: 0;
    width: 36rpx;
    height: 36rpx;
    line-height: 36rpx;
    text-align: center;
    border-radius: 8rpx;
    background: $cc-bg-1;
    color: #fff;
    font-size: 24rpx;
    font-weight: 700;

    &--on {
      background: $cc-danger;
    }
  }

  &__t {
    flex: 1;
    font-size: 23rpx;
    line-height: 1.5;
    color: $cc-danger;
  }
}
</style>
