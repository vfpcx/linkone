<script setup lang="ts">
// WK 现场代建入库（F7-1 · US-WK-01b：商户现场到货由 WK 直接登记入库，POST /tenant/inbound）。
// 链路：登记即增库存 → PENDING_WA_CONFIRM 72h 商户确认/异议；异议 → TA 仲裁，判定成立冲销撤销。
// 拍照按 TA 拍照开关执行（batch-config.photoMode：REQUIRED 必拍 / 其余选填，≤5 张，POST /api/v1/files 先传后挂）。
// 货位（C2 locationEnabled）、批次三字段（batchEnabled）按开关显隐并必填；过期批次二次确认。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { Sku, SnowflakeId, Wholesaler } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { uploadImage } from '../../../utils/request'
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
const batchNo = ref('')
const productionDate = ref('')
const expiryDate = ref('')
const expiredConfirmed = ref(false)
const confirmed = ref(false)
const batchEnabled = ref(false)
const locationEnabled = ref(false)
const photoMode = ref('NONE')
const photos = ref<string[]>([])
const uploading = ref(false)
const submitting = ref(false)

const pickedWh = computed(() => merchants.value.find((w) => String(w.id) === String(whId.value)) ?? null)
const pickedSku = computed(() => skus.value.find((s) => String(s.id) === String(skuId.value)) ?? null)
const photoRequired = computed(() => photoMode.value === 'REQUIRED')

function todayStr(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const isExpiredDoc = computed(() => !!expiryDate.value && expiryDate.value <= todayStr())

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
  resetForm()
  void loadSkus()
}

function resetForm(): void {
  skuId.value = null
  qty.value = ''
  palletQty.value = ''
  location.value = ''
  batchNo.value = ''
  productionDate.value = ''
  expiryDate.value = ''
  expiredConfirmed.value = false
  confirmed.value = false
  photos.value = []
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
    if (cfgWork) {
      locationEnabled.value = cfgWork.locationEnabled === 1
      batchEnabled.value = cfgWork.batchEnabled === 1
      photoMode.value = cfgWork.photoMode ?? 'NONE'
    }
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
  batchNo.value = ''
  productionDate.value = ''
  expiryDate.value = ''
  expiredConfirmed.value = false
  confirmed.value = false
}

function onProdChange(e: { detail: { value: string } }): void {
  productionDate.value = e.detail.value
  if (expiryDate.value && expiryDate.value <= productionDate.value) expiryDate.value = ''
}

function onExpiryChange(e: { detail: { value: string } }): void {
  expiryDate.value = e.detail.value
  if (productionDate.value && expiryDate.value <= productionDate.value) {
    uni.showToast({ title: '效期需晚于生产日期', icon: 'none' })
    expiryDate.value = ''
  }
}

function addPhotos(): void {
  const remain = 5 - photos.value.length
  if (remain <= 0) {
    uni.showToast({ title: '最多 5 张现场照片', icon: 'none' })
    return
  }
  uni.chooseImage({
    count: remain,
    sizeType: ['compressed'],
    success: (res) => {
      uploading.value = true
      void (async () => {
        try {
          for (const p of res.tempFilePaths) {
            photos.value.push(await uploadImage(p))
          }
        } catch {
          /* request 已 toast */
        } finally {
          uploading.value = false
        }
      })()
    },
  })
}

function removePhoto(i: number): void {
  photos.value.splice(i, 1)
}

function previewPhoto(i: number): void {
  const urls = photos.value
  if (urls.length) uni.previewImage({ urls, current: urls[i] ?? urls[0] })
}

async function submit(): Promise<void> {
  if (!whId.value || !skuId.value || submitting.value || uploading.value) return
  const q = Number(qty.value)
  if (!Number.isFinite(q) || q <= 0) {
    uni.showToast({ title: '请输入正确的入库件数', icon: 'none' })
    return
  }
  if (batchEnabled.value) {
    if (!batchNo.value.trim()) {
      uni.showToast({ title: '批次功能已开启，请填写批次号', icon: 'none' })
      return
    }
    if (!productionDate.value || !expiryDate.value) {
      uni.showToast({ title: '批次功能已开启，请选择生产/效期日期', icon: 'none' })
      return
    }
    if (isExpiredDoc.value && !expiredConfirmed.value) {
      uni.showToast({ title: '该批次效期已过，请勾选过期批次确认', icon: 'none' })
      return
    }
  }
  if (locationEnabled.value && !location.value.trim()) {
    uni.showToast({ title: '货位功能已开启，请填写入库货位', icon: 'none' })
    return
  }
  if (photoRequired.value && !photos.value.length) {
    uni.showToast({ title: '拍照开关已设为必填，请先拍摄现场照片', icon: 'none' })
    return
  }
  if (!confirmed.value) {
    uni.showToast({ title: '请勾选实收确认', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const pal = palletQty.value.trim()
    await wkApi.inbound.register({
      wholesalerId: whId.value,
      skuId: skuId.value,
      qty: q,
      palletQty: pal === '' ? undefined : Number(pal),
      batchNo: batchEnabled.value ? batchNo.value.trim() : undefined,
      productionDate: productionDate.value || undefined,
      expiryDate: expiryDate.value || undefined,
      expiredConfirmed: isExpiredDoc.value ? true : undefined,
      location: location.value.trim() || undefined,
      attachments: photos.value.length ? [...photos.value] : undefined,
    })
    uni.showToast({ title: '代建入库完成，已通知商户确认', icon: 'success' })
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

    <!-- 到货信息 -->
    <view v-if="pickedSku" class="sec">
      <view class="sec__title">3 · 到货登记</view>
      <view class="form">
        <view class="form__row">
          <text class="form__label">实收件数</text>
          <input v-model="qty" class="form__input" type="number" placeholder="0" placeholder-class="ph" />
        </view>
        <view class="form__row">
          <text class="form__label">托盘数</text>
          <input v-model="palletQty" class="form__input" type="number" placeholder="可空" placeholder-class="ph" />
        </view>
        <template v-if="batchEnabled">
          <view class="form__row">
            <text class="form__label">批次号</text>
            <input v-model="batchNo" class="form__input" type="text" placeholder="批次功能已开启，必填" placeholder-class="ph" maxlength="32" />
          </view>
          <view class="form__row">
            <text class="form__label">生产日期</text>
            <picker mode="date" :value="productionDate" @change="onProdChange">
              <view class="form__picker" :class="{ ph: !productionDate }">{{ productionDate || '请选择' }}</view>
            </picker>
          </view>
          <view class="form__row">
            <text class="form__label">效期至</text>
            <picker mode="date" :value="expiryDate" @change="onExpiryChange">
              <view class="form__picker" :class="{ ph: !expiryDate }">{{ expiryDate || '请选择' }}</view>
            </picker>
          </view>
        </template>
        <view v-if="locationEnabled" class="form__row">
          <text class="form__label">入库货位</text>
          <input v-model="location" class="form__input" type="text" placeholder="货位功能已开启，必填" placeholder-class="ph" maxlength="64" />
        </view>
      </view>

      <view v-if="isExpiredDoc" class="check-line" @click="expiredConfirmed = !expiredConfirmed">
        <view class="check-line__box" :class="{ 'check-line__box--on': expiredConfirmed }">{{ expiredConfirmed ? '✓' : '' }}</view>
        <text class="check-line__t">该批次效期已过，我确认商户仍要求按此批次入库</text>
      </view>
    </view>

    <!-- 现场照片 -->
    <view v-if="pickedSku" class="sec">
      <view class="sec__title">4 · 现场照片（{{ photos.length }}/5）</view>
      <view v-if="photoRequired" class="photo-tip photo-tip--required">拍照开关已设为必填，请至少拍摄 1 张现场到货照片</view>
      <view v-else class="photo-tip">建议拍摄现场到货照片存证（TA 拍照开关：选填）</view>
      <view v-if="photos.length" class="photos">
        <view v-for="(u, i) in photos" :key="u" class="photos__item">
          <image class="photos__img" :src="u" mode="aspectFill" @click="previewPhoto(i)" />
          <view class="photos__del" @click="removePhoto(i)">×</view>
        </view>
        <view v-if="photos.length < 5" class="photos__add" @click="addPhotos">{{ uploading ? '上传中…' : '＋' }}</view>
      </view>
      <view v-else class="photos">
        <view class="photos__add" @click="addPhotos">{{ uploading ? '上传中…' : '＋' }}</view>
      </view>
    </view>

    <!-- 确认 -->
    <view v-if="pickedSku" class="confirm" @click="confirmed = !confirmed">
      <view class="confirm__box" :class="{ 'confirm__box--on': confirmed }">{{ confirmed ? '✓' : '' }}</view>
      <view class="confirm__t">
        <text class="confirm__line1">我确认该笔为商户实际到货的实收登记（代商户入库）</text>
        <text class="confirm__line2">商户 {{ pickedWh?.name }} · {{ labelOf(pickedSku) }} × {{ qty || '—' }} 件</text>
      </view>
    </view>

    <button
      class="btn-primary submit"
      :disabled="!pickedSku"
      :loading="submitting"
      @click="submit"
    >确认代建入库</button>

    <view class="note">
      <text class="note__t">说明：代建入库用于商户送货到仓时由 WK 现场直接登记入库（省去申请等待），提交即增加该商户可售库存，商户将收到 72h 内确认/异议通知（超时自动确认）；若商户异议且经 TA 仲裁认定成立，单据将撤销并自动冲销库存。</text>
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
    color: $cc-fg-3;
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

  &__picker {
    width: 55%;
    text-align: right;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.photo-tip {
  padding: 14rpx 18rpx;
  border-radius: 10rpx;
  background: $cc-bg-3;
  font-size: 22rpx;
  color: $cc-fg-4;
  line-height: 1.6;
  margin-bottom: 14rpx;

  &--required {
    background: $cc-danger-bg;
    color: $cc-danger;
  }
}

.photos {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;

  &__item {
    position: relative;
    width: 150rpx;
    height: 150rpx;
  }

  &__img {
    width: 150rpx;
    height: 150rpx;
    border-radius: 12rpx;
    background: $cc-bg-2;
  }

  &__del {
    position: absolute;
    top: -14rpx;
    right: -14rpx;
    width: 44rpx;
    height: 44rpx;
    line-height: 40rpx;
    text-align: center;
    border-radius: 999rpx;
    background: $cc-fg-2;
    color: #fff;
    font-size: 30rpx;
  }

  &__add {
    width: 150rpx;
    height: 150rpx;
    line-height: 150rpx;
    text-align: center;
    border-radius: 12rpx;
    border: 2rpx dashed $cc-border-1;
    color: $cc-fg-4;
    font-size: 56rpx;
    box-sizing: border-box;
  }
}

.check-line {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  margin-top: 18rpx;
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
