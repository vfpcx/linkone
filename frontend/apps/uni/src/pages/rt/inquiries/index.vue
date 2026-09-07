<script setup lang="ts">
// RT「我的意向单」（F2 · US-RT-04；F6 加登录态 + 联系方式 reveal）：
// 本店 + 本手机号全部询价单及状态。
// 后端仅回尾号归属提示；状态过滤在前端做（PENDING/CONFIRMED → 进行中，COMPLETED/VOIDED → 已结束）。
// F6（D-RT-01）：登录买家账号后手机号锁定为登录号；CONFIRMED/COMPLETED 单可「查看批发商电话」。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { RtMyInquiries, RtMyInquiriesSummary } from '@cangchu/api-types'
import { rtApi } from '../../../api/rt'
import { PHONE_MAX_LEN, PHONE_RE } from '../../../config'
import { hasRtScope } from '../../../utils/session'
import { fmtDateTime, fmtMoney, fmtPrice } from '../../../utils/format'
import { getSavedPhone, savePhone } from '../../../utils/storage'

type FilterKey = 'all' | 'ongoing' | 'done'

const STATUS_TEXT: Record<RtMyInquiriesSummary['status'], string> = {
  PENDING: '待商户确认',
  CONFIRMED: '已确认 · 待送货',
  COMPLETED: '已完成',
  VOIDED: '已作废',
}

let storeCode = ''

const phoneInput = ref('')
const data = ref<RtMyInquiries | null>(null)
const loading = ref(false)
const loaded = ref(false)
const errMsg = ref('')
const filter = ref<FilterKey>('all')
// F6：登录态 / 联系方式 reveal
const rtLogged = ref(false)
const queriedPhone = ref('')
const contactingId = ref('')
const contactPhone = ref('')
const showContact = ref(false)

const filtered = computed(() => {
  const list = data.value?.inquiries ?? []
  if (filter.value === 'all') return list
  if (filter.value === 'ongoing') return list.filter((i) => i.status === 'PENDING' || i.status === 'CONFIRMED')
  return list.filter((i) => i.status === 'COMPLETED' || i.status === 'VOIDED')
})

function statusText(s: RtMyInquiriesSummary['status']): string {
  return STATUS_TEXT[s]
}

function statusClass(s: RtMyInquiriesSummary['status']): string {
  if (s === 'PENDING') return 'chip-st--warn'
  if (s === 'CONFIRMED') return 'chip-st--ok'
  if (s === 'COMPLETED') return 'chip-st--done'
  return 'chip-st--void'
}

async function query(): Promise<void> {
  const p = phoneInput.value.trim()
  if (!PHONE_RE.test(p)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  if (loading.value) return
  loading.value = true
  errMsg.value = ''
  try {
    savePhone(p)
    data.value = await rtApi.getMyInquiries({ code: storeCode, rtPhone: p })
    queriedPhone.value = p
    uni.setNavigationBarTitle({ title: data.value.storeName || '我的意向单' })
  } catch {
    // request 已 toast
    data.value = null
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function itemTotal(s: RtMyInquiriesSummary): { count: number; amount: number } {
  const count = s.items.reduce((a, it) => a + it.qty, 0)
  const amount = s.items.reduce((a, it) => a + it.qty * (it.dealPrice ?? it.unitPriceSnapshot), 0)
  return { count, amount }
}

function goStore(): void {
  uni.reLaunch({ url: `/pages/rt/store/index?code=${encodeURIComponent(storeCode)}` })
}

/** F6：跳买家登录页（登录成功后 navigateBack 回本页并自动刷新） */
function goRtLogin(): void {
  uni.navigateTo({ url: '/pages/rt/login/index' })
}

function maskPhone(p: string): string {
  return p.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2')
}

/** 已确认/已完成单可查看批发商电话（US-RT-04 / D-RT-01）：未登录先引导登录 */
async function onContact(s: RtMyInquiriesSummary): Promise<void> {
  if (!rtLogged.value) {
    uni.showModal({
      title: '需要先登录买家账号',
      content: '意向单确认后，登录本账号即可查看批发商电话，与商户线下成交。',
      confirmText: '去登录',
      cancelText: '暂不',
      confirmColor: '#059669',
      success: (r) => {
        if (r.confirm) goRtLogin()
      },
    })
    return
  }
  if (contactingId.value) return
  contactingId.value = s.inquiryId
  try {
    const res = await rtApi.getWholesalerContact(s.inquiryId)
    contactPhone.value = res.phone
    showContact.value = true
  } catch {
    // request 已 toast（非本人 / 未确认 → 后端 50402 文案直达）
  } finally {
    contactingId.value = ''
  }
}

function copyPhone(): void {
  uni.setClipboardData({
    data: contactPhone.value,
    success: () => {
      uni.showToast({ title: '已复制电话号码', icon: 'none' })
    },
  })
}

function callPhone(): void {
  // #ifdef MP-WEIXIN
  uni.makePhoneCall({
    phoneNumber: contactPhone.value,
    fail: () => {
      uni.showToast({ title: '无法拨号，可先复制号码', icon: 'none' })
    },
  })
  // #endif
  // #ifndef MP-WEIXIN
  window.location.href = `tel:${contactPhone.value}`
  // #endif
}

onLoad((options) => {
  storeCode = decodeURIComponent((options?.code as string) ?? '')
})

onShow(() => {
  rtLogged.value = hasRtScope()
  if (rtLogged.value) {
    // 登录态：手机号恒锁定为登录账号（登录页已 savePhone 覆盖），防止查询号与账号不一致导致 reveal 403
    phoneInput.value = getSavedPhone()
  } else if (!phoneInput.value) {
    phoneInput.value = getSavedPhone()
  }
  // 登录返回本页/首开自动按登录号查询
  if (rtLogged.value && phoneInput.value && PHONE_RE.test(phoneInput.value) && queriedPhone.value !== phoneInput.value) {
    query()
  }
})

onPullDownRefresh(async () => {
  if (data.value && phoneInput.value) await query()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 查询头（F6：登录后锁定为登录账号手机号） -->
    <view class="q-head">
      <view class="q-head__row">
        <input
          v-model="phoneInput"
          class="q-head__input"
          type="number"
          :maxlength="PHONE_MAX_LEN"
          placeholder="输入查询手机号"
          placeholder-class="ph"
          :disabled="rtLogged"
          confirm-type="search"
          @confirm="query"
        />
        <button class="btn-primary q-head__btn" :loading="loading" @click="query">{{ rtLogged ? '刷新' : '查询' }}</button>
      </view>
      <view v-if="rtLogged" class="q-head__login">
        <text class="q-head__login-tag">买家已登录</text>
        <text class="q-head__login-sub">手机号锁定为 {{ maskPhone(phoneInput || '') }} · 确认/完成单可直接联系批发商</text>
      </view>
      <view v-else-if="data" class="q-head__meta">
        <text>{{ data.storeName }} · 尾号 {{ data.rtPhoneLast4 }}</text>
      </view>
    </view>

    <!-- 状态过滤 -->
    <view v-if="data?.inquiries.length" class="filters">
      <view
        v-for="f in [
          { key: 'all', label: '全部' },
          { key: 'ongoing', label: '进行中' },
          { key: 'done', label: '已结束' },
        ] as Array<{ key: FilterKey; label: string }>"
        :key="f.key"
        class="fchip"
        :class="{ 'fchip--on': filter === f.key }"
        @click="filter = f.key"
      >
        {{ f.label }}
      </view>
    </view>

    <!-- 内容 -->
    <view v-if="!loaded && !data" class="hint">
      <text class="hint__text">输入提交意向单时的手机号，查看本店订单与处理状态</text>
    </view>
    <view v-else-if="data && !filtered.length" class="hint">
      <text class="hint__text">{{ data.inquiries.length ? '该分类暂无记录' : '暂无意向单，去店铺逛逛吧' }}</text>
      <button class="btn-ghost hint__btn" @click="goStore">去进店</button>
    </view>

    <view v-else-if="filtered.length" class="list">
      <view v-for="s in filtered" :key="s.inquiryId" class="inquiry">
        <view class="inquiry__head">
          <text class="inquiry__doc">{{ s.docNo }}</text>
          <text class="chip-st" :class="statusClass(s.status)">{{ statusText(s.status) }}</text>
        </view>
        <view class="inquiry__sub">
          <text>{{ s.wholesalerName ?? '商户' }}</text>
          <text class="inquiry__time">{{ fmtDateTime(s.createdAt) }}</text>
        </view>
        <view class="inquiry__items">
          <view v-for="it in s.items" :key="it.skuId" class="row">
            <view class="row__main">
              <text class="row__name">{{ it.name ?? '商品' }}</text>
              <text v-if="it.spec" class="row__spec">{{ it.spec }}</text>
            </view>
            <text class="row__qty">×{{ it.qty }}</text>
            <text class="row__price">{{ fmtPrice(it.dealPrice ?? it.unitPriceSnapshot) }}</text>
          </view>
        </view>
        <view class="inquiry__foot">
          <text class="inquiry__total">
            共 {{ itemTotal(s).count }} 件 · {{ fmtMoney(itemTotal(s).amount) }}
          </text>
          <text v-if="s.status === 'PENDING'" class="inquiry__tip">商户确认后将短信通知你</text>
          <text v-else-if="s.status === 'CONFIRMED'" class="inquiry__tip">已确认 · 可联系批发商送货</text>
          <text v-else-if="s.status === 'VOIDED'" class="inquiry__tip">本单已作废，可与商户沟通后重新询价</text>
          <text v-else class="inquiry__tip">已成交，感谢惠顾</text>
        </view>
        <!-- F6 · US-RT-04 / D-RT-01：确认/完成后可查批发商电话（需 RT 登录） -->
        <view
          v-if="s.status === 'CONFIRMED' || s.status === 'COMPLETED'"
          class="inquiry__actions"
          @click.stop
        >
          <text class="inquiry__actions-note">{{ rtLogged ? '登录本账号可查看批发商电话' : '登录后可查看批发商电话' }}</text>
          <button class="btn-contact" :loading="contactingId === s.inquiryId" @click="onContact(s)">查看批发商电话</button>
        </view>
      </view>
    </view>

    <!-- F6 · D-RT-01：批发商电话弹层（拨打/复制，H5 tel: / 微信 makePhoneCall） -->
    <view v-if="showContact" class="mask" @click="showContact = false">
      <view class="contact" @click.stop>
        <text class="contact__label">批发商联系电话</text>
        <text class="contact__phone">{{ contactPhone }}</text>
        <view class="contact__actions">
          <button class="contact__btn" @click="copyPhone">复制号码</button>
          <button class="contact__btn contact__btn--primary" @click="callPhone">拨打</button>
        </view>
        <text class="contact__tip">仅供线下成交联系使用，请勿转发他人</text>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: $cc-bg-2;
  box-sizing: border-box;
}

/* ===== 查询头 ===== */
.q-head {
  padding: 8rpx 4rpx 24rpx;

  &__row {
    display: flex;
    gap: 16rpx;
  }

  &__input {
    flex: 1;
    height: 88rpx;
    padding: 0 24rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 12rpx;
    background: $cc-bg-1;
    font-size: 30rpx;
    letter-spacing: 2rpx;
    color: $cc-fg-1;
    box-sizing: border-box;
  }

  &__btn {
    width: 200rpx;
    height: 88rpx;
    line-height: 88rpx;
    margin: 0;
    border-radius: 12rpx;
    font-size: 30rpx;
    font-weight: 600;
  }

  &__meta {
    margin-top: 14rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }
}

.ph {
  color: $cc-fg-4;
}

/* ===== 状态过滤 ===== */
.filters {
  display: flex;
  gap: 14rpx;
  margin-bottom: 20rpx;
}

.fchip {
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  color: $cc-fg-2;
  font-size: 26rpx;

  &--on {
    background: $cc-rt-accent;
    border-color: $cc-rt-accent;
    color: #fff;
    font-weight: 600;
  }
}

/* ===== 提示 / 空态 ===== */
.hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 28rpx;
  padding: 140rpx 40rpx;

  &__text {
    font-size: 26rpx;
    color: $cc-fg-4;
    text-align: center;
    line-height: 1.7;
  }

  &__btn {
    width: 280rpx;
    height: 84rpx;
    line-height: 84rpx;
    border-radius: 12rpx;
    font-size: 28rpx;
  }
}

/* ===== 意向单卡 ===== */
.list {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.inquiry {
  padding: 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__doc {
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sub {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 10rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__time {
    color: $cc-fg-4;
  }

  &__items {
    margin-top: 18rpx;
    padding: 18rpx;
    border-radius: 12rpx;
    background: $cc-bg-2;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 16rpx;
  }

  &__total {
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-danger;
  }

  &__tip {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 8rpx 0;

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 12rpx;
  }

  &__name {
    font-size: 27rpx;
    color: $cc-fg-1;
  }

  &__spec {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__qty {
    font-size: 26rpx;
    color: $cc-fg-2;
  }

  &__price {
    width: 140rpx;
    text-align: right;
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

/* ===== 状态 chip ===== */
.chip-st {
  font-size: 22rpx;
  padding: 4rpx 16rpx;
  border-radius: 8rpx;
  font-weight: 500;

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }

  &--ok {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--done {
    background: $cc-bg-3;
    color: $cc-fg-2;
  }

  &--void {
    background: $cc-danger-bg;
    color: $cc-danger;
  }
}

/* ===== F6：登录态 banner / 输入锁定 ===== */
.q-head__input[disabled] {
  background: $cc-bg-3;
  color: $cc-fg-3;
}

.q-head__login {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
  padding: 12rpx 16rpx;
  border-radius: 10rpx;
  background: $cc-success-bg;

  &-tag {
    flex-shrink: 0;
    padding: 4rpx 14rpx;
    border-radius: 8rpx;
    background: $cc-success;
    color: #fff;
    font-size: 22rpx;
    font-weight: 600;
  }

  &-sub {
    font-size: 22rpx;
    color: $cc-success;
    line-height: 1.5;
  }
}

/* ===== F6：查看批发商电话（US-RT-04 / D-RT-01） ===== */
.inquiry__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 2rpx dashed $cc-border-1;

  &-note {
    flex: 1;
    min-width: 0;
    font-size: 22rpx;
    color: $cc-fg-4;
    line-height: 1.5;
  }
}

.btn-contact {
  height: 64rpx;
  padding: 0 28rpx;
  margin: 0;
  line-height: 64rpx;
  border-radius: 10rpx;
  background: $cc-success-bg;
  border: 2rpx solid $cc-success;
  color: $cc-success;
  font-size: 24rpx;
  font-weight: 600;

  &::after {
    border: none;
  }
}

.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40rpx;
  background: rgba(15, 23, 42, 0.5);
}

.contact {
  width: 100%;
  max-width: 560rpx;
  padding: 40rpx 32rpx 28rpx;
  border-radius: 24rpx;
  background: $cc-bg-1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;

  &__label {
    font-size: 26rpx;
    color: $cc-fg-3;
  }

  &__phone {
    font-size: 56rpx;
    font-weight: 700;
    letter-spacing: 4rpx;
    color: $cc-fg-1;
  }

  &__actions {
    display: flex;
    gap: 20rpx;
    margin-top: 16rpx;
    width: 100%;
  }

  &__btn {
    flex: 1;
    height: 84rpx;
    margin: 0;
    line-height: 84rpx;
    border-radius: 12rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;
    font-size: 28rpx;
    font-weight: 600;

    &::after {
      border: none;
    }

    &--primary {
      background: $cc-rt-accent;
      color: #fff;
    }
  }

  &__tip {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}
</style>
