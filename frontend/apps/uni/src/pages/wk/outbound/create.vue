<script setup lang="ts">
// WK 代建出库（F5-W1 · 现场卖货直接出库，POST /tenant/wk/outbound-requests 直达 COMPLETED）。
// 凭据：confirmed=true 显式确认；qty > 在库×50% 时须复述件数 restatedQty==qty（后端 50338）。
// 货位开关启用时 location 必填（50822）；在库不足由后端 50251 整体拒绝。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { Sku, SnowflakeId, Wholesaler } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'

const loading = ref(false)
const merchants = ref<Wholesaler[]>([])
const skus = ref<Sku[]>([])
const onhandBySku = new Map<string, number>()

const whId = ref<SnowflakeId | null>(null)
const skuId = ref<SnowflakeId | null>(null)
const qty = ref('')
const palletQty = ref('')
const location = ref('')
const confirmed = ref(false)
const restate = ref('')
const locationEnabled = ref(false)
const submitting = ref(false)

const pickedWh = computed(() => merchants.value.find((w) => String(w.id) === String(whId.value)) ?? null)
const pickedSku = computed(() => skus.value.find((s) => String(s.id) === String(skuId.value)) ?? null)
const onhand = computed(() => {
  const id = skuId.value
  if (!id) return null
  return onhandBySku.get(String(id)) ?? null
})
const needRestate = computed(() => {
  const oh = onhand.value
  if (oh == null || oh <= 0) return false
  const q = Number(qty.value)
  return Number.isFinite(q) && q > oh / 2
})
const enough = computed(() => {
  const oh = onhand.value
  if (oh == null) return null
  return Number(qty.value) <= oh
})

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId && work.role === 'WK')
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function labelOf(s: Sku): string {
  return s.spec ? `${s.name}（${s.spec}）` : s.name
}

function pickWh(w: Wholesaler): void {
  whId.value = w.id
  skuId.value = null
  qty.value = ''
  palletQty.value = ''
  location.value = ''
  restate.value = ''
  confirmed.value = false
  void loadSkus()
}

async function loadMerchants(): Promise<void> {
  loading.value = true
  try {
    const [ws, cfgWork] = await Promise.all([
      wkApi.listWholesalers(),
      (async () => {
        const work = readWork()
        if (!work) return null
        try {
          return await wkApi.batchConfig(work.tenantId)
        } catch {
          return null
        }
      })(),
    ])
    merchants.value = ws
    if (cfgWork) locationEnabled.value = cfgWork.locationEnabled === 1
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

async function loadSkus(): Promise<void> {
  if (!whId.value) return
  loading.value = true
  skuId.value = null
  try {
    const [list, inv] = await Promise.all([
      wkApi.listSkuByWholesaler(whId.value),
      wkApi.listInventories(whId.value),
    ])
    skus.value = list
    onhandBySku.clear()
    inv.forEach((it) => onhandBySku.set(String(it.skuId), it.qty))
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function pickSku(s: Sku): void {
  skuId.value = s.id
  qty.value = ''
  palletQty.value = ''
  restate.value = ''
  confirmed.value = false
}

function toggleConfirmed(): void {
  confirmed.value = !confirmed.value
}

async function submit(): Promise<void> {
  if (!whId.value || !skuId.value || submitting.value) return
  const q = Number(qty.value)
  if (!Number.isFinite(q) || q <= 0) {
    uni.showToast({ title: '请输入正确的出库件数', icon: 'none' })
    return
  }
  if (enough.value === false) {
    uni.showToast({ title: `在库仅 ${onhand.value} 件，件数超出可用库存`, icon: 'none' })
    return
  }
  if (needRestate.value) {
    if (!restate.value.trim() || Number(restate.value) !== q) {
      uni.showToast({ title: '出库超过在库一半，请准确复述件数', icon: 'none' })
      return
    }
  }
  if (locationEnabled.value && !location.value.trim()) {
    uni.showToast({ title: '货位功能已开启，请填写拣出货位', icon: 'none' })
    return
  }
  if (!confirmed.value) {
    uni.showToast({ title: '请勾选确认出库凭据', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const pal = palletQty.value.trim()
    await wkApi.outbound.createByWk({
      wholesalerId: whId.value,
      skuId: skuId.value,
      qty: q,
      palletQty: pal === '' ? undefined : Number(pal),
      confirmed: true,
      restatedQty: needRestate.value ? q : undefined,
      location: location.value.trim() || undefined,
    })
    uni.showToast({ title: '代建出库完成', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 600)
  } catch {
    /* request 已 toast */
  } finally {
    submitting.value = false
  }
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void loadMerchants()
})
</script>

<template>
  <view class="page">
    <!-- 商户 -->
    <view class="sec">
      <view class="sec__title">1 · 选择商户</view>
      <scroll-view v-if="merchants.length" scroll-x class="chips" show-scrollbar="false">
        <view
          v-for="w in merchants"
          :key="String(w.id)"
          class="chip"
          :class="{ 'chip--on': String(w.id) === String(whId) }"
          @click="pickWh(w)"
        >{{ w.name }}</view>
      </scroll-view>
      <view v-else-if="!loading" class="empty">暂无商户</view>
    </view>

    <!-- 货品 -->
    <view v-if="whId" class="sec">
      <view class="sec__title">2 · 选择货品（{{ skus.length }}）</view>
      <view v-if="skus.length" class="sku-list">
        <view
          v-for="s in skus"
          :key="String(s.id)"
          class="sku"
          :class="{ 'sku--on': String(s.id) === String(skuId) }"
          @click="pickSku(s)"
        >
          <view class="sku__main">
            <text class="sku__name">{{ labelOf(s) }}</text>
            <text class="sku__price">公开价 ¥{{ s.unitPrice.toFixed(2) }}{{ s.moqPrice != null ? ` · 起批 ${s.moqPrice.toFixed(2)}` : '' }}</text>
          </view>
          <view class="sku__stock">
            <text class="sku__stock-num">{{ onhandBySku.get(String(s.id)) ?? '—' }}</text>
            <text class="sku__stock-label">在库(件)</text>
          </view>
        </view>
      </view>
      <view v-else-if="!loading" class="empty">该商户暂无货品</view>
    </view>

    <!-- 出库信息 -->
    <view v-if="pickedSku" class="sec">
      <view class="sec__title">3 · 出库信息</view>
      <view class="form">
        <view class="form__row">
          <text class="form__label">出库件数</text>
          <input v-model="qty" class="form__input" type="number" placeholder="0" placeholder-class="ph" />
        </view>
        <view class="form__row">
          <text class="form__label">托盘数</text>
          <input v-model="palletQty" class="form__input" type="number" placeholder="可空" placeholder-class="ph" />
        </view>
        <view v-if="locationEnabled" class="form__row">
          <text class="form__label">拣出货位</text>
          <input v-model="location" class="form__input" type="text" placeholder="货位功能已开启，必填" placeholder-class="ph" maxlength="64" />
        </view>
      </view>

      <view v-if="onhand != null" class="stock-line" :class="{ 'stock-line--low': enough === false }">
        当前在库：{{ onhand }} 件{{ enough === false ? '（件数超出可用库存）' : '' }}
      </view>
      <view v-if="needRestate" class="restate">
        <text class="restate__t">出库件数超过在库一半，请再次输入件数以确认（大额出库二次确认）</text>
        <input v-model="restate" class="input" type="number" :placeholder="`请复述件数 ${qty || ''}`" placeholder-class="ph" />
      </view>
    </view>

    <!-- 凭据 -->
    <view v-if="pickedSku && enough !== false" class="confirm" @click="toggleConfirmed">
      <view class="confirm__box" :class="{ 'confirm__box--on': confirmed }">{{ confirmed ? '✓' : '' }}</view>
      <view class="confirm__t">
        <text class="confirm__line1">我确认该笔为实际卖货出库凭据，已核对数量</text>
        <text class="confirm__line2">商户 {{ pickedWh?.name }} · {{ labelOf(pickedSku) }} × {{ qty || '—' }} 件</text>
      </view>
    </view>

    <button
      class="btn-primary submit"
      :disabled="!pickedSku || enough === false"
      :loading="submitting"
      @click="submit"
    >确认代建出库</button>

    <view class="note">
      <text class="note__t">说明：代建出库用于商户现场买货直接出库（如线下零售带走），提交即完成出库；大额出库需复述件数。库存不足或数据有误请先联系商户。</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.sec {
  margin-bottom: 26rpx;

  &__title {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-fg-2;
    margin-bottom: 14rpx;
  }
}

.chips {
  white-space: nowrap;
}

.chip {
  display: inline-block;
  padding: 14rpx 26rpx;
  margin-right: 12rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  font-size: 25rpx;
  color: $cc-fg-2;

  &--on {
    background: $cc-info-bg;
    border-color: $cc-accent;
    color: $cc-accent;
    font-weight: 600;
  }
}

.sku-list {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.sku {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 20rpx 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &--on {
    border: 2rpx solid $cc-accent;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__name {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__price {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__stock {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2rpx;
  }

  &__stock-num {
    font-size: 28rpx;
    font-weight: 800;
    color: $cc-warning;
  }

  &__stock-label {
    font-size: 18rpx;
    color: $cc-fg-4;
  }
}

.form {
  padding: 8rpx 4rpx;

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14rpx 0;
    border-bottom: 2rpx solid $cc-bg-2;
  }

  &__label {
    font-size: 25rpx;
    color: $cc-fg-3;
  }

  &__input {
    width: 55%;
    text-align: right;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.stock-line {
  margin-top: 14rpx;
  font-size: 22rpx;
  color: $cc-success;

  &--low {
    color: $cc-danger;
  }
}

.restate {
  margin-top: 16rpx;
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: $cc-danger-bg;

  &__t {
    display: block;
    font-size: 22rpx;
    color: $cc-danger;
    line-height: 1.6;
    margin-bottom: 10rpx;
  }
}

.input {
  height: 84rpx;
  padding: 0 20rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 10rpx;
  font-size: 28rpx;
  color: $cc-fg-1;
  background: $cc-bg-1;
  box-sizing: border-box;
}

.confirm {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  margin-top: 8rpx;
  padding: 18rpx;
  border-radius: 12rpx;
  background: $cc-warning-bg;

  &__box {
    flex-shrink: 0;
    width: 40rpx;
    height: 40rpx;
    line-height: 40rpx;
    text-align: center;
    border-radius: 10rpx;
    background: $cc-bg-1;
    color: #fff;
    font-size: 26rpx;
    font-weight: 700;

    &--on {
      background: $cc-warning;
    }
  }

  &__t {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__line1 {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__line2 {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

.submit {
  margin-top: 30rpx;
  height: 96rpx;
  font-size: 31rpx;
  font-weight: 600;

  &[disabled] {
    opacity: 0.5;
  }
}

.note {
  margin-top: 22rpx;
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: $cc-bg-3;

  &__t {
    font-size: 21rpx;
    color: $cc-fg-4;
    line-height: 1.7;
  }
}

.empty {
  padding: 30rpx 0;
  text-align: center;
  font-size: 24rpx;
  color: $cc-fg-4;
}

.ph {
  color: $cc-fg-4;
}
</style>
