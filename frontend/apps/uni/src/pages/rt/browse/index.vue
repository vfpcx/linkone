<script setup lang="ts">
// RT 商品广场（跨店铺浏览商品 · 先搜货再进店）：
// 顶部搜索框（可空 = 逛全部在售商品）+ 触底分页 + 下拉刷新 + 定位距离排序。
// 数据源 GET /rt/sku-search（公开，无登录态）：在售 SKU（listed + 有货 + 商户/租户 ACTIVE）
// 每张卡展示公开价/库存 + 所属商户/仓库，点击进入对应店铺（tenantSimpleCode）提交意向单。
// 联系方式仍走询价确认后 reveal（D-RT-01），本页不下发任何 PII。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import type { RtSkuSearchItem } from '@cangchu/api-types'
import { rtApi } from '../../../api/rt'
import { fmtPrice } from '../../../utils/format'

const PAGE_SIZE = 20

const keyword = ref('')
/** 已生效的搜索词（点搜索/回车后才更新，避免输入抖动触发请求） */
const activeKeyword = ref('')
const rows = ref<RtSkuSearchItem[]>([])
const total = ref(0)
const pageNo = ref(1)
const loading = ref(false)
const loaded = ref(false)
const errMsg = ref('')
const lat = ref<number | undefined>()
const lng = ref<number | undefined>()
const located = ref(false)

const hasMore = computed(() => rows.value.length < Number(total.value))
const empty = computed(() => loaded.value && !loading.value && rows.value.length === 0)

/** 附近优先（有定位且结果带距离）时给出提示 */
const sortedByDistance = computed(() => located.value && rows.value.some((r) => r.distanceMeters != null))

function formatDistance(meters: number): string {
  if (meters < 1000) return `${meters}m`
  return `${(meters / 1000).toFixed(1)}km`
}

async function fetchPage(p: number): Promise<void> {
  if (loading.value) return
  loading.value = true
  errMsg.value = ''
  try {
    const res = await rtApi.searchSkus({
      keyword: activeKeyword.value || undefined,
      lat: lat.value,
      lng: lng.value,
      page: p,
      size: PAGE_SIZE,
    })
    const list = res.records ?? []
    rows.value = p === 1 ? list : rows.value.concat(list)
    total.value = Number(res.total ?? 0)
    pageNo.value = Number(res.current ?? p)
  } catch {
    if (p === 1) rows.value = []
    errMsg.value = '加载失败，下拉可重试'
  } finally {
    loading.value = false
    loaded.value = true
  }
}

/** 定位只用于距离排序：失败/拒绝不阻塞浏览（后端不传坐标即按最新上架排序） */
function locate(): void {
  if (located.value) return
  uni.getLocation({
    type: 'gcj02',
    success: (res) => {
      lat.value = res.latitude
      lng.value = res.longitude
      located.value = true
      // 已加载过则带距离重排
      if (loaded.value) void fetchPage(1)
    },
    fail: () => {
      /* 未授权定位：按最新上架排序，不提示打断浏览 */
    },
  })
}

function onSearch(): void {
  const kw = keyword.value.trim()
  activeKeyword.value = kw
  uni.setNavigationBarTitle({ title: kw ? `搜“${kw}”` : '逛商品' })
  void fetchPage(1)
}

function clearKeyword(): void {
  keyword.value = ''
  onSearch()
}

function goStore(item: RtSkuSearchItem): void {
  uni.navigateTo({ url: `/pages/rt/store/index?code=${encodeURIComponent(item.tenantSimpleCode)}` })
}

function goBack(): void {
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) })
}

onLoad((options) => {
  const kw = (options?.keyword as string) ?? ''
  keyword.value = decodeURIComponent(kw)
  activeKeyword.value = keyword.value.trim()
  if (activeKeyword.value) uni.setNavigationBarTitle({ title: `搜“${activeKeyword.value}”` })
  locate()
  void fetchPage(1)
})

onPullDownRefresh(async () => {
  await fetchPage(1)
  uni.stopPullDownRefresh()
})

onReachBottom(() => {
  if (!hasMore.value || loading.value) return
  void fetchPage(pageNo.value + 1)
})
</script>

<template>
  <view class="page">
    <!-- 搜索头（sticky）：空关键词 = 逛全部在售商品 -->
    <view class="bar">
      <view class="bar__row">
        <input
          v-model="keyword"
          class="bar__input"
          placeholder="搜商品名 / 规格，如：苹果 / 5kg"
          placeholder-class="ph"
          maxlength="50"
          confirm-type="search"
          @confirm="onSearch"
        />
        <button class="bar__btn" :loading="loading && pageNo === 1" @click="onSearch">搜索</button>
      </view>
      <view class="bar__meta">
        <text v-if="activeKeyword" class="bar__kw">
          关键词「{{ activeKeyword }}」
          <text class="bar__clear" @click="clearKeyword">清空</text>
        </text>
        <text v-else class="bar__kw">全部在售商品 · 跨店铺</text>
        <text v-if="loaded" class="bar__count">共 {{ total }} 件</text>
      </view>
      <text v-if="sortedByDistance" class="bar__tip">已按离你最近排序</text>
    </view>

    <!-- 商品列表 -->
    <view v-if="rows.length" class="list">
      <view v-for="item in rows" :key="item.skuId" class="sku" @click="goStore(item)">
        <view class="sku__main">
          <text class="sku__name">{{ item.name }}</text>
          <text v-if="item.spec" class="sku__spec">规格 {{ item.spec }}</text>
          <view class="sku__from">
            <text class="sku__wa">{{ item.wholesalerName }}</text>
            <text class="sku__store">{{ item.storeName }} · {{ item.tenantSimpleCode }}</text>
          </view>
        </view>
        <view class="sku__side">
          <text class="sku__price">{{ fmtPrice(item.unitPrice) }}</text>
          <text v-if="item.moqQty > 1" class="sku__moq">{{ item.moqQty }} 件起 {{ fmtPrice(item.moqPrice) }}</text>
          <text class="sku__stock">库存 {{ item.stockQty }}</text>
          <text v-if="item.distanceMeters != null" class="sku__dist">{{ formatDistance(item.distanceMeters) }}</text>
        </view>
        <text class="sku__go">进店 ›</text>
      </view>

      <view class="foot">
        <text v-if="loading" class="foot__text">加载中…</text>
        <text v-else-if="hasMore" class="foot__text">上拉加载更多</text>
        <text v-else class="foot__text">没有更多了 · 共 {{ total }} 件</text>
      </view>
      <text class="foot__note">价格与库存为商户公开信息；点进店铺可提交意向单，商户确认后主动联系你</text>
    </view>

    <!-- 空态 / 首屏加载 -->
    <view v-else-if="loading && !loaded" class="hint">
      <text class="hint__text">正在加载在售商品…</text>
    </view>
    <view v-else-if="empty" class="hint">
      <text class="hint__text">
        {{ errMsg || (activeKeyword ? `没搜到「${activeKeyword}」，换个关键词试试` : '暂时还没有在售商品') }}
      </text>
      <view class="hint__ops">
        <button v-if="activeKeyword" class="btn-ghost hint__btn" @click="clearKeyword">看看全部商品</button>
        <button class="btn-ghost hint__btn" @click="goBack">回首页逛仓库</button>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 60rpx;
  background: $cc-bg-2;
  box-sizing: border-box;
}

/* ===== 搜索头 ===== */
.bar {
  padding: 8rpx 4rpx 20rpx;

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
    color: $cc-fg-1;
    box-sizing: border-box;
  }

  &__btn {
    width: 168rpx;
    height: 88rpx;
    margin: 0;
    line-height: 88rpx;
    border-radius: 12rpx;
    background: $cc-rt-accent;
    color: #fff;
    font-size: 30rpx;
    font-weight: 600;

    &::after {
      border: none;
    }
  }

  &__meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 14rpx;
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__kw {
    display: flex;
    align-items: center;
    gap: 12rpx;
    min-width: 0;
  }

  &__clear {
    padding: 2rpx 14rpx;
    border-radius: 999rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;
    font-size: 22rpx;
  }

  &__count {
    flex-shrink: 0;
    color: $cc-fg-4;
  }

  &__tip {
    display: block;
    margin-top: 8rpx;
    font-size: 22rpx;
    color: $cc-success;
  }
}

.ph {
  color: $cc-fg-4;
}

/* ===== 商品列表 ===== */
.list {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.sku {
  position: relative;
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 26rpx 24rpx 34rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8rpx;
  }

  &__name {
    font-size: 31rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__spec {
    font-size: 24rpx;
    color: $cc-fg-3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__from {
    display: flex;
    align-items: center;
    gap: 12rpx;
    margin-top: 4rpx;
    font-size: 22rpx;
    min-width: 0;
  }

  &__wa {
    flex-shrink: 0;
    padding: 2rpx 12rpx;
    border-radius: 6rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 240rpx;
  }

  &__store {
    flex: 1;
    min-width: 0;
    color: $cc-fg-4;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__side {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 6rpx;
  }

  &__price {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-rt-accent;
  }

  &__moq {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__stock {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__dist {
    font-size: 22rpx;
    color: $cc-success;
  }

  &__go {
    position: absolute;
    right: 24rpx;
    bottom: 10rpx;
    font-size: 22rpx;
    color: $cc-rt-accent;
  }
}

/* ===== 列表尾 / 空态 ===== */
.foot {
  padding: 20rpx 0 8rpx;
  text-align: center;

  &__text {
    font-size: 24rpx;
    color: $cc-fg-4;
  }

  &__note {
    display: block;
    padding: 0 20rpx 20rpx;
    text-align: center;
    font-size: 22rpx;
    line-height: 1.6;
    color: $cc-fg-4;
  }
}

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

  &__ops {
    display: flex;
    gap: 20rpx;
  }

  &__btn {
    min-width: 240rpx;
    height: 84rpx;
    margin: 0;
    line-height: 84rpx;
    border-radius: 12rpx;
    font-size: 28rpx;
  }
}
</style>
