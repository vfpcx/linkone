<script setup lang="ts">
// WA/WE 新建入库申请（F7-4 · P3b T1：POST /wholesaler/inbound-requests，D-5 多行拆 N 单共享 batchSubmitId）。
// 与 admin wa/Inbound.vue 新建入库申请对话框同口径：≤50 行；每商品拆一张一单 SKU 申请单；
// 批次三字段按商户开关（batchEnabled）显隐必填，同批 (skuId,batchNo) 重复 50362 行级预检；
// 过期红/30 天临期黄仅提示（过期二次确认在 WK 登记侧 50364）；提交零库存零计费。
// 复制重建（REJECTED 一键重提）：onLoad rebuild 参数回填 sku/件数/托盘/备注/批次三字段。
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { Sku, SnowflakeId } from '@cangchu/api-types'
import { waInboundApi } from '../../../api/waInbound'
import { skuApi } from '../../../api/sku'
import { wkApi } from '../../../api/wk'
import { hasWaScope, readWork } from '../../../utils/session'

interface Row {
  skuId: SnowflakeId | null
  qty: string
  palletQty: string
  remark: string
  batchNo: string
  productionDate: string
  expiryDate: string
}

const MAX_ROWS = 50
const work = readWork()

const rows = ref<Row[]>([emptyRow()])
const skus = ref<Sku[]>([])
const skusLoaded = ref(false)
const batchEnabled = ref(false)
const submitting = ref(false)

const pickIndex = ref<number | null>(null)
const skuSheetOpen = computed(() => pickIndex.value !== null)

function emptyRow(): Row {
  return {
    skuId: null,
    qty: '',
    palletQty: '',
    remark: '',
    batchNo: '',
    productionDate: '',
    expiryDate: '',
  }
}

function todayStr(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function labelOf(s: Sku): string {
  return s.spec ? `${s.name}（${s.spec}）` : s.name
}

function addRow(): void {
  if (rows.value.length >= MAX_ROWS) {
    uni.showToast({ title: `最多 ${MAX_ROWS} 行商品`, icon: 'none' })
    return
  }
  rows.value.push(emptyRow())
}

function removeRow(i: number): void {
  rows.value.splice(i, 1)
  if (!rows.value.length) rows.value.push(emptyRow())
}

// ===== 商品选择 sheet =====
function openPick(i: number): void {
  pickIndex.value = i
}
function closePick(): void {
  pickIndex.value = null
}
function pickSku(s: Sku): void {
  const i = pickIndex.value
  if (i === null) return
  rows.value[i].skuId = s.id
  closePick()
}

const pickedSku = (i: number): Sku | null => {
  const id = rows.value[i].skuId
  return id == null ? null : (skus.value.find((s) => String(s.id) === String(id)) ?? null)
}

// ===== 批次日期 =====
function onProdChange(i: number, e: { detail: { value: string } }): void {
  rows.value[i].productionDate = e.detail.value
  const r = rows.value[i]
  if (r.expiryDate && r.expiryDate <= r.productionDate) {
    uni.showToast({ title: '效期需晚于生产日期', icon: 'none' })
    r.expiryDate = ''
  }
}
function onExpiryChange(i: number, e: { detail: { value: string } }): void {
  const r = rows.value[i]
  const v = e.detail.value
  if (r.productionDate && v <= r.productionDate) {
    uni.showToast({ title: '效期需晚于生产日期', icon: 'none' })
    r.expiryDate = ''
    return
  }
  r.expiryDate = v
}

/** 效期状态：expired（≤ 今天，登记侧需二次确认）/ near（≤30 天临期）/ null */
function expiryState(r: Row): 'expired' | 'near' | null {
  if (!r.expiryDate) return null
  const ms = new Date(`${r.expiryDate}T23:59:59`).getTime() - Date.now()
  const days = Math.ceil(ms / 86400000)
  if (days <= 0) return 'expired'
  return days <= 30 ? 'near' : null
}

/** 同批 (skuId, batchNo) 重复预检 → 返回重复行号文本；null=无 */
function dupHint(i: number): string | null {
  const a = rows.value[i]
  if (a.skuId == null) return null
  for (let j = 0; j < rows.value.length; j += 1) {
    if (j === i) continue
    const b = rows.value[j]
    if (String(a.skuId) !== String(b.skuId)) continue
    if (String(a.batchNo.trim()) === String(b.batchNo.trim())) return `与第 ${j + 1} 行同商品同批次（批次号需唯一）`
  }
  return null
}

// ===== 提交 =====
async function doSubmit(): Promise<void> {
  if (submitting.value) return
  const wh = work?.wholesalerId
  if (!work || !wh) {
    uni.showToast({ title: '未找到商户信息，请重新登录', icon: 'none' })
    return
  }
  // 行级校验（第一条错误提示并高亮）
  for (let i = 0; i < rows.value.length; i += 1) {
    const r = rows.value[i]
    const n = i + 1
    if (r.skuId == null) {
      uni.showToast({ title: `第 ${n} 行请选择商品`, icon: 'none' })
      return
    }
    const q = Number(r.qty)
    if (!r.qty.trim() || !Number.isFinite(q) || q <= 0) {
      uni.showToast({ title: `第 ${n} 行请输入申请件数`, icon: 'none' })
      return
    }
    const pal = r.palletQty.trim()
    if (pal && (!Number.isFinite(Number(pal)) || Number(pal) < 0)) {
      uni.showToast({ title: `第 ${n} 行托盘数需 ≥0`, icon: 'none' })
      return
    }
    if (batchEnabled.value) {
      if (!r.batchNo.trim()) {
        uni.showToast({ title: `第 ${n} 行：批次功能已开启，请填写批次号`, icon: 'none' })
        return
      }
      if (!r.productionDate || !r.expiryDate) {
        uni.showToast({ title: `第 ${n} 行：批次功能已开启，请选择生产/效期日期`, icon: 'none' })
        return
      }
      if (r.productionDate > todayStr()) {
        uni.showToast({ title: `第 ${n} 行生产日期不能晚于今天`, icon: 'none' })
        return
      }
      if (r.expiryDate <= r.productionDate) {
        uni.showToast({ title: `第 ${n} 行效期需晚于生产日期`, icon: 'none' })
        return
      }
    }
    if (dupHint(i)) {
      uni.showToast({ title: `第 ${n} 行${dupHint(i) ?? ''}`, icon: 'none' })
      return
    }
  }

  const count = rows.value.length
  uni.showModal({
    title: `确认提交（拆 ${count} 张申请单）`,
    content: '每张申请单只含一种商品，同批提交。提交不影响库存与计费，仓库受理并登记入库确认后才开始计费。',
    confirmText: '确认提交',
    confirmColor: '#4F46E5',
    success: async (res) => {
      if (!res.confirm) return
      submitting.value = true
      try {
        const created = await waInboundApi.submit({
          wholesalerId: wh,
          items: rows.value.map((r) => {
            const pal = r.palletQty.trim()
            return {
              skuId: r.skuId as SnowflakeId,
              qty: Number(r.qty),
              palletQty: pal === '' ? undefined : Number(pal),
              remark: r.remark.trim() ? r.remark.trim() : undefined,
              batchNo: batchEnabled.value ? r.batchNo.trim() : undefined,
              productionDate: batchEnabled.value ? r.productionDate : undefined,
              expiryDate: batchEnabled.value ? r.expiryDate : undefined,
            }
          }),
        })
        uni.showToast({ title: `已提交 ${created.length} 张申请单，等待仓库受理`, icon: 'success' })
        setTimeout(() => uni.navigateBack(), 700)
      } catch {
        /* request 已 toast（50362 同批重复/40001 行数等） */
      } finally {
        submitting.value = false
      }
    },
  })
}

// ===== 初始化 =====
async function init(options: Record<string, string>): Promise<void> {
  if (!hasWaScope() || !work) {
    uni.reLaunch({ url: '/pages/wa/login/index' })
    return
  }
  if (!work.wholesalerId) {
    uni.showToast({ title: '请先绑定商户后再提交入库申请', icon: 'none' })
    return
  }
  // 复制重建预填（REJECTED 一键重提）
  if (options.rebuild === '1' && options.skuId) {
    rows.value = [
      {
        skuId: String(options.skuId) as SnowflakeId,
        qty: options.qty || '',
        palletQty: options.palletQty || '',
        remark: options.remark || '',
        batchNo: options.batchNo || '',
        productionDate: options.productionDate || '',
        expiryDate: options.expiryDate || '',
      },
    ]
  }
  const cfgPromise = wkApi
    .batchConfig(work.tenantId)
    .then((cfg) => {
      batchEnabled.value = cfg.batchEnabled === 1
    })
    .catch(() => {
      /* 配置拉取失败按未开启处理 */
    })
  try {
    const [list] = await Promise.all([skuApi.listMine(work.wholesalerId), cfgPromise])
    skus.value = list
    skusLoaded.value = true
  } catch {
    /* request 已 toast */
  }
}

onLoad((options) => {
  void init((options ?? {}) as unknown as Record<string, string>)
})
</script>

<template>
  <view class="page">
    <view class="banner">提交不影响库存与计费；仓库受理并在 24h 内登记，你确认后即入库计费。每种商品将拆成一张申请单（同批提交，便于整批处理）。</view>

    <!-- 商品行 -->
    <view v-for="(r, i) in rows" :key="i" class="row">
      <view class="row__head">
        <text class="row__no">第 {{ i + 1 }} 行</text>
        <text v-if="rows.length > 1" class="row__del" @click="removeRow(i)">删除本行</text>
      </view>

      <!-- 商品选择 -->
      <view v-if="pickedSku(i)" class="sku-picked" @click="openPick(i)">
        <view class="sku-picked__main">
          <text class="sku-picked__name">{{ labelOf(pickedSku(i) as Sku) }}</text>
          <text v-if="pickedSku(i)?.listed === false" class="sku-picked__off">已下架（提交后仓库可能无法受理）</text>
        </view>
        <text class="sku-picked__op">更换 ›</text>
      </view>
      <view v-else class="sku-empty" @click="openPick(i)">
        <text class="sku-empty__t">＋ 点击选择商品</text>
      </view>

      <view class="form">
        <view class="form__row">
          <text class="form__label">申请件数</text>
          <input v-model="r.qty" class="form__input" type="number" placeholder=">0，必填" placeholder-class="ph" />
        </view>
        <view class="form__row">
          <text class="form__label">预计托盘数</text>
          <input v-model="r.palletQty" class="form__input" type="number" placeholder="可空" placeholder-class="ph" />
        </view>
        <view class="form__row">
          <text class="form__label">行备注</text>
          <input v-model="r.remark" class="form__input" type="text" placeholder="可空（≤512）" placeholder-class="ph" maxlength="512" />
        </view>
        <template v-if="batchEnabled">
          <view class="form__row">
            <text class="form__label">批次号</text>
            <input v-model="r.batchNo" class="form__input" type="text" placeholder="批次功能已开启，必填" placeholder-class="ph" maxlength="64" />
          </view>
          <view class="form__row">
            <text class="form__label">生产日期</text>
            <picker mode="date" :value="r.productionDate" :end="todayStr()" @change="onProdChange(i, $event)">
              <view class="form__picker" :class="{ ph: !r.productionDate }">{{ r.productionDate || '请选择' }}</view>
            </picker>
          </view>
          <view class="form__row">
            <text class="form__label">效期至</text>
            <picker mode="date" :value="r.expiryDate" :start="r.productionDate || '2000-01-01'" @change="onExpiryChange(i, $event)">
              <view class="form__picker" :class="{ ph: !r.expiryDate }">{{ r.expiryDate || '请选择' }}</view>
            </picker>
          </view>
        </template>
      </view>

      <!-- 批次状态提示 -->
      <view v-if="batchEnabled && expiryState(r) === 'expired'" class="tip tip--err">
        该批次已过期（效期至 {{ r.expiryDate }}）：仍可提交，仓库登记入库时将强警告并需二次确认
      </view>
      <view v-else-if="batchEnabled && expiryState(r) === 'near'" class="tip tip--warn">
        该批次将在 30 天内到效期：可提交，登记后立即进入临期预警列表
      </view>
      <view v-if="batchEnabled && dupHint(i)" class="tip tip--err">{{ dupHint(i) }}</view>
    </view>

    <!-- 添加商品 -->
    <view class="add" @click="addRow">
      <text class="add__t">＋ 添加商品行（{{ rows.length }}/{{ MAX_ROWS }}）</text>
    </view>

    <button class="btn-primary submit" :disabled="submitting" :loading="submitting" @click="doSubmit">提交入库申请</button>
    <view class="note">
      <text class="note__t">提交后由仓库在 24h 内受理，受理后在 24h 内登记；登记时会核对件数与批次并拍照存证，登记完成后通知你确认，实登件数以仓库登记为准。</text>
    </view>

    <!-- 商品选择 sheet -->
    <view v-if="skuSheetOpen" class="mask" @click="closePick">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">选择商品（{{ skus.length }}）</text>
          <text class="sheet__close" @click="closePick">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view v-if="!skusLoaded" class="hint">加载中…</view>
          <view v-else-if="!skus.length" class="hint">暂无商品，请先在「我的商品」上架</view>
          <view v-else class="sku-list">
            <view
              v-for="s in skus"
              :key="String(s.id)"
              class="sku"
              :class="{ 'sku--on': pickIndex !== null && rows[pickIndex]?.skuId != null && String(s.id) === String(rows[pickIndex].skuId) }"
              @click="pickSku(s)"
            >
              <view class="sku__main">
                <text class="sku__name">{{ labelOf(s) }}</text>
                <text class="sku__sub">
                  公开价 ¥{{ s.unitPrice.toFixed(2) }}<template v-if="s.moqPrice != null"> · 起批 ¥{{ s.moqPrice.toFixed(2) }}</template>
                  <template v-if="s.listed === false"> · 已下架</template>
                </text>
              </view>
              <text
                v-if="pickIndex !== null && rows[pickIndex]?.skuId != null && String(s.id) === String(rows[pickIndex].skuId)"
                class="sku__check"
              >✓</text>
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
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.banner {
  margin-bottom: 20rpx;
  padding: 14rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-info-bg;
  font-size: 22rpx;
  color: $cc-fg-3;
  line-height: 1.6;
}

.row {
  margin-bottom: 22rpx;
  padding: 22rpx 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14rpx;
  }

  &__no {
    font-size: 24rpx;
    font-weight: 700;
    color: $cc-fg-2;
  }

  &__del {
    padding: 6rpx 18rpx;
    border-radius: 999rpx;
    background: $cc-danger-bg;
    color: $cc-danger;
    font-size: 22rpx;
  }
}

.sku-picked {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14rpx;
  padding: 16rpx 18rpx;
  border-radius: 12rpx;
  background: $cc-info-bg;
  border: 2rpx solid $cc-accent;

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

  &__off {
    font-size: 20rpx;
    color: $cc-danger;
  }

  &__op {
    font-size: 23rpx;
    color: $cc-accent;
    flex-shrink: 0;
  }
}

.sku-empty {
  padding: 24rpx 18rpx;
  border-radius: 12rpx;
  border: 2rpx dashed $cc-border-2;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-3;
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

.tip {
  margin-top: 14rpx;
  padding: 12rpx 16rpx;
  border-radius: 10rpx;
  font-size: 21rpx;
  line-height: 1.6;

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }
}

.add {
  padding: 24rpx;
  border-radius: 12rpx;
  border: 2rpx dashed $cc-border-2;
  text-align: center;
  margin-bottom: 30rpx;

  &__t {
    font-size: 26rpx;
    font-weight: 600;
    color: $cc-accent;
  }
}

.submit {
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

.mask {
  position: fixed;
  inset: 0;
  background: rgba(2, 6, 23, 0.45);
  z-index: 20;
  display: flex;
  align-items: flex-end;
}

.sheet {
  width: 100%;
  max-height: 82vh;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  display: flex;
  flex-direction: column;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 26rpx 28rpx 18rpx;
    border-bottom: 1rpx solid $cc-border-1;
    flex-shrink: 0;
  }

  &__title {
    font-size: 30rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__close {
    font-size: 30rpx;
    color: $cc-fg-4;
    padding: 4rpx 8rpx;
  }

  &__body {
    padding: 24rpx 28rpx calc(40rpx + env(safe-area-inset-bottom));
    box-sizing: border-box;
    max-height: 72vh;
  }
}

.hint {
  padding: 60rpx 20rpx;
  text-align: center;
  font-size: 24rpx;
  color: $cc-fg-4;
}

.sku-list {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.sku {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 18rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;

  &--on {
    border: 2rpx solid $cc-accent;
    background: $cc-info-bg;
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

  &__sub {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__check {
    color: $cc-accent;
    font-size: 32rpx;
    font-weight: 700;
  }
}

.ph {
  color: $cc-fg-4;
}
</style>
