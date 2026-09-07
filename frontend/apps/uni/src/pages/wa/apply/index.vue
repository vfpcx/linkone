<script setup lang="ts">
// WA/WE 我的入库申请（F7-4 · source=WA_SUBMIT 正向申请链移动化）：
// 与 admin wa/Inbound.vue 我的申请视图同口径（六态过滤 + 全局口径横幅 + 同批 N 单 tag）。
// 待受理 24h 内仓库受理 → 受理后 24h 内登记 → 登记后通知确认 → 已入库（CONFIRMED）起计费；
// 仅 SUBMITTED 可撤回（理由必填 ≤100）；REJECTED 可一键复制重建（带批次/托盘/备注回填）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import type { InboundRequest, Sku } from '@cangchu/api-types'
import { waInboundApi } from '../../../api/waInbound'
import { skuApi } from '../../../api/sku'
import { fmtDateTime } from '../../../utils/format'
import { hasWaScope, readWork, type WaWork } from '../../../utils/session'

type TabKey = 'ALL' | 'SUBMITTED' | 'ACCEPTED' | 'CONFIRMED' | 'REJECTED' | 'WITHDRAWN'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'SUBMITTED', label: '待受理' },
  { key: 'ACCEPTED', label: '已受理' },
  { key: 'CONFIRMED', label: '已入库' },
  { key: 'REJECTED', label: '已驳回' },
  { key: 'WITHDRAWN', label: '已撤回' },
]

const STATUS_META: Record<string, { text: string; cls: string }> = {
  SUBMITTED: { text: '待受理', cls: 'chip--warn' },
  ACCEPTED: { text: '已受理', cls: 'chip--ok' },
  CONFIRMED: { text: '已入库', cls: 'chip--ok' },
  REJECTED: { text: '已驳回', cls: 'chip--err' },
  WITHDRAWN: { text: '已撤回', cls: 'chip--void' },
}
const statusMeta = (s: string): { text: string; cls: string } => STATUS_META[s] ?? { text: s, cls: 'chip--void' }

const REJECT_LABEL: Record<string, string> = {
  QTY: '数量不符',
  QUALITY: '质量问题',
  BATCH: '批次不符',
  OTHER: '其他',
}
const rejectLabel = (c: string | null | undefined): string => (c ? (REJECT_LABEL[c] ?? c) : '—')

const work = ref<WaWork | null>(null)
const tab = ref<TabKey>('ALL')
const rows = ref<InboundRequest[]>([])
const loading = ref(true)
const page = ref(1)
const finished = ref(false)

/** skuId → 品名（移动端拉「我的商品」建映射，失败则回退 skuId） */
const skuMap = new Map<string, Sku>()
const skuLabel = (id: unknown): string => {
  const s = skuMap.get(String(id))
  return s ? (s.spec ? `${s.name}（${s.spec}）` : s.name) : String(id)
}

/** 当前加载行内「同批 N 单」标识（N≥2；与 admin 同口径，跨页批次不跨页统计） */
function batchTag(row: InboundRequest): string | null {
  if (!row.batchSubmitId) return null
  let n = 0
  for (const r of rows.value) {
    if (r.batchSubmitId === row.batchSubmitId) n += 1
  }
  return n >= 2 ? `同批 ${n} 单` : null
}

/** 卡片数量主文：CONFIRMED 已实登；其余申请件数 */
const qtyText = (row: InboundRequest): string =>
  row.status === 'CONFIRMED' ? `实登 ${row.qty} 件` : `${row.requestedQty ?? row.qty} 件`

const diffText = (row: InboundRequest): string | null => {
  if (row.status !== 'CONFIRMED' || row.requestedQty == null) return null
  const d = row.qty - row.requestedQty
  if (d === 0) return null
  return `实登与申请差 ${d > 0 ? '+' : ''}${d} 件`
}

async function load(reset: boolean): Promise<void> {
  if (!work.value) return
  if (reset) {
    page.value = 1
    finished.value = false
    rows.value = []
  }
  loading.value = true
  try {
    const data = await waInboundApi.list({
      source: 'WA_SUBMIT',
      status: tab.value === 'ALL' ? undefined : tab.value,
      page: page.value,
      size: 20,
    })
    const rec = data.records ?? []
    rows.value = page.value === 1 ? rec : [...rows.value, ...rec]
    finished.value = rec.length < 20 || rows.value.length >= Number(data.total || 0)
    if (work.value.wholesalerId) {
      try {
        const skus = await skuApi.listMine(work.value.wholesalerId)
        for (const s of skus) skuMap.set(String(s.id), s)
      } catch {
        /* 品名映射静默降级为 skuId */
      }
    }
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function switchTab(t: TabKey): void {
  if (tab.value === t) return
  tab.value = t
  void load(true)
}

// ===== 详情 sheet =====
const detailOpen = ref(false)
const active = ref<InboundRequest | null>(null)
function openDetail(row: InboundRequest): void {
  active.value = row
  detailOpen.value = true
}
function closeDetail(): void {
  detailOpen.value = false
}
function previewImage(urls: string[], i: number): void {
  if (!urls.length) return
  uni.previewImage({ urls, current: urls[i] ?? urls[0] })
}

const timeText = (v: string | null | undefined): string => (v ? fmtDateTime(v) : '—')

// ===== 撤回（仅 SUBMITTED） =====
const withdrawOpen = ref(false)
const withdrawReason = ref('')
const submitting = ref(false)
function openWithdraw(row: InboundRequest): void {
  active.value = row
  withdrawReason.value = ''
  withdrawOpen.value = true
  detailOpen.value = false
}
function closeWithdraw(): void {
  withdrawOpen.value = false
}
async function doWithdraw(): Promise<void> {
  const row = active.value
  if (!row || submitting.value) return
  const reason = withdrawReason.value.trim()
  if (!reason) {
    uni.showToast({ title: '请填写撤回理由', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await waInboundApi.withdraw(row.id, { reason })
    uni.showToast({ title: '已撤回该申请', icon: 'success' })
    withdrawOpen.value = false
    detailOpen.value = false
    await load(true)
  } catch {
    // 50350 已 toast → 刷新回显实际状态
    await load(true)
  } finally {
    submitting.value = false
  }
}

// ===== 复制重建（REJECTED → 带参跳新建） =====
function rebuild(row: InboundRequest): void {
  const q: Record<string, string> = {
    rebuild: '1',
    skuId: String(row.skuId),
    qty: String(row.requestedQty ?? row.qty),
  }
  if (row.palletQty != null && row.palletQty > 0) q.palletQty = String(row.palletQty)
  if (row.remark) q.remark = row.remark
  if (row.batchNo) q.batchNo = row.batchNo
  if (row.productionDate) q.productionDate = row.productionDate
  if (row.expiryDate) q.expiryDate = row.expiryDate
  const query = Object.entries(q)
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  uni.navigateTo({ url: `/pages/wa/apply/create?${query}` })
}

function goCreate(): void {
  uni.navigateTo({ url: '/pages/wa/apply/create' })
}

const pendingCount = computed(() => rows.value.filter((r) => r.status === 'SUBMITTED').length)

// ===== 生命周期 =====
function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}
onLoad(() => {
  if (!hasWaScope()) goLogin()
})
onShow(() => {
  work.value = readWork()
  if (!hasWaScope() || !work.value) return
  void load(true)
})
onPullDownRefresh(async () => {
  work.value = readWork()
  await load(true)
  uni.stopPullDownRefresh()
})
onReachBottom(() => {
  if (!finished.value && !loading.value) {
    page.value += 1
    void load(false)
  }
})
</script>

<template>
  <view class="page">
    <!-- 全局口径横幅 -->
    <view class="banner">提交与受理不影响库存与计费，登记入库并确认后才开始计费；待受理申请可撤回，已驳回申请可一键复制重建。</view>

    <!-- 状态过滤 -->
    <scroll-view scroll-x class="filters" show-scrollbar="false">
      <view
        v-for="f in TABS"
        :key="f.key"
        class="fchip"
        :class="{ 'fchip--on': tab === f.key }"
        @click="switchTab(f.key)"
      >
        {{ f.label }}
        <text v-if="f.key === 'SUBMITTED' && pendingCount > 0" class="fchip__n">{{ pendingCount }}</text>
      </view>
    </scroll-view>

    <!-- 列表 -->
    <view v-if="loading && !rows.length" class="hint"><text class="hint__t">加载中…</text></view>
    <view v-else-if="!rows.length" class="hint">
      <text class="hint__t">暂无{{ tab === 'ALL' ? '' : TABS.find((t) => t.key === tab)?.label }}的入库申请{{ tab === 'ALL' ? '，点下方按钮提交' : '' }}</text>
    </view>

    <view v-else class="list">
      <view v-for="row in rows" :key="String(row.id)" class="card" @click="openDetail(row)">
        <view class="card__head">
          <text class="card__doc">{{ row.docNo }}</text>
          <view class="card__badges">
            <text v-if="batchTag(row)" class="chip chip--done">{{ batchTag(row) }}</text>
            <text class="chip" :class="statusMeta(row.status).cls">{{ statusMeta(row.status).text }}</text>
          </view>
        </view>
        <view class="card__main">
          <text class="card__name">{{ skuLabel(row.skuId) }}</text>
          <text class="card__qty">{{ qtyText(row) }}</text>
        </view>
        <view v-if="diffText(row)" class="card__diff">{{ diffText(row) }}</view>
        <view v-if="row.batchNo" class="card__meta">批次 {{ row.batchNo }}<template v-if="row.expiryDate"> · 效期至 {{ row.expiryDate }}</template></view>
        <view v-if="row.palletQty != null && row.palletQty > 0" class="card__meta">预计托盘 {{ row.palletQty }}</view>
        <view v-if="row.remark" class="card__meta card__meta--remark">{{ row.remark }}</view>
        <view v-if="row.status === 'REJECTED'" class="card__reject">驳回原因：{{ rejectLabel(row.rejectReason) }}<template v-if="row.rejectRemark"> · {{ row.rejectRemark }}</template></view>
        <view class="card__sub">
          <text>{{ timeText(row.createdAt) }} 提交</text>
          <text v-if="row.status === 'CONFIRMED'">登记于 {{ timeText(row.registeredAt) }}</text>
        </view>
        <view v-if="row.status === 'SUBMITTED' || row.status === 'REJECTED'" class="card__foot">
          <view class="ops">
            <view v-if="row.status === 'SUBMITTED'" class="ops__btn ops__btn--ghost" @click.stop="openWithdraw(row)">撤回</view>
            <view v-if="row.status === 'REJECTED'" class="ops__btn ops__btn--ghost" @click.stop="rebuild(row)">复制重建</view>
            <view class="ops__btn ops__btn--main" @click.stop="openDetail(row)">详情</view>
          </view>
        </view>
      </view>
      <view v-if="loading" class="more">加载中…</view>
      <view v-else-if="finished" class="more">没有更多了</view>
    </view>

    <!-- 悬浮新建 -->
    <view class="fab" @click="goCreate">
      <text class="fab__t">＋ 新建入库申请</text>
    </view>

    <!-- 详情 sheet -->
    <view v-if="detailOpen && active" class="mask" @click="closeDetail">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">申请单 {{ active.docNo }}</text>
          <text class="sheet__close" @click="closeDetail">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view class="statusline">
            <text v-if="batchTag(active)" class="chip chip--done">{{ batchTag(active) }}</text>
            <text class="chip" :class="statusMeta(active.status).cls">{{ statusMeta(active.status).text }}</text>
          </view>

          <view class="info">
            <view class="info__row">
              <text class="info__k">商品</text>
              <text class="info__v">{{ skuLabel(active.skuId) }}</text>
            </view>
            <view class="info__row">
              <text class="info__k">申请件数</text>
              <text class="info__v">{{ active.requestedQty ?? active.qty }} 件</text>
            </view>
            <view v-if="active.status === 'CONFIRMED'" class="info__row">
              <text class="info__k">实登件数</text>
              <text class="info__v">{{ active.qty }} 件</text>
            </view>
            <view v-if="active.palletQty != null && active.palletQty > 0" class="info__row">
              <text class="info__k">预计托盘</text>
              <text class="info__v">{{ active.palletQty }}</text>
            </view>
            <template v-if="active.batchNo">
              <view class="info__row">
                <text class="info__k">批次号</text>
                <text class="info__v">{{ active.batchNo }}</text>
              </view>
              <view v-if="active.productionDate" class="info__row">
                <text class="info__k">生产日期</text>
                <text class="info__v">{{ active.productionDate }}</text>
              </view>
              <view v-if="active.expiryDate" class="info__row">
                <text class="info__k">效期至</text>
                <text class="info__v">{{ active.expiryDate }}</text>
              </view>
            </template>
            <view v-if="active.remark" class="info__row">
              <text class="info__k">申请备注</text>
              <text class="info__v">{{ active.remark }}</text>
            </view>
            <view v-if="active.location" class="info__row">
              <text class="info__k">登记货位</text>
              <text class="info__v">{{ active.location }}</text>
            </view>
            <view class="info__row">
              <text class="info__k">提交时间</text>
              <text class="info__v">{{ timeText(active.createdAt) }}</text>
            </view>
            <view v-if="active.registeredAt" class="info__row">
              <text class="info__k">登记时间</text>
              <text class="info__v">{{ timeText(active.registeredAt) }}</text>
            </view>
          </view>

          <!-- 驳回信息 -->
          <view v-if="active.status === 'REJECTED'" class="reject-box">
            <view class="reject-box__t">
              <text class="chip chip--err">驳回 · {{ rejectLabel(active.rejectReason) }}</text>
            </view>
            <text v-if="active.rejectRemark" class="reject-box__d">{{ active.rejectRemark }}</text>
            <view v-if="(active.rejectAttachments?.length ?? 0) > 0" class="photos">
              <view class="sec-t">驳回举证照片</view>
              <view class="photos__row">
                <image
                  v-for="(u, i) in active.rejectAttachments ?? []"
                  :key="u"
                  class="photos__img"
                  :src="u"
                  mode="aspectFill"
                  @click="previewImage(active.rejectAttachments ?? [], i)"
                />
              </view>
            </view>
            <view class="reject-box__hint">已驳回的申请可点下方「复制重建」修改后重新提交</view>
          </view>

          <!-- 撤回理由 -->
          <view v-if="active.status === 'WITHDRAWN'" class="info">
            <view class="info__row">
              <text class="info__k">撤回理由</text>
              <text class="info__v">{{ active.withdrawReason || '—' }}</text>
            </view>
          </view>

          <!-- 登记照片 -->
          <template v-if="active.status === 'CONFIRMED'">
            <view v-if="(active.attachments?.length ?? 0) > 0" class="photos">
              <view class="sec-t">登记照片</view>
              <view class="photos__row">
                <image
                  v-for="(u, i) in active.attachments ?? []"
                  :key="u"
                  class="photos__img"
                  :src="u"
                  mode="aspectFill"
                  @click="previewImage(active.attachments ?? [], i)"
                />
              </view>
            </view>
            <view class="done-box"><text class="done-box__t">已登记入库并开始计费</text></view>
          </template>

          <!-- 操作区 -->
          <view v-if="active.status === 'SUBMITTED'" class="ops-bar">
            <view class="ops-bar__ghost" @click="openWithdraw(active)">撤回申请</view>
          </view>
          <view v-if="active.status === 'SUBMITTED'" class="sheet__tip2">待受理申请可撤回；仓库受理后如需取消请联系仓库</view>
          <view v-if="active.status === 'REJECTED'" class="ops-bar">
            <button class="ops-bar__main" @click="rebuild(active)">复制重建</button>
          </view>
          <view v-if="active.status === 'ACCEPTED'" class="done-box">
            <text class="done-box__t">仓库已受理，将在 24h 内登记入库，登记后通知你确认</text>
          </view>
          <view v-if="active.status === 'WITHDRAWN'" class="done-box">
            <text class="done-box__t">已撤回，不影响库存与计费</text>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 撤回 sheet -->
    <view v-if="withdrawOpen && active" class="mask" @click="closeWithdraw">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">撤回申请 · {{ active.docNo }}</text>
          <text class="sheet__close" @click="closeWithdraw">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view class="dispute__tip">撤回不影响库存与计费；如仍需入库请重新提交申请。</view>
          <view class="sec">撤回理由</view>
          <textarea
            v-model="withdrawReason"
            class="ta"
            :maxlength="100"
            placeholder="请填写撤回理由（必填，≤100 字）"
            placeholder-class="ph"
          />
          <view class="ops-bar">
            <button class="ops-bar__main ops-bar__main--danger" :loading="submitting" @click="doWithdraw">确认撤回</button>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 160rpx;
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

.filters {
  white-space: nowrap;
  margin-bottom: 20rpx;
}

.fchip {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  padding: 12rpx 28rpx;
  margin-right: 12rpx;
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

.more {
  padding: 24rpx 0 8rpx;
  text-align: center;
  font-size: 22rpx;
  color: $cc-fg-4;
}

.card {
  margin-bottom: 20rpx;
  padding: 24rpx 26rpx 22rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12rpx;
  }

  &__badges {
    display: flex;
    gap: 8rpx;
    flex-shrink: 0;
  }

  &__doc {
    font-size: 27rpx;
    font-weight: 600;
    color: $cc-fg-1;
    word-break: break-all;
  }

  &__main {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-top: 16rpx;
    padding: 14rpx 16rpx;
    border-radius: 12rpx;
    background: $cc-bg-2;
    gap: 16rpx;
  }

  &__name {
    flex: 1;
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__qty {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-accent;
  }

  &__diff {
    margin-top: 10rpx;
    font-size: 21rpx;
    font-weight: 600;
    color: $cc-warning;
  }

  &__meta {
    margin-top: 10rpx;
    font-size: 22rpx;
    color: $cc-fg-3;

    &--remark {
      color: $cc-fg-2;
      line-height: 1.5;
    }
  }

  &__reject {
    margin-top: 10rpx;
    font-size: 22rpx;
    color: $cc-danger;
    line-height: 1.5;
  }

  &__sub {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 12rpx;
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__foot {
    display: flex;
    justify-content: flex-end;
    margin-top: 16rpx;
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

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--void {
    background: $cc-bg-3;
    color: $cc-fg-4;
  }
}

.ops {
  display: flex;
  gap: 16rpx;

  &__btn {
    padding: 12rpx 36rpx;
    border-radius: 999rpx;
    font-size: 26rpx;
    font-weight: 600;

    &--ghost {
      background: $cc-bg-2;
      color: $cc-fg-2;
    }

    &--main {
      background: $cc-accent;
      color: #fff;
    }
  }
}

.fab {
  position: fixed;
  left: 50%;
  bottom: calc(40rpx + env(safe-area-inset-bottom));
  transform: translateX(-50%);
  z-index: 10;
  padding: 22rpx 60rpx;
  border-radius: 999rpx;
  background: $cc-accent;
  box-shadow: 0 8rpx 24rpx rgba(79, 70, 229, 0.35);

  &__t {
    font-size: 30rpx;
    font-weight: 700;
    color: #fff;
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
  max-height: 88vh;
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
    max-height: 78vh;
  }

  &__tip2 {
    margin-top: 16rpx;
    text-align: center;
    font-size: 21rpx;
    color: $cc-fg-4;
  }
}

.statusline {
  display: flex;
  align-items: center;
  gap: 14rpx;
  flex-wrap: wrap;
}

.info {
  margin-top: 20rpx;
  padding: 6rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;

  &__row {
    display: flex;
    justify-content: space-between;
    gap: 20rpx;
    padding: 14rpx 0;
    border-bottom: 1rpx solid $cc-border-1;

    &:last-child {
      border-bottom: none;
    }
  }

  &__k {
    flex-shrink: 0;
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__v {
    text-align: right;
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-fg-1;
    word-break: break-all;
  }
}

.sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 26rpx 0 12rpx;
  font-size: 24rpx;
  font-weight: 700;
  color: $cc-fg-2;

  &__cnt {
    font-weight: 400;
    color: $cc-fg-4;
  }
}

.sec-t {
  margin: 22rpx 0 12rpx;
  font-size: 24rpx;
  font-weight: 700;
  color: $cc-fg-2;
}

.reject-box {
  margin-top: 20rpx;
  padding: 20rpx;
  border-radius: 12rpx;
  background: $cc-danger-bg;

  &__t {
    display: flex;
    align-items: center;
  }

  &__d {
    display: block;
    margin-top: 14rpx;
    font-size: 24rpx;
    color: $cc-fg-2;
    line-height: 1.6;
  }

  &__hint {
    margin-top: 14rpx;
    font-size: 22rpx;
    color: $cc-danger;
  }
}

.photos {
  margin-top: 16rpx;

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: 16rpx;
  }

  &__img {
    width: 150rpx;
    height: 150rpx;
    border-radius: 12rpx;
    background: $cc-bg-3;
  }
}

.dispute__tip {
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-danger-bg;
  font-size: 23rpx;
  color: $cc-danger;
  line-height: 1.6;
}

.ta {
  width: 100%;
  height: 160rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  background: $cc-bg-2;
  font-size: 24rpx;
  color: $cc-fg-1;
  box-sizing: border-box;
}

.ph {
  color: $cc-fg-4;
}

.ops-bar {
  display: flex;
  gap: 16rpx;
  margin-top: 30rpx;

  &__ghost {
    flex: 1;
    height: 84rpx;
    line-height: 84rpx;
    text-align: center;
    border-radius: 999rpx;
    background: $cc-bg-2;
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-danger;
  }

  &__main {
    flex: 1;
    height: 84rpx;
    line-height: 84rpx;
    border-radius: 999rpx;
    background: $cc-accent;
    color: #fff;
    font-size: 28rpx;
    font-weight: 600;
    border: none;
    margin: 0;

    &::after {
      border: none;
    }

    &--danger {
      background: $cc-danger;
    }
  }
}

.done-box {
  margin-top: 26rpx;
  padding: 30rpx 24rpx;
  border-radius: 14rpx;
  background: $cc-bg-2;
  text-align: center;

  &__t {
    font-size: 24rpx;
    color: $cc-fg-3;
    line-height: 1.7;
  }
}
</style>
