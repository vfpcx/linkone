<script setup lang="ts">
// WA/WE 询价处理（F3 · US-WA-06 / US-WE-03）：
// 列表（待确认/全部/历史）→ 点行看明细 → PENDING 确认：逐行议价 + 沉淀专属价 + 查全号联系买家。
import { computed, reactive, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { ConfirmInquiryRequest, CustomerPriceVo, Inquiry, Sku } from '@cangchu/api-types'
import { inquiryApi } from '../../../api/inquiry'
import { skuApi } from '../../../api/sku'
import { piiApi } from '../../../api/pii'
import { fmtDateTime, fmtMoney } from '../../../utils/format'
import { hasWaScope, readAuth, readWork, type WaWork } from '../../../utils/session'

type TabKey = 'pending' | 'all' | 'history'

const STATUS_META: Record<string, { text: string; cls: string }> = {
  PENDING: { text: '待确认', cls: 'chip--warn' },
  CONFIRMED: { text: '已确认', cls: 'chip--ok' },
  COMPLETED: { text: '已完成', cls: 'chip--done' },
  VOIDED: { text: '已作废', cls: 'chip--void' },
}

const work = ref<WaWork | null>(null)
const list = ref<Inquiry[]>([])
const loading = ref(true)
const tab = ref<TabKey>('pending')

const filtered = computed(() => {
  if (tab.value === 'pending') return list.value.filter((i) => i.status === 'PENDING')
  if (tab.value === 'history') return list.value.filter((i) => i.status !== 'PENDING')
  return list.value
})

/** skuId → 品名（商品行展示用，移动端拉「我的商品」建映射，失败则显示 skuId） */
const skuMap = reactive<Record<string, Sku>>({})

const pendingCount = computed(() => list.value.filter((i) => i.status === 'PENDING').length)

// ===== 详情 / 确认 sheet =====
const active = ref<Inquiry | null>(null)
const showConfirm = ref(false)
const confirming = ref(false)
const dealPrices = reactive<Record<string, number>>({})
const settleChecked = ref(false)
const existingMap = reactive<Record<string, number>>({})

function openDetail(inq: Inquiry): void {
  active.value = inq
  showConfirm.value = true
  // 重置议价草稿 + 拉取现有专属价（覆盖提示）
  for (const k of Object.keys(dealPrices)) delete dealPrices[k]
  for (const it of inq.items) dealPrices[String(it.id)] = Number(it.unitPriceSnapshot ?? 0)
  settleChecked.value = false
  void fetchExisting(inq)
}

async function fetchExisting(inq: Inquiry): Promise<void> {
  for (const k of Object.keys(existingMap)) delete existingMap[k]
  if (!work.value?.wholesalerId) return
  try {
    const rows: CustomerPriceVo[] = await inquiryApi.listCustomerPrices(work.value.wholesalerId)
    for (const cp of rows) {
      if (cp.rtPhone === inq.rtPhone && cp.status === 'ACTIVE') {
        existingMap[String(cp.skuId)] = Number(cp.unitPrice)
      }
    }
  } catch {
    /* 拉取失败降级：不显示「将覆盖」精确文案 */
  }
}

function itemLabel(it: Inquiry['items'][number]): string {
  const sku = skuMap[String(it.skuId)]
  if (sku) return sku.spec ? `${sku.name} ${sku.spec}` : sku.name
  return String(it.skuId)
}

function totalOf(inq: Inquiry): { count: number; amount: number } {
  const count = inq.items.reduce((a, it) => a + it.qty, 0)
  const amount = inq.items.reduce((a, it) => a + it.qty * Number(it.unitPriceSnapshot ?? 0), 0)
  return { count, amount }
}

/** 勾选沉淀后将被沉淀的行数（成交价≠公开价快照） */
const willSettleCount = computed(() => {
  if (!settleChecked.value || !active.value) return 0
  return active.value.items.filter((it) => Math.abs(Number(dealPrices[String(it.id)] ?? 0) - Number(it.unitPriceSnapshot)) > 0.001).length
})

function onDealPriceInput(it: Inquiry['items'][number], e: any): void {
  const n = Number(e?.detail?.value ?? '')
  if (Number.isNaN(n)) return
  dealPrices[String(it.id)] = n
}

async function doConfirm(): Promise<void> {
  const inq = active.value
  if (!inq) return
  for (const it of inq.items) {
    const dp = dealPrices[String(it.id)]
    if (dp == null || !Number.isFinite(dp) || dp <= 0) {
      uni.showToast({ title: '成交价须大于 0，请检查', icon: 'none' })
      return
    }
  }
  if (confirming.value) return
  confirming.value = true
  try {
    const payload: ConfirmInquiryRequest = {
      items: inq.items.map((it) => ({
        inquiryItemId: String(it.id),
        dealPrice: Number(dealPrices[String(it.id)]),
      })),
      settleAsCustomerPrice: settleChecked.value,
    }
    await inquiryApi.confirm(inq.id, payload)
    uni.showToast({ title: '已确认并结算，询价单已转出库', icon: 'success' })
    closeDetail()
    await load()
  } catch {
    /* request 已 toast（如 WE 未授权 42004） */
  } finally {
    confirming.value = false
  }
}

function closeDetail(): void {
  active.value = null
  showConfirm.value = false
}

function revealPhoneOf(inq: Inquiry): void {
  if (!inq.id) return
  piiApi
    .revealPhone('INQUIRY', String(inq.id))
    .then(({ phone }) => {
      uni.showModal({
        title: '买家完整电话',
        content: phone,
        confirmText: '复制',
        cancelText: '关闭',
        success: (r) => {
          if (r.confirm) uni.setClipboardData({ data: phone })
        },
      })
    })
    .catch(() => {
      /* 越权/对象不存在 → request 已 toast */
    })
}

async function load(): Promise<void> {
  if (!work.value) return
  loading.value = true
  try {
    const rows = await inquiryApi.list()
    list.value = rows
    // 拉取品名映射（静默失败则回退 skuId）
    if (work.value.wholesalerId) {
      try {
        const skus = await skuApi.listMine(work.value.wholesalerId)
        for (const s of skus) skuMap[String(s.id)] = s
      } catch {
        /* 静默 */
      }
    }
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

onLoad(() => {
  if (!hasWaScope()) goLogin()
})

onShow(() => {
  work.value = readWork()
  if (!hasWaScope() || !work.value) return
  void load()
})

onPullDownRefresh(async () => {
  work.value = readWork()
  await load()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 状态过滤 -->
    <view class="filters">
      <view
        v-for="f in [
          { key: 'pending', label: '待确认' },
          { key: 'all', label: '全部' },
          { key: 'history', label: '历史' },
        ] as Array<{ key: TabKey; label: string }>"
        :key="f.key"
        class="fchip"
        :class="{ 'fchip--on': tab === f.key }"
        @click="tab = f.key"
      >
        {{ f.label }}
        <text v-if="f.key === 'pending' && pendingCount > 0" class="fchip__n">{{ pendingCount }}</text>
      </view>
    </view>

    <!-- 列表 -->
    <view v-if="loading && !list.length" class="hint"><text class="hint__t">加载中…</text></view>
    <view v-else-if="!filtered.length" class="hint">
      <text class="hint__t">{{ tab === 'pending' ? '没有待确认的询价单，买家提交后会实时出现' : '暂无询价记录' }}</text>
    </view>

    <view v-else class="list">
      <view
        v-for="inq in filtered"
        :key="String(inq.id)"
        class="card"
        @click="inq.status === 'PENDING' ? openDetail(inq) : openDetail(inq)"
      >
        <view class="card__head">
          <text class="card__doc">{{ inq.docNo }}</text>
          <text class="chip" :class="STATUS_META[String(inq.status)]?.cls ?? 'chip--done'">
            {{ STATUS_META[String(inq.status)]?.text ?? String(inq.status) }}
          </text>
        </view>
        <view class="card__sub">
          <text>买家 {{ inq.rtPhone || '—' }}</text>
          <text class="card__time">{{ fmtDateTime(inq.createdAt) }}</text>
        </view>

        <view class="items">
          <view v-for="(it, idx) in inq.items.slice(0, 2)" :key="String(it.id)" class="row">
            <text class="row__name">{{ itemLabel(it) }}</text>
            <text class="row__qty">×{{ it.qty }}</text>
            <text class="row__price">{{ fmtMoney(Number(it.unitPriceSnapshot ?? 0)) }}</text>
            <text v-if="idx === 0 && inq.items.length > 2" class="row__more">+{{ inq.items.length - 2 }} 项</text>
          </view>
        </view>

        <view class="card__foot">
          <text class="card__total">共 {{ totalOf(inq).count }} 件 · {{ fmtMoney(totalOf(inq).amount) }}</text>
          <view v-if="inq.status === 'PENDING'" class="ops">
            <view class="ops__btn ops__btn--ghost" @click.stop="revealPhoneOf(inq)">查全号</view>
            <view class="ops__btn ops__btn--main" @click.stop="openDetail(inq)">确认处理</view>
          </view>
          <text v-else-if="inq.status === 'CONFIRMED'" class="card__tip">已确认并结算，自动转出库</text>
          <text v-else class="card__tip">{{ STATUS_META[String(inq.status)]?.text }}</text>
        </view>
      </view>
    </view>

    <!-- 详情 / 确认 sheet -->
    <view v-if="showConfirm && active" class="mask" @click="closeDetail">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">询价单 · {{ active.docNo }}</text>
          <text class="sheet__close" @click="closeDetail">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view class="sum">
            <text class="sum__phone">买家 {{ active.rtPhone || '—' }}</text>
            <view class="sum__row">
              <text class="sum__btn" @click="revealPhoneOf(active)">查全号联系买家</text>
              <text class="sum__total">{{ totalOf(active).count }} 件</text>
            </view>
          </view>

          <view v-if="active.status === 'PENDING'" class="settle-hint">
            确认时可为买家报价；成交价 ≠ 公开价且勾选沉淀时，将写入该买家的专属价
          </view>

          <!-- 明细 + 议价行 -->
          <view v-for="it in active.items" :key="String(it.id)" class="line">
            <view class="line__main">
              <text class="line__name">{{ itemLabel(it) }}</text>
              <view class="line__meta">
                <text>数量 {{ it.qty }}</text>
                <text class="line__snapshot">公开价 {{ fmtMoney(Number(it.unitPriceSnapshot ?? 0)) }}</text>
              </view>
              <view v-if="active.status === 'PENDING'" class="line__settle">
                <text class="line__label">成交价</text>
                <input
                  class="line__input"
                  type="digit"
                  :value="String(dealPrices[String(it.id)] ?? it.unitPriceSnapshot ?? '')"
                  @input="onDealPriceInput(it, $event)"
                />
                <text class="line__unit">元</text>
                <text v-if="existingMap[String(it.skuId)] != null" class="line__cover">
                  将覆盖现有专属价 {{ fmtMoney(existingMap[String(it.skuId)]) }}
                </text>
              </view>
              <text v-else class="line__deal">成交价 {{ fmtMoney(Number(dealPrices[String(it.id)] ?? 0)) }}</text>
            </view>
          </view>

          <view v-if="active.status === 'PENDING'" class="settle">
            <view class="settle__row" @click="settleChecked = !settleChecked">
              <view class="check" :class="{ 'check--on': settleChecked }">{{ settleChecked ? '✓' : '' }}</view>
              <view class="settle__main">
                <text class="settle__label">沉淀为客户专属价</text>
                <text class="settle__desc">仅成交价 ≠ 公开价的行生效，买家下次自动见专属价</text>
              </view>
            </view>
            <view v-if="willSettleCount > 0" class="settle__count">本次将沉淀 {{ willSettleCount }} 行专属价</view>
            <button class="btn-primary sheet__btn" :loading="confirming" @click="doConfirm">确认并结算</button>
            <view class="sheet__tip">确认后自动转出库单，商户号与报价短信同步通知买家</view>
          </view>

          <view v-else class="done-box">
            <text class="done-box__t">该单已由批发商处理，无需再操作</text>
            <view v-if="active.status === 'CONFIRMED'" class="done-box__hint">已确认并结算 → 自动转出库单</view>
            <view v-else-if="active.status === 'COMPLETED'" class="done-box__hint">已完成</view>
            <view v-else-if="active.status === 'VOIDED'" class="done-box__hint">本单已作废</view>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 60rpx;
  box-sizing: border-box;
}

.filters {
  display: flex;
  gap: 14rpx;
  margin-bottom: 20rpx;
}

.fchip {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  color: $cc-fg-2;
  font-size: 26rpx;

  &--on {
    background: $cc-accent;
    border-color: $cc-accent;
    color: #fff;
    font-weight: 600;
  }

  &__n {
    font-size: 20rpx;
    background: rgba(255, 255, 255, 0.3);
    padding: 0 10rpx;
    border-radius: 999rpx;
  }
}

.hint {
  padding: 140rpx 40rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-4;
    line-height: 1.7;
  }
}

.card {
  margin-bottom: 20rpx;
  padding: 26rpx 26rpx 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__doc {
    font-size: 29rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sub {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 8rpx;
    font-size: 23rpx;
    color: $cc-fg-3;
  }

  &__time {
    color: $cc-fg-4;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 18rpx;
  }

  &__total {
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }

  &__tip {
    font-size: 22rpx;
    color: $cc-fg-4;
  }
}

.chip {
  font-size: 22rpx;
  padding: 4rpx 16rpx;
  border-radius: 8rpx;

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

.items {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;
}

.row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 6rpx 0;

  &__name {
    flex: 1;
    min-width: 0;
    font-size: 25rpx;
    color: $cc-fg-2;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__qty {
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__price {
    font-size: 24rpx;
    color: $cc-fg-1;
    font-weight: 600;
  }

  &__more {
    font-size: 20rpx;
    color: $cc-fg-4;
  }
}

.ops {
  display: flex;
  gap: 14rpx;

  &__btn {
    height: 60rpx;
    line-height: 60rpx;
    padding: 0 26rpx;
    border-radius: 999rpx;
    font-size: 24rpx;

    &--ghost {
      background: $cc-bg-3;
      color: $cc-fg-2;
    }

    &--main {
      background: $cc-accent;
      color: #fff;
      font-weight: 600;
    }
  }
}

/* sheet */
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
  height: 84vh;
  display: flex;
  flex-direction: column;
  border-radius: 28rpx 28rpx 0 0;
  background: $cc-bg-1;
  box-sizing: border-box;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 28rpx 32rpx 0;
  }

  &__title {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__close {
    padding: 8rpx 16rpx;
    font-size: 32rpx;
    color: $cc-fg-4;
  }

  &__body {
    flex: 1;
    min-height: 0;
    padding: 20rpx 32rpx 40rpx;
    box-sizing: border-box;
  }

  &__btn {
    margin-top: 24rpx;
    height: 92rpx;
    font-size: 30rpx;
    font-weight: 600;
  }

  &__tip {
    display: block;
    margin-top: 14rpx;
    font-size: 21rpx;
    color: $cc-fg-4;
    text-align: center;
  }
}

.sum {
  padding: 18rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;
  display: flex;
  flex-direction: column;
  gap: 10rpx;

  &__phone {
    font-size: 25rpx;
    color: $cc-fg-2;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__btn {
    font-size: 24rpx;
    color: $cc-accent;
    text-decoration: underline;
  }

  &__total {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.settle-hint {
  margin: 18rpx 0 4rpx;
  font-size: 22rpx;
  color: $cc-warning;
  line-height: 1.6;
}

.line {
  padding: 22rpx 0;
  border-bottom: 2rpx solid $cc-bg-3;

  &__name {
    font-size: 29rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__meta {
    display: flex;
    gap: 24rpx;
    margin-top: 8rpx;
    font-size: 23rpx;
    color: $cc-fg-3;
  }

  &__settle {
    display: flex;
    align-items: center;
    gap: 14rpx;
    margin-top: 14rpx;
  }

  &__label {
    font-size: 24rpx;
    color: $cc-fg-2;
  }

  &__input {
    width: 160rpx;
    height: 64rpx;
    padding: 0 12rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 10rpx;
    font-size: 30rpx;
    color: $cc-danger;
    font-weight: 600;
    text-align: center;
  }

  &__unit {
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__cover {
    font-size: 21rpx;
    color: $cc-warning;
    background: $cc-warning-bg;
    padding: 2rpx 10rpx;
    border-radius: 6rpx;
  }
}

.settle {
  margin-top: 10rpx;

  &__row {
    display: flex;
    gap: 16rpx;
    align-items: flex-start;
    padding: 20rpx;
    border-radius: 12rpx;
    background: $cc-bg-2;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__label {
    font-size: 27rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__desc {
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__count {
    margin-top: 14rpx;
    font-size: 22rpx;
    color: $cc-warning;
    background: $cc-warning-bg;
    padding: 6rpx 16rpx;
    border-radius: 8rpx;
    display: inline-block;
  }
}

.check {
  width: 40rpx;
  height: 40rpx;
  margin-top: 4rpx;
  border-radius: 50%;
  border: 2rpx solid $cc-border-2;
  text-align: center;
  line-height: 36rpx;
  font-size: 26rpx;
  color: #fff;
  flex-shrink: 0;

  &--on {
    background: $cc-accent;
    border-color: $cc-accent;
  }
}

.done-box {
  margin-top: 30rpx;
  text-align: center;

  &__t {
    font-size: 26rpx;
    color: $cc-fg-2;
  }

  &__hint {
    margin-top: 8rpx;
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}
</style>
