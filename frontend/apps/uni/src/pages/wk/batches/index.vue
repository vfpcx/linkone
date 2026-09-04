<script setup lang="ts">
// WK 批次登记簿（F5-W2 · 13 §3）：GET /tenant/batches（全量）+ PUT /{id}（默认批次补录效期）
// + PUT /{id}/location（C2 移库）+ GET /{id}/location-logs（变更记录）。
// BatchVo 不含名称 → 商户/SKU 本地映射；remainingQty 为 02:00 离线推算值（UI 标注）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import type { Batch, BatchLocationLog, SnowflakeId, TenantBatchConfig, Wholesaler } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import {
  BATCH_SOURCE_LABELS,
  BATCH_STATUS_LABELS,
  batchTone,
  expiryText,
  fmtDate,
  fmtDateTime,
} from '../../../utils/warehouse'

const SEGMENTS: Array<{ value: string; label: string }> = [
  { value: '', label: '全部' },
  { value: 'IN_STOCK', label: '在库' },
  { value: 'EXPIRING', label: '临期' },
  { value: 'PENDING_CLEARANCE', label: '待清理' },
  { value: 'SOLD_OUT', label: '已售罄' },
  { value: 'CLEARED', label: '已清库' },
  { value: 'CLOSED', label: '已冻结' },
]

const loading = ref(false)
const cfg = ref<TenantBatchConfig | null>(null)
const all = ref<Batch[]>([])
const seg = ref('')
const keyword = ref('')
const whNameOf = new Map<string, string>()
const skuNameOf = new Map<string, string>()

// 弹层
const active = ref<Batch | null>(null)
const locInput = ref('')
const logs = ref<BatchLocationLog[]>([])
const prod = ref('')
const exp = ref('')
const saving = ref(false)

const shown = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return all.value.filter((b) => {
    if (seg.value && b.status !== seg.value) return false
    if (!kw) return true
    const sku = skuNameOf.get(String(b.skuId)) ?? ''
    return `${b.batchNo} ${sku}`.toLowerCase().includes(kw)
  })
})

const inStockCount = computed(() => all.value.filter((b) => b.status === 'IN_STOCK' || b.status === 'EXPIRING' || b.status === 'PENDING_CLEARANCE').length)

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId && work.role === 'WK')
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function todayStr(): string {
  const d = new Date()
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function skuNameOfId(id: SnowflakeId): string {
  return skuNameOf.get(String(id)) ?? 'SKU ' + id
}

function merchantNameOf(id: SnowflakeId): string {
  return whNameOf.get(String(id)) ?? ''
}

function canBackfill(b: Batch): boolean {
  return b.source === 'DEFAULT' && b.status !== 'CLEARED' && b.status !== 'CLOSED' && (!b.expiryDate || !b.productionDate)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const work = readWork()
    const [c, data, ws] = await Promise.all([
      work ? wkApi.batchConfig(work.tenantId).catch(() => null) : Promise.resolve(null),
      wkApi.batch.list(),
      wkApi.listWholesalers(),
    ])
    cfg.value = c
    all.value = data.list
    whNameOf.clear()
    skuNameOf.clear()
    ws.forEach((w: Wholesaler) => whNameOf.set(String(w.id), w.name))
    const whIds = new Set(all.value.map((b) => b.wholesalerId))
    const skuRows = await Promise.all(
      [...whIds].map(async (wid) => {
        try {
          return await wkApi.listSkuByWholesaler(wid)
        } catch {
          return []
        }
      }),
    )
    skuRows.flat().forEach((s) => skuNameOf.set(String(s.id), s.spec ? `${s.name}（${s.spec}）` : s.name))
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function open(b: Batch): void {
  active.value = { ...b }
  locInput.value = b.location ?? ''
  logs.value = []
  if (cfg.value?.locationEnabled === 1) {
    void wkApi.batch
      .locationLogs(b.id, 1, 20)
      .then((p) => {
        logs.value = p.records ?? []
      })
      .catch(() => {
        logs.value = []
      })
  }
}

function closeSheet(): void {
  if (!saving.value) active.value = null
}

async function saveLocation(): Promise<void> {
  const b = active.value
  if (!b || saving.value) return
  saving.value = true
  try {
    const val = locInput.value.trim()
    const updated = await wkApi.batch.updateLocation(b.id, { location: val === '' ? null : val })
    const idx = all.value.findIndex((x) => String(x.id) === String(b.id))
    if (idx >= 0) all.value[idx] = updated
    active.value = { ...updated }
    logs.value = []
    const p = await wkApi.batch.locationLogs(b.id, 1, 20)
    logs.value = p.records ?? []
    uni.showToast({ title: val === '' ? '货位已清空' : `已移至「${val}」`, icon: 'none' })
  } catch {
    /* request 已 toast */
  } finally {
    saving.value = false
  }
}

function onProd(e: { detail: { value: string } }): void {
  prod.value = e.detail.value
}

function onExp(e: { detail: { value: string } }): void {
  exp.value = e.detail.value
}

async function saveBackfill(): Promise<void> {
  const b = active.value
  if (!b || saving.value) return
  if (!prod.value && !exp.value) {
    uni.showToast({ title: '请选择生产日期或到效期', icon: 'none' })
    return
  }
  saving.value = true
  try {
    const updated = await wkApi.batch.backfill(b.id, {
      productionDate: prod.value || undefined,
      expiryDate: exp.value || undefined,
    })
    const idx = all.value.findIndex((x) => String(x.id) === String(b.id))
    if (idx >= 0) all.value[idx] = updated
    active.value = { ...updated }
    prod.value = ''
    exp.value = ''
    uni.showToast({ title: '效期已补录', icon: 'none' })
  } catch {
    /* request 已 toast（40205/40206/50362 等） */
  } finally {
    saving.value = false
  }
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <view v-if="cfg && cfg.batchEnabled !== 1" class="hint">
      批次功能未启用。启用后入库登记批次/效期并自动产生默认批次；本页将展示批次登记簿。
    </view>

    <template v-else>
      <view class="bar">
        <view class="bar__stat">
          <text class="bar__num">{{ inStockCount }}</text>
          <text class="bar__lbl">在库批次</text>
        </view>
        <view class="bar__stat">
          <text class="bar__num">{{ all.length }}</text>
          <text class="bar__lbl">登记簿总数</text>
        </view>
        <view v-if="cfg && cfg.locationEnabled === 1" class="bar__loc">货位功能已开启</view>
      </view>

      <view class="seg">
        <scroll-view scroll-x show-scrollbar="false">
          <view
            v-for="s in SEGMENTS"
            :key="s.value"
            class="seg__item"
            :class="{ 'seg__item--on': seg === s.value }"
            @click="seg = s.value"
          >{{ s.label }}</view>
        </scroll-view>
      </view>

      <view class="search">
        <input v-model="keyword" class="search__input" type="text" placeholder="搜索批次号 / 货品名称" placeholder-class="ph" />
      </view>

      <view v-if="shown.length" class="list">
        <view v-for="b in shown" :key="String(b.id)" class="card" @click="open(b)">
          <view class="card__head">
            <text class="card__name">{{ skuNameOfId(b.skuId) }}</text>
            <view class="card__tags">
              <text v-if="b.location" class="tag tag--info">货位 {{ b.location }}</text>
              <text v-if="canBackfill(b)" class="tag tag--warn">待补录效期</text>
            </view>
          </view>
          <view class="card__meta">
            <text class="card__no">{{ b.batchNo }}</text>
            <text class="card__dot">·</text>
            <text class="card__ws">{{ merchantNameOf(b.wholesalerId) }}</text>
          </view>
          <view class="card__foot">
            <view class="card__kv">
              <text class="k">状态</text>
              <text class="v">{{ BATCH_STATUS_LABELS[b.status] ?? b.status }}</text>
              <text class="k">效期</text>
              <text class="v" :class="{ 'v--err': (b.remainingDays ?? 0) < 0 }">{{ fmtDate(b.expiryDate) }}</text>
              <text class="v v--dim" v-if="b.expiryDate">（{{ expiryText(b.remainingDays, cfg?.expiryThresholdDays ?? 30) }}）</text>
            </view>
            <view class="card__qty">
              <text class="card__qty-num">{{ b.remainingQty }}</text>
              <text class="card__qty-lbl">推算剩余件</text>
            </view>
          </view>
        </view>
      </view>
      <view v-else-if="!loading" class="empty">暂无批次记录</view>
    </template>

    <!-- 批次详情弹层 -->
    <view v-if="active" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <view class="sheet__title">
          <text class="sheet__name">{{ active.batchNo }}</text>
          <text class="sheet__status" :class="batchTone(active.status) === 'ok' ? 'sheet__status--ok' : batchTone(active.status) === 'warn' ? 'sheet__status--warn' : batchTone(active.status) === 'err' ? 'sheet__status--err' : 'sheet__status--muted'">
            {{ BATCH_STATUS_LABELS[active.status] ?? active.status }}
          </text>
        </view>
        <view class="kv">
          <view class="kv__row"><text class="kv__k">货品</text><text class="kv__v">{{ skuNameOfId(active.skuId) }}</text></view>
          <view class="kv__row"><text class="kv__k">商户</text><text class="kv__v">{{ merchantNameOf(active.wholesalerId) }}</text></view>
          <view class="kv__row"><text class="kv__k">生产日期</text><text class="kv__v">{{ fmtDate(active.productionDate) }}</text></view>
          <view class="kv__row"><text class="kv__k">到效期</text><text class="kv__v" :class="{ 'v--err': (active.remainingDays ?? 0) < 0 }">{{ fmtDate(active.expiryDate) }}</text></view>
          <view class="kv__row"><text class="kv__k">推算剩余</text><text class="kv__v">{{ active.remainingQty }} 件（02:00 日推）</text></view>
          <view class="kv__row"><text class="kv__k">累计入库</text><text class="kv__v">{{ active.initialQty }} 件</text></view>
          <view class="kv__row"><text class="kv__k">来源</text><text class="kv__v">{{ BATCH_SOURCE_LABELS[active.source] ?? active.source }}</text></view>
          <view class="kv__row"><text class="kv__k">当前货位</text><text class="kv__v">{{ active.location || '未指定' }}</text></view>
          <view class="kv__row"><text class="kv__k">创建时间</text><text class="kv__v">{{ fmtDateTime(active.createdAt) }}</text></view>
        </view>

        <!-- 移库（货位开关开启） -->
        <view v-if="cfg && cfg.locationEnabled === 1" class="block">
          <view class="block__title">调整货位（移库）</view>
          <view class="row">
            <input v-model="locInput" class="input" type="text" maxlength="64" placeholder="输入新货位号（留空=清空）" placeholder-class="ph" />
            <button class="btn-sm" :loading="saving" :disabled="saving" @click="saveLocation">保存</button>
          </view>
          <view v-if="logs.length" class="logs">
            <view v-for="(l, i) in logs" :key="String(l.id) || i" class="logs__row">
              <text class="logs__t">{{ fmtDateTime(l.createdAt) }}</text>
              <text class="logs__d">{{ l.fromLocation || '未指定' }} → {{ l.toLocation || '空' }}</text>
            </view>
          </view>
        </view>

        <!-- 默认批次补录效期 -->
        <view v-if="canBackfill(active)" class="block">
          <view class="block__title">补录效期（默认批次）</view>
          <view class="row">
            <picker class="picker" mode="date" :value="prod || todayStr()" @change="onProd">
              <view class="picker__box"><text class="picker__lbl">生产日期</text><text class="picker__val">{{ prod || '选择' }}</text></view>
            </picker>
            <picker class="picker" mode="date" :value="exp || todayStr()" @change="onExp">
              <view class="picker__box"><text class="picker__lbl">到效期</text><text class="picker__val">{{ exp || '选择' }}</text></view>
            </picker>
          </view>
          <button class="btn-primary btn-save" :loading="saving" :disabled="saving" @click="saveBackfill">保存补录</button>
        </view>

        <view class="sheet__close" @click="closeSheet">关闭</view>
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

.hint {
  padding: 22rpx;
  border-radius: 12rpx;
  background: $cc-bg-3;
  font-size: 24rpx;
  color: $cc-fg-4;
  line-height: 1.7;
}

.bar {
  display: flex;
  align-items: center;
  gap: 30rpx;
  padding: 24rpx 26rpx;
  border-radius: $cc-card-radius;
  background: linear-gradient(135deg, #334155 0%, #1e293b 100%);
  color: #fff;

  &__stat {
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__num {
    font-size: 36rpx;
    font-weight: 800;
  }

  &__lbl {
    font-size: 20rpx;
    opacity: 0.75;
  }

  &__loc {
    margin-left: auto;
    padding: 6rpx 14rpx;
    border-radius: 999rpx;
    background: rgba(255, 255, 255, 0.18);
    font-size: 20rpx;
  }
}

.seg {
  margin-top: 18rpx;
  white-space: nowrap;

  &__item {
    display: inline-block;
    padding: 12rpx 26rpx;
    margin-right: 10rpx;
    border-radius: 999rpx;
    background: $cc-bg-1;
    border: 2rpx solid $cc-border-1;
    font-size: 24rpx;
    color: $cc-fg-2;

    &--on {
      background: $cc-brand-primary;
      border-color: $cc-brand-primary;
      color: #fff;
      font-weight: 600;
    }
  }
}

.search {
  margin: 16rpx 0;

  &__input {
    height: 76rpx;
    padding: 0 24rpx;
    border-radius: 999rpx;
    background: $cc-bg-1;
    border: 2rpx solid $cc-border-1;
    font-size: 26rpx;
    color: $cc-fg-1;
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.card {
  padding: 20rpx 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10rpx;
  }

  &__name {
    flex: 1;
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__tags {
    display: flex;
    gap: 8rpx;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 10rpx;
    margin: 10rpx 0;
  }

  &__no {
    font-size: 23rpx;
    font-weight: 600;
    color: $cc-accent;
  }

  &__dot {
    color: $cc-fg-4;
  }

  &__ws {
    font-size: 23rpx;
    color: $cc-fg-4;
    flex: 1;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__foot {
    display: flex;
    align-items: center;
    gap: 14rpx;
  }

  &__kv {
    flex: 1;
    display: flex;
    align-items: baseline;
    gap: 10rpx;
    flex-wrap: wrap;

    .k {
      font-size: 21rpx;
      color: $cc-fg-4;
    }

    .v {
      font-size: 24rpx;
      color: $cc-fg-2;
      font-weight: 600;
    }

    .v--err {
      color: $cc-danger;
    }

    .v--dim {
      color: $cc-fg-4;
      font-size: 20rpx;
      font-weight: 400;
    }
  }

  &__qty {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2rpx;
  }

  &__qty-num {
    font-size: 32rpx;
    font-weight: 800;
    color: $cc-fg-1;
  }

  &__qty-lbl {
    font-size: 18rpx;
    color: $cc-fg-4;
  }
}

.tag {
  display: inline-block;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  font-size: 19rpx;

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
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
  max-height: 82vh;
  overflow-y: auto;
  padding: 30rpx 30rpx;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  border-radius: 28rpx 28rpx 0 0;
  background: $cc-bg-1;
  box-sizing: border-box;

  &__title {
    display: flex;
    align-items: center;
    gap: 14rpx;
    margin-bottom: 18rpx;
  }

  &__name {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__status {
    padding: 4rpx 12rpx;
    border-radius: 999rpx;
    font-size: 20rpx;

    &--ok {
      background: $cc-success-bg;
      color: $cc-success;
    }

    &--warn {
      background: $cc-warning-bg;
      color: $cc-warning;
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

  &__close {
    margin-top: 24rpx;
    height: 84rpx;
    line-height: 84rpx;
    text-align: center;
    border-radius: 14rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;
    font-size: 28rpx;
  }
}

.kv {
  display: flex;
  flex-direction: column;

  &__row {
    display: flex;
    align-items: baseline;
    padding: 10rpx 0;
    font-size: 24rpx;
  }

  &__k {
    width: 150rpx;
    flex-shrink: 0;
    color: $cc-fg-4;
  }

  &__v {
    flex: 1;
    color: $cc-fg-1;
    font-weight: 500;
  }

  .v--err {
    color: $cc-danger;
    font-weight: 700;
  }
}

.block {
  margin-top: 22rpx;
  padding-top: 18rpx;
  border-top: 2rpx solid $cc-bg-2;

  &__title {
    font-size: 25rpx;
    font-weight: 700;
    color: $cc-fg-2;
    margin-bottom: 14rpx;
  }
}

.row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.input {
  flex: 1;
  height: 78rpx;
  padding: 0 20rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 10rpx;
  font-size: 26rpx;
  color: $cc-fg-1;
  background: $cc-bg-2;
}

.btn-sm {
  width: 150rpx;
  height: 78rpx;
  line-height: 78rpx;
  padding: 0;
  margin: 0;
  font-size: 25rpx;
  border-radius: 10rpx;
  background: $cc-brand-primary;
  color: #fff;
}

.logs {
  margin-top: 14rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;

  &__row {
    display: flex;
    justify-content: space-between;
    font-size: 22rpx;
    color: $cc-fg-3;
    background: $cc-bg-2;
    border-radius: 8rpx;
    padding: 10rpx 14rpx;
  }

  &__d {
    color: $cc-fg-2;
    font-weight: 600;
  }
}

.picker {
  flex: 1;
  min-width: 0;

  &__box {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 78rpx;
    padding: 0 18rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 10rpx;
    background: $cc-bg-2;
  }

  &__lbl {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__val {
    font-size: 24rpx;
    color: $cc-fg-1;
    font-weight: 600;
  }
}

.btn-save {
  margin-top: 16rpx;
  height: 82rpx;
  font-size: 27rpx;
}

.empty {
  padding: 60rpx 0;
  text-align: center;
  font-size: 24rpx;
  color: $cc-fg-4;
}

.ph {
  color: $cc-fg-4;
}
</style>
