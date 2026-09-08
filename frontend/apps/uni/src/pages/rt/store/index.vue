<script setup lang="ts">
// RT 店铺页（F2 正式多端）：浏览商户在库商品 → 步进选择 → 底部估价条 → 提交意向单。
// 另含：我的价目（专属价 sheet）/ 我的意向单（跳转）。手机号 = 本地身份，未绑定时提交流程内联收集。
import { computed, reactive, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { Inquiry, RtPriceGroup, RtStoreFront, RtStoreSku } from '@cangchu/api-types'
import { rtApi } from '../../../api/rt'
import { PHONE_MAX_LEN, PHONE_RE } from '../../../config'
import { fmtMoney, fmtPrice } from '../../../utils/format'
import { addRecentStore, getSavedPhone, savePhone } from '../../../utils/storage'

const store = ref<RtStoreFront | null>(null)
const loading = ref(true)
const loadErr = ref('')

const activeWaId = ref('')
/** 各 SKU 已选数量（key=skuId，0 视同未选） */
const qtyMap = reactive<Record<string, number>>({})
const submitting = ref(false)

// 手机号（本地身份）
const savedPhone = ref('')
const showPhoneSheet = ref(false)
const phoneDraft = ref('')
/** 输入手机号后要续做的动作 */
const phoneAction = ref<'submit' | 'price'>('submit')

// 提交成功弹层
const done = ref<{ docNo: string; count: number; amount: number } | null>(null)

// 我的价目弹层
const showPriceSheet = ref(false)
const priceData = ref<{ phoneLast4: string; groups: RtPriceGroup[] } | null>(null)
const priceLoading = ref(false)

let storeCode = ''

const currentWholesaler = computed(() => {
  const s = store.value
  if (!s) return null
  return s.wholesalers.find((w) => w.wholesalerId === activeWaId.value) ?? s.wholesalers[0] ?? null
})

function qty(skuId: string): number {
  return qtyMap[skuId] ?? 0
}

function setQty(skuId: string, v: number): void {
  if (v <= 0) delete qtyMap[skuId]
  else qtyMap[skuId] = v
}

function inc(sku: RtStoreSku): void {
  // B-RT-03 缺货下单护栏：步进上限 = 当前库存，UI 无法选超量（后端下单不校验库存，超量会在 WA 确认时才被拒 50251）
  if (qty(sku.skuId) >= sku.stockQty) return
  setQty(sku.skuId, qty(sku.skuId) + 1)
}

function dec(sku: RtStoreSku): void {
  setQty(sku.skuId, qty(sku.skuId) - 1)
}

function onQtyInput(sku: RtStoreSku, e: any): void {
  const n = Math.floor(Number(e?.detail?.value ?? ''))
  if (Number.isNaN(n)) return
  setQty(sku.skuId, Math.min(n, sku.stockQty))
}

function goBack(): void {
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) })
}

// 当前商户已选行（估价展示用，提交同样按此）
const currentCart = computed(() => {
  const w = currentWholesaler.value
  if (!w) return []
  return w.skus
    .filter((s) => qty(s.skuId) > 0)
    .map((s) => {
      const q = qty(s.skuId)
      // 试算：未达起批按公开单价，达到起批提示起批价；实际成交以商户确认价为准
      const unit = q >= s.moqQty ? Math.min(s.unitPrice, s.moqPrice) : s.unitPrice
      return { sku: s, q, unit }
    })
})

const cartSummary = computed(() => {
  const rows = currentCart.value
  const count = rows.reduce((acc, r) => acc + r.q, 0)
  const amount = rows.reduce((acc, r) => acc + r.q * r.unit, 0)
  return { count, amount }
})

const hasPhone = computed(() => PHONE_RE.test(savedPhone.value))

function ensurePhone(): void {
  if (!hasPhone.value) {
    showPhoneSheet.value = true
    return
  }
  doSubmit()
}

function submitPrice(): void {
  showPhoneSheet.value = false
  openPrice()
}

function onPhoneConfirm(): void {
  const p = phoneDraft.value.trim()
  if (!PHONE_RE.test(p)) {
    uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })
    return
  }
  savePhone(p)
  savedPhone.value = p
  showPhoneSheet.value = false
  if (phoneAction.value === 'submit') doSubmit()
  else openPrice()
}

function showPhone(action: 'submit' | 'price'): void {
  phoneAction.value = action
  phoneDraft.value = savedPhone.value
  showPhoneSheet.value = true
}

async function doSubmit(): Promise<void> {
  const w = currentWholesaler.value
  const rows = currentCart.value
  if (!w || !rows.length) {
    uni.showToast({ title: '请先选择要询价的商品', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const data: Inquiry = await rtApi.submitInquiry({
      code: storeCode,
      wholesalerId: w.wholesalerId,
      rtPhone: savedPhone.value,
      items: rows.map((r) => ({ skuId: r.sku.skuId, qty: r.q })),
    })
    done.value = { docNo: data.docNo, count: cartSummary.value.count, amount: cartSummary.value.amount }
    // 清空当前商户已选数量
    rows.forEach((r) => delete qtyMap[r.sku.skuId])
  } catch {
    // request 已 toast，这里静默
  } finally {
    submitting.value = false
  }
}

async function openPrice(): Promise<void> {
  if (priceLoading.value) return
  priceLoading.value = true
  showPriceSheet.value = true
  priceData.value = null
  try {
    const res = await rtApi.getMyPriceList({ code: storeCode, rtPhone: savedPhone.value })
    priceData.value = { phoneLast4: res.rtPhoneLast4, groups: res.wholesalers }
  } catch {
    // request 已 toast
  } finally {
    priceLoading.value = false
  }
}

function ensurePrice(): void {
  if (!hasPhone.value) {
    showPhone('price')
    return
  }
  openPrice()
}

function goInquiries(): void {
  uni.navigateTo({ url: `/pages/rt/inquiries/index?code=${encodeURIComponent(storeCode)}` })
}

function closeDone(): void {
  done.value = null
}

async function load(): Promise<void> {
  loadErr.value = ''
  try {
    const res = await rtApi.getStore(storeCode)
    store.value = res
    activeWaId.value = res.wholesalers[0]?.wholesalerId ?? ''
    uni.setNavigationBarTitle({ title: res.storeName })
    addRecentStore(res.storeCode, res.storeName)
  } catch (e) {
    loadErr.value = '店铺加载失败，请确认店铺码正确后重试'
  } finally {
    loading.value = false
  }
}

onLoad((options) => {
  storeCode = decodeURIComponent((options?.code as string) ?? '')
  if (!storeCode) {
    loadErr.value = '缺少店铺码'
    loading.value = false
    return
  }
  load()
})

onShow(() => {
  savedPhone.value = getSavedPhone()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 加载 / 失败态 -->
    <view v-if="loading" class="center-box">
      <text class="center-box__text">正在进店…</text>
    </view>
    <view v-else-if="loadErr" class="center-box">
      <text class="center-box__text">{{ loadErr }}</text>
      <button class="btn-primary center-box__btn" @click="goBack">返回重进</button>
    </view>

    <template v-else-if="store">
      <!-- 店铺头 -->
      <view class="store-head">
        <view class="store-head__main">
          <text class="store-head__name">{{ store.storeName }}</text>
          <text v-if="store.businessHours" class="store-head__hours">{{ store.businessHours }}</text>
        </view>
        <view class="store-head__tools">
          <view class="tool" @click="ensurePrice">
            <text class="tool__icon">价</text>
            <text class="tool__text">我的价目</text>
          </view>
          <view class="tool" @click="goInquiries">
            <text class="tool__icon">单</text>
            <text class="tool__text">意向单</text>
          </view>
        </view>
        <text v-if="store.intro" class="store-head__intro">{{ store.intro }}</text>
        <view class="store-head__phone" @click="showPhone('price')">
          <text v-if="hasPhone" class="store-head__phone-text">手机 {{ savedPhone.slice(0, 3) }}****{{ savedPhone.slice(7) }} 可看专属价</text>
          <text v-else class="store-head__phone-text">输入手机号，查看我的专属价</text>
        </view>
      </view>

      <!-- 商户 tabs -->
      <scroll-view v-if="store.wholesalers.length > 1" scroll-x class="wa-tabs" :show-scrollbar="false">
        <view class="wa-tabs__inner">
          <view
            v-for="w in store.wholesalers"
            :key="w.wholesalerId"
            class="wa-tab"
            :class="{ 'wa-tab--on': w.wholesalerId === activeWaId }"
            @click="activeWaId = w.wholesalerId"
          >
            <text>{{ w.name }}</text>
            <text v-if="w.pinned" class="wa-tab__pin">置顶</text>
          </view>
        </view>
      </scroll-view>

      <!-- 商品列表（当前商户） -->
      <view v-if="currentWholesaler" class="skus">
        <view v-if="!currentWholesaler.skus.length" class="empty">
          <text class="empty__text">该商户暂无在库商品</text>
        </view>
        <view v-for="sku in currentWholesaler.skus" :key="sku.skuId" class="sku-row">
          <view class="sku-main">
            <view class="sku-name-line">
              <text v-if="sku.featured" class="badge badge--hot">主推</text>
              <text class="sku-name">{{ sku.name }}</text>
            </view>
            <text v-if="sku.spec" class="sku-spec">{{ sku.spec }}</text>
            <view class="sku-price-line">
              <text class="sku-price">{{ fmtPrice(sku.unitPrice) }}/件</text>
              <text v-if="sku.moqQty" class="sku-moq">满 {{ sku.moqQty }} 件 {{ fmtPrice(sku.moqPrice) }}</text>
            </view>
          </view>
          <view class="sku-side">
            <text class="sku-stock">库存 {{ sku.stockQty }}</text>
            <view class="stepper">
              <view class="step" :class="{ 'step--disabled': qty(sku.skuId) <= 0 }" @click="dec(sku)">−</view>
              <input
                class="step-input"
                type="number"
                :value="String(qty(sku.skuId) || '')"
                @input="onQtyInput(sku, $event)"
              />
              <view
                class="step step--plus"
                :class="{ 'step--off': qty(sku.skuId) >= sku.stockQty }"
                @click="inc(sku)"
              >
                ＋
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 空店态 -->
      <view v-if="!store.wholesalers.length" class="empty">
        <text class="empty__text">本店暂无批发商入驻</text>
      </view>

      <!-- 底部估价提交栏 -->
      <view v-if="currentWholesaler && cartSummary.count > 0" class="submit-bar">
        <view class="submit-bar__info">
          <text class="submit-bar__count">{{ cartSummary.count }} 件</text>
          <text class="submit-bar__amount">{{ fmtMoney(cartSummary.amount) }}</text>
          <text class="submit-bar__hint">参考估价，实际以商户确认价为准</text>
        </view>
        <button class="btn-primary submit-bar__btn" :loading="submitting" @click="ensurePhone">提交意向单</button>
      </view>
    </template>

    <!-- 手机号弹层 -->
    <view v-if="showPhoneSheet" class="mask" @click="showPhoneSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet__title">填写手机号</view>
        <view class="sheet__desc">用于接收询价结果并识别你的专属价（仅尾号留档，隐私安全）</view>
        <input
          v-model="phoneDraft"
          class="phone-input"
          type="number"
          :maxlength="PHONE_MAX_LEN"
          placeholder="11 位手机号"
          placeholder-class="ph"
        />
        <button class="btn-primary sheet__btn" @click="onPhoneConfirm">确认并继续</button>
      </view>
    </view>

    <!-- 提交成功弹层 -->
    <view v-if="done" class="mask">
      <view class="sheet sheet--center">
        <view class="sheet__ok">✓</view>
        <view class="sheet__title">意向单已提交</view>
        <view class="sheet__desc">
          单号 {{ done.docNo }} · {{ done.count }} 件 · 估价 {{ fmtMoney(done.amount) }}，商户确认后将短信通知你
        </view>
        <button class="btn-primary sheet__btn" @click="goInquiries">查看我的意向单</button>
        <button class="btn-ghost sheet__btn sheet__btn--ghost" @click="closeDone">继续逛货</button>
      </view>
    </view>

    <!-- 我的价目弹层 -->
    <view v-if="showPriceSheet" class="mask" @click="showPriceSheet = false">
      <view class="sheet sheet--full" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">我的价目</text>
          <text class="sheet__close" @click="showPriceSheet = false">✕</text>
        </view>
        <view class="sheet__desc">
          客户专属价由商户设定或议价沉淀，仅尾号 {{ priceData?.phoneLast4 ?? '--' }} 可见
        </view>
        <scroll-view scroll-y class="price-body">
          <view v-if="priceLoading" class="center-box">
            <text class="center-box__text">加载中…</text>
          </view>
          <view v-else-if="!priceData?.groups.length" class="empty">
            <text class="empty__text">暂无专属价目，向商户报手机号或提交询价即可累积</text>
          </view>
          <view v-for="g in priceData?.groups ?? []" :key="g.wholesalerId" class="price-group">
            <text class="price-group__name">{{ g.name }}</text>
            <view v-for="it in g.items" :key="it.skuId" class="price-row" :class="{ 'price-row--off': !it.listed }">
              <view class="price-row__main">
                <view class="sku-name-line">
                  <text v-if="it.source === 'from_inquiry' && it.listed" class="badge">议价</text>
                  <text v-if="!it.listed" class="badge badge--off">已下架</text>
                  <text class="sku-name">{{ it.name }}</text>
                </view>
                <text v-if="it.spec" class="sku-spec">{{ it.spec }}</text>
                <view class="price-row__meta">
                  <text class="sku-stock">库存 {{ it.stockQty }}</text>
                  <text v-if="it.expireAt" class="sku-stock">到期 {{ it.expireAt.slice(0, 10) }}</text>
                </view>
              </view>
              <view class="price-row__side">
                <text class="price-row__price">{{ fmtPrice(it.customerPrice) }}</text>
                <text class="price-row__public">公开价 {{ fmtPrice(it.unitPrice) }}</text>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 0 0 140rpx;
  background: $cc-bg-2;
  box-sizing: border-box;
}

.center-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24rpx;
  padding: 120rpx 40rpx;

  &__text {
    font-size: 28rpx;
    color: $cc-fg-3;
    text-align: center;
    line-height: 1.6;
  }

  &__btn {
    width: 320rpx;
  }
}

.empty {
  padding: 80rpx 40rpx;
  text-align: center;

  &__text {
    font-size: 26rpx;
    color: $cc-fg-4;
    line-height: 1.7;
  }
}

/* ===== 店铺头 ===== */
.store-head {
  padding: 28rpx 24rpx 20rpx;
  background: linear-gradient(135deg, #047857 0%, $cc-rt-accent 100%);
  color: #fff;

  &__main {
    display: flex;
    align-items: center;
    gap: 16rpx;
  }

  &__name {
    font-size: 38rpx;
    font-weight: 700;
  }

  &__hours {
    font-size: 22rpx;
    color: rgba(255, 255, 255, 0.85);
  }

  &__intro {
    display: block;
    margin-top: 10rpx;
    font-size: 24rpx;
    color: rgba(255, 255, 255, 0.85);
    line-height: 1.6;
  }

  &__tools {
    display: flex;
    gap: 16rpx;
    margin-top: 20rpx;
  }

  &__phone {
    margin-top: 16rpx;

    &-text {
      font-size: 22rpx;
      color: rgba(255, 255, 255, 0.9);
      text-decoration: underline;
    }
  }
}

.tool {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 12rpx 20rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.16);

  &__icon {
    width: 30rpx;
    height: 30rpx;
    line-height: 30rpx;
    text-align: center;
    border-radius: 50%;
    background: #fff;
    color: $cc-rt-accent;
    font-size: 20rpx;
    font-weight: 700;
  }

  &__text {
    font-size: 24rpx;
    color: #fff;
  }
}

/* ===== 商户 tabs ===== */
.wa-tabs {
  position: sticky;
  top: 0;
  z-index: 5;
  background: $cc-bg-2;
  white-space: nowrap;

  &__inner {
    display: inline-flex;
    gap: 14rpx;
    padding: 16rpx 24rpx;
  }
}

.wa-tab {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  padding: 14rpx 28rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  color: $cc-fg-2;
  font-size: 27rpx;

  &--on {
    background: $cc-rt-accent;
    border-color: $cc-rt-accent;
    color: #fff;
    font-weight: 600;
  }

  &__pin {
    font-size: 18rpx;
    padding: 0 8rpx;
    border-radius: 6rpx;
    background: rgba(255, 255, 255, 0.25);
  }
}

/* ===== 商品行 ===== */
.skus {
  padding: 0 24rpx;
}

.sku-row {
  display: flex;
  align-items: center;
  margin-bottom: 16rpx;
  padding: 24rpx;
  background: $cc-bg-1;
  border-radius: $cc-card-radius;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);
}

.sku-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.sku-name-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
}

.sku-name {
  font-size: 30rpx;
  font-weight: 600;
  color: $cc-fg-1;
}

.sku-spec {
  font-size: 24rpx;
  color: $cc-fg-3;
}

.sku-price-line {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.sku-price {
  font-size: 30rpx;
  font-weight: 700;
  color: $cc-danger;
}

.sku-moq {
  font-size: 22rpx;
  color: $cc-fg-4;
}

.badge {
  font-size: 18rpx;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background: $cc-success-bg;
  color: $cc-success;

  &--hot {
    background: #fff7ed;
    color: $cc-rt-warm;
  }

  &--off {
    background: $cc-bg-3;
    color: $cc-fg-4;
  }
}

.sku-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 16rpx;
  margin-left: 16rpx;
}

.sku-stock {
  font-size: 22rpx;
  color: $cc-fg-4;
}

/* ===== 步进器 ===== */
.stepper {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.step {
  width: 56rpx;
  height: 56rpx;
  line-height: 52rpx;
  text-align: center;
  border-radius: 10rpx;
  background: $cc-bg-3;
  color: $cc-fg-2;
  font-size: 34rpx;
  font-weight: 600;

  &--plus {
    background: $cc-rt-accent;
    color: #fff;
  }

  &--disabled {
    opacity: 0.35;
  }

  &--off {
    opacity: 0.35;
    pointer-events: none;
  }
}

.step-input {
  width: 76rpx;
  height: 56rpx;
  text-align: center;
  border: 2rpx solid $cc-border-1;
  border-radius: 10rpx;
  font-size: 28rpx;
  color: $cc-fg-1;
  background: #fff;
}

/* ===== 底部提交栏 ===== */
.submit-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background: $cc-bg-1;
  box-shadow: 0 -4rpx 20rpx rgba(2, 6, 23, 0.06);

  &__info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2rpx;
  }

  &__count {
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__amount {
    font-size: 34rpx;
    font-weight: 700;
    color: $cc-danger;
  }

  &__hint {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__btn {
    width: 240rpx;
    margin-left: 20rpx;
    height: 88rpx;
    line-height: 88rpx;
    border-radius: 12rpx;
    font-size: 30rpx;
    font-weight: 600;
  }
}

/* ===== 弹层 ===== */
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

  &--center {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &--full {
    height: 78vh;
    display: flex;
    flex-direction: column;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: 34rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__desc {
    margin: 14rpx 0 24rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
    line-height: 1.7;
  }

  &__ok {
    width: 88rpx;
    height: 88rpx;
    line-height: 88rpx;
    text-align: center;
    border-radius: 50%;
    background: $cc-success-bg;
    color: $cc-success;
    font-size: 52rpx;
    font-weight: 700;
    margin-bottom: 20rpx;
  }

  &__close {
    padding: 8rpx 16rpx;
    font-size: 32rpx;
    color: $cc-fg-4;
  }

  &__btn {
    width: 100%;
    margin-top: 16rpx;

    &--ghost {
      background: $cc-bg-3;
      color: $cc-fg-2;
    }
  }
}

.phone-input {
  height: 96rpx;
  padding: 0 24rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 12rpx;
  font-size: 34rpx;
  letter-spacing: 2rpx;
  color: $cc-fg-1;
  background: $cc-bg-2;
}

.ph {
  color: $cc-fg-4;
}

/* ===== 我的价目 ===== */
.price-body {
  flex: 1;
  min-height: 0;
}

.price-group {
  margin-top: 20rpx;

  &__name {
    display: block;
    margin-bottom: 12rpx;
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }
}

.price-row {
  display: flex;
  align-items: center;
  margin-bottom: 12rpx;
  padding: 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;

  &--off {
    opacity: 0.55;
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8rpx;
  }

  &__meta {
    display: flex;
    gap: 20rpx;
  }

  &__side {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 6rpx;
    margin-left: 16rpx;
  }

  &__price {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-danger;
  }

  &__public {
    font-size: 20rpx;
    color: $cc-fg-4;
    text-decoration: line-through;
  }
}
</style>
