<script setup lang="ts">
// WK 临期预警（F5-W2 · US-WK-04）：GET /tenant/batches/expiring（EXPIRING∪PENDING_CLEARANCE，
// 剩余天数升序）+ POST /tenant/batches/{id}/notify-wholesaler 一键站内信（同批次 24h 限 1 → 50367）。
// 批次名/商户名本地映射（BatchVo 不含名称，契约为省 join 不带）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import type { Batch, Sku, SnowflakeId, Wholesaler } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import { BATCH_SOURCE_LABELS, BATCH_STATUS_LABELS, batchTone, expiryText, fmtDate } from '../../../utils/warehouse'

const loading = ref(false)
const thresholdDays = ref(30)
const batchEnabled = ref(false)
const rows = ref<Batch[]>([])
const whNameOf = new Map<string, string>()
const skuNameOf = new Map<string, string>()
const notifiedAtOf = new Map<string, string>()

const totalQty = computed(() => rows.value.reduce((a, b) => a + b.remainingQty, 0))
const expiredCount = computed(() => rows.value.filter((b) => (b.remainingDays ?? 9999) < 0).length)

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

async function buildNames(wid: SnowflakeId): Promise<void> {
  try {
    const skus = await wkApi.listSkuByWholesaler(wid)
    skus.forEach((s) => skuNameOf.set(String(s.id), labelOf(s)))
  } catch {
    /* 单个商户名称失败不阻塞列表 */
  }
}

function fill(): void {
  rows.value = rows.value.map((r) => ({ ...r }))
}

function cooldownMs(b: Batch): number {
  const at = notifiedAtOf.get(String(b.id)) ?? b.manualNotifiedAt
  if (!at) return 0
  const end = new Date(at).getTime() + 24 * 3600 * 1000
  return Math.max(0, end - Date.now())
}

function cooldownText(b: Batch): string {
  const ms = cooldownMs(b)
  if (ms <= 0) return ''
  const h = Math.floor(ms / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return h > 0 ? `${h}小时${m}分后可通知` : `${m}分后可通知`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const work = readWork()
    const [cfg, data] = await Promise.all([
      work ? wkApi.batchConfig(work.tenantId).catch(() => null) : Promise.resolve(null),
      wkApi.expiry.list(),
    ])
    thresholdDays.value = cfg?.expiryThresholdDays ?? 30
    batchEnabled.value = cfg?.batchEnabled === 1
    rows.value = data.list
    // 名称映射：涉及商户的 SKU 列表
    const whIds = new Set(rows.value.map((r) => r.wholesalerId))
    const ws = await wkApi.listWholesalers()
    whNameOf.clear()
    skuNameOf.clear()
    ws.forEach((w: Wholesaler) => whNameOf.set(String(w.id), w.name))
    await Promise.all([...whIds].map((id) => buildNames(id)))
    fill()
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

async function notify(b: Batch): Promise<void> {
  if (cooldownMs(b) > 0) {
    uni.showToast({ title: '该批次 24h 内已通知商户', icon: 'none' })
    return
  }
  try {
    await wkApi.expiry.notify(b.id)
    notifiedAtOf.set(String(b.id), new Date().toISOString())
    fill()
    uni.showToast({ title: `已通知「${whNameOf.get(String(b.wholesalerId)) ?? '商户'}」处理`, icon: 'none' })
  } catch {
    /* request 已 toast（含 50367） */
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
    <view v-if="batchEnabled === false" class="hint">
      批次功能未启用，不产生临期预警。如需启用请在电脑端店铺设置中开启（将生成默认批次吸收存量）。
    </view>

    <view v-else class="sum">
      <view class="sum__item">
        <text class="sum__num">{{ rows.length }}</text>
        <text class="sum__lbl">临期/待清理批次</text>
      </view>
      <view class="sum__item">
        <text class="sum__num">{{ totalQty }}</text>
        <text class="sum__lbl">推算剩余(件)</text>
      </view>
      <view class="sum__item sum__item--err">
        <text class="sum__num">{{ expiredCount }}</text>
        <text class="sum__lbl">已过期批次</text>
      </view>
    </view>

    <view v-if="rows.length" class="sec-tip">临期阈值：{{ thresholdDays }} 天（02:00 日扫判定）</view>

    <view v-if="rows.length" class="list">
      <view v-for="b in rows" :key="String(b.id)" class="card">
        <view class="card__head">
          <text class="card__sku">{{ skuNameOf.get(String(b.skuId)) ?? 'SKU ' + b.skuId }}</text>
          <view class="card__badges">
            <text class="tag" :class="batchTone(b.status) === 'warn' ? 'tag--warn' : batchTone(b.status) === 'err' ? 'tag--err' : 'tag--info'">
              {{ BATCH_STATUS_LABELS[b.status] ?? b.status }}
            </text>
            <text v-if="(b.remainingDays ?? 0) < 0" class="tag tag--err">已过期 {{ Math.abs(b.remainingDays ?? 0) }} 天</text>
            <text v-else class="tag tag--warn">{{ expiryText(b.remainingDays, thresholdDays) }}</text>
          </view>
        </view>
        <view class="card__meta">
          <text class="card__no">{{ b.batchNo }}</text>
          <text class="card__dot">·</text>
          <text class="card__ws">{{ whNameOf.get(String(b.wholesalerId)) ?? '商户' }}</text>
        </view>
        <view class="card__foot">
          <view class="card__kv">
            <view class="card__kv-line"><text class="k">到效期</text><text class="v" :class="{ 'v--err': (b.remainingDays ?? 0) < 0 }">{{ fmtDate(b.expiryDate) }}</text></view>
            <view class="card__kv-line"><text class="k">推算剩余</text><text class="v">{{ b.remainingQty }} 件</text></view>
            <view v-if="b.clearedAt" class="card__kv-line"><text class="k">清理完成</text><text class="v">{{ fmtDate(b.clearedAt) }}</text></view>
            <view class="card__kv-line"><text class="k">来源</text><text class="v">{{ BATCH_SOURCE_LABELS[b.source] ?? b.source }}</text></view>
          </view>
          <button
            class="btn-notify"
            :class="{ 'btn-notify--off': cooldownMs(b) > 0 }"
            :disabled="cooldownMs(b) > 0"
            @click="notify(b)"
          >{{ cooldownMs(b) > 0 ? cooldownText(b) : '一键通知商户' }}</button>
        </view>
      </view>
    </view>

    <view v-else-if="!loading" class="empty">{{ batchEnabled === false ? '' : '暂无临期/待清理批次，库存状态良好' }}</view>
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

.sum {
  display: flex;
  padding: 26rpx 10rpx;
  border-radius: $cc-card-radius;
  background: linear-gradient(135deg, #b45309 0%, #92400e 100%);
  color: #fff;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6rpx;

    &--err {
      .sum__num {
        color: #fed7aa;
      }
    }
  }

  &__num {
    font-size: 44rpx;
    font-weight: 800;
  }

  &__lbl {
    font-size: 20rpx;
    opacity: 0.8;
  }
}

.sec-tip {
  margin: 20rpx 4rpx 12rpx;
  font-size: 22rpx;
  color: $cc-fg-4;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.card {
  padding: 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12rpx;
  }

  &__sku {
    flex: 1;
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__badges {
    display: flex;
    flex-wrap: wrap;
    gap: 8rpx;
    justify-content: flex-end;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 10rpx;
    margin: 12rpx 0;
  }

  &__no {
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-accent;
  }

  &__dot {
    color: $cc-fg-4;
  }

  &__ws {
    font-size: 24rpx;
    color: $cc-fg-4;
    flex: 1;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__foot {
    display: flex;
    align-items: center;
    gap: 16rpx;
  }

  &__kv {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__kv-line {
    display: flex;
    gap: 12rpx;
    font-size: 23rpx;

    .k {
      width: 130rpx;
      color: $cc-fg-4;
      flex-shrink: 0;
    }

    .v {
      color: $cc-fg-2;
      font-weight: 500;
    }

    .v--err {
      color: $cc-danger;
      font-weight: 700;
    }
  }
}

.btn-notify {
  width: 216rpx;
  height: 72rpx;
  line-height: 72rpx;
  padding: 0;
  font-size: 23rpx;
  border-radius: 12rpx;
  background: $cc-danger;
  color: #fff;
  margin: 0;

  &--off {
    background: $cc-bg-3;
    color: $cc-fg-4;
  }
}

.tag {
  display: inline-block;
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  font-size: 20rpx;

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }
}

.empty {
  padding: 60rpx 0;
  text-align: center;
  font-size: 24rpx;
  color: $cc-fg-4;
}
</style>
