<script setup lang="ts">
// WK 库存查询（F5-W2）：GET /tenant/inventories?wholesalerId=（InventoryVo）+ GET /tenant/skus?wholesalerId=（名称）。
// 按商户查看全部在库行（含 0），金额列以公开价估算；批次/临期/移库见「批次登记簿」。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import type { InventoryItem, Sku, SnowflakeId, Wholesaler } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import { fmtDateTime } from '../../../utils/warehouse'

const loading = ref(false)
const merchants = ref<Wholesaler[]>([])
const whId = ref<SnowflakeId | null>(null)
const rows = ref<Array<InventoryItem & { sku?: Sku }>>([])
const keyword = ref('')

const pickedWh = computed(() => merchants.value.find((w) => String(w.id) === String(whId.value)) ?? null)
const totalQty = computed(() => rows.value.reduce((a, r) => a + r.qty, 0))
const totalPallet = computed(() => rows.value.reduce((a, r) => a + (r.palletQty ?? 0), 0))
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) => {
    const name = r.sku ? (r.sku.spec ? `${r.sku.name}${r.sku.spec}` : r.sku.name).toLowerCase() : ''
    return name.includes(kw)
  })
})

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId && work.role === 'WK')
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

async function loadData(): Promise<void> {
  loading.value = true
  try {
    const [ws, inv] = await Promise.all([
      wkApi.listWholesalers(),
      whId.value ? wkApi.listInventories(whId.value) : Promise.resolve([] as InventoryItem[]),
    ])
    merchants.value = ws
    if (!whId.value && ws.length) whId.value = ws[0].id
    if (whId.value) {
      const skus = await wkApi.listSkuByWholesaler(whId.value).catch(() => [] as Sku[])
      const map = new Map(skus.map((s) => [String(s.id), s]))
      rows.value = inv.map((it) => ({ ...it, sku: map.get(String(it.skuId)) }))
    } else {
      rows.value = []
    }
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

async function pickWh(id: SnowflakeId): Promise<void> {
  whId.value = id
  keyword.value = ''
  await loadData()
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void loadData()
})

onPullDownRefresh(async () => {
  await loadData()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <scroll-view v-if="merchants.length" scroll-x class="chips" show-scrollbar="false">
      <view
        v-for="w in merchants"
        :key="String(w.id)"
        class="chip"
        :class="{ 'chip--on': String(w.id) === String(whId) }"
        @click="pickWh(w.id)"
      >{{ w.name }}</view>
    </scroll-view>

    <view v-if="pickedWh" class="sum">
      <view class="sum__item">
        <text class="sum__num">{{ totalQty }}</text>
        <text class="sum__lbl">库存合计(件)</text>
      </view>
      <view class="sum__item">
        <text class="sum__num">{{ totalPallet }}</text>
        <text class="sum__lbl">托盘占用</text>
      </view>
      <view class="sum__item">
        <text class="sum__num">{{ filtered.length }}</text>
        <text class="sum__lbl">SKU 行数</text>
      </view>
    </view>

    <view v-if="pickedWh" class="search">
      <input v-model="keyword" class="search__input" type="text" placeholder="搜索货品名称" placeholder-class="ph" />
    </view>

    <view v-if="filtered.length" class="list">
      <view v-for="r in filtered" :key="String(r.id)" class="card">
        <view class="card__main">
          <text class="card__name">{{ r.sku ? (r.sku.spec ? `${r.sku.name}（${r.sku.spec}）` : r.sku.name) : 'SKU ' + r.skuId }}</text>
          <text class="card__sub">{{ pickedWh?.name }} · 更新 {{ fmtDateTime(r.updatedAt) }}</text>
        </view>
        <view class="card__nums">
          <view class="card__num-row">
            <text class="card__qty" :class="{ 'card__qty--zero': r.qty === 0 }">{{ r.qty }}</text>
            <text class="card__unit">件</text>
          </view>
          <text class="card__pallet">托盘 {{ r.palletQty ?? 0 }}</text>
        </view>
      </view>
    </view>
    <view v-else-if="!loading" class="empty">{{ pickedWh ? '该商户暂无库存记录' : '请先选择商户' }}</view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.chips {
  white-space: nowrap;
}

.chip {
  display: inline-block;
  padding: 12rpx 24rpx;
  margin-right: 12rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
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

.sum {
  display: flex;
  margin-top: 20rpx;
  padding: 24rpx 0;
  border-radius: $cc-card-radius;
  background: linear-gradient(135deg, #0f766e 0%, #134e4a 100%);
  color: #fff;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4rpx;
  }

  &__num {
    font-size: 38rpx;
    font-weight: 800;
  }

  &__lbl {
    font-size: 20rpx;
    opacity: 0.8;
  }
}

.search {
  margin: 18rpx 0;

  &__input {
    height: 80rpx;
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 20rpx 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 6rpx;
    min-width: 0;
  }

  &__name {
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__sub {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__nums {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 4rpx;
    flex-shrink: 0;
  }

  &__num-row {
    display: flex;
    align-items: baseline;
    gap: 6rpx;
  }

  &__qty {
    font-size: 40rpx;
    font-weight: 800;
    color: $cc-fg-1;

    &--zero {
      color: $cc-fg-4;
    }
  }

  &__unit {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__pallet {
    font-size: 21rpx;
    color: $cc-fg-4;
  }
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
