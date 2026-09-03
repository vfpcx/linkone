<script setup lang="ts">
// WK 出库作业（F5-W1）：分页任务队列 + 打印/登记出库/撤回处理/代建入口。
// 状态机：PENDING_ACCEPT 待受理 → PRINTED 已打印 → COMPLETED 已出库；
// R4：WA 已申请撤回（PRINTED 态 withdrawRequested=1）需 WK 二次确认（同意=CANCELLED+回补 / 拒绝=继续履约）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import type { OutboundRequest, SnowflakeId } from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import {
  OUTBOUND_SOURCE_LABELS,
  OUTBOUND_STATUS_LABELS,
  fmtDateTime,
  statusTone,
} from '../../../utils/warehouse'

const SEGMENTS = [
  { v: 'PENDING_ACCEPT', label: '待受理' },
  { v: 'PRINTED', label: '待登记' },
  { v: 'COMPLETED', label: '已完成' },
  { v: 'ALL', label: '全部' },
]

const loading = ref(false)
const rows = ref<OutboundRequest[]>([])
const segment = ref('PENDING_ACCEPT')
const pageNo = ref(1)
const total = ref(0)
const locationEnabled = ref(false)

const merchantName = new Map<string, string>()
const skuName = new Map<string, string>()
const skuSpec = new Map<string, string | null>()

const showSheet = ref(false)
const panel = ref<'detail' | 'register'>('detail')
const target = ref<OutboundRequest | null>(null)
const submitting = ref(false)

// 登记表单
const regRelease = ref('')
const regLocation = ref('')

const hasMore = computed(() => rows.value.length < Number(total))

function skuLabel(wid?: SnowflakeId | null, sid?: SnowflakeId | null): string {
  if (!wid || !sid) return ''
  const n = skuName.get(`${wid}:${sid}`) ?? ''
  const spec = skuSpec.get(`${wid}:${sid}`) ?? ''
  return spec ? `${n}（${spec}）` : n
}

function merchantOf(d: OutboundRequest): string {
  return d.wholesalerName || (d.wholesalerId ? merchantName.get(String(d.wholesalerId)) ?? '' : '')
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId && work.role === 'WK')
}

function goCreate(): void {
  uni.navigateTo({ url: '/pages/wk/outbound/create' })
}

async function loadConfig(): Promise<void> {
  const work = readWork()
  if (!work) return
  try {
    const cfg = await wkApi.batchConfig(work.tenantId)
    locationEnabled.value = cfg.locationEnabled === 1
  } catch {
    /* 读失败按关闭处理 */
  }
}

async function loadNames(): Promise<void> {
  try {
    const ws = await wkApi.listWholesalers()
    ws.forEach((w) => merchantName.set(String(w.id), w.name))
    const needed = new Set<string>()
    rows.value.forEach((d) => {
      if (d.wholesalerId) needed.add(String(d.wholesalerId))
    })
    const lists = await Promise.all(
      Array.from(needed).map(async (wid) => {
        try {
          return await wkApi.listSkuByWholesaler(wid as unknown as SnowflakeId)
        } catch {
          return []
        }
      }),
    )
    lists.flat().forEach((s) => {
      skuName.set(`${s.wholesalerId}:${s.id}`, s.name)
      skuSpec.set(`${s.wholesalerId}:${s.id}`, s.spec)
    })
  } catch {
    /* 请求已 toast；名称缺失时展示降级文案 */
  }
}

async function fetchPage(p: number): Promise<void> {
  if (!guard()) {
    goLogin()
    return
  }
  loading.value = true
  try {
    const status = segment.value === 'ALL' ? undefined : segment.value
    const res = await wkApi.outbound.list(status, p, 20)
    const list = res.records
    if (p === 1) rows.value = list
    else rows.value = rows.value.concat(list)
    pageNo.value = p
    total.value = Number(res.total)
    await loadNames()
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function reloadFirst(): void {
  void fetchPage(1)
}

function pickSegment(v: string): void {
  if (segment.value === v) return
  segment.value = v
  reloadFirst()
}

function replaceRow(d: OutboundRequest): void {
  rows.value = rows.value.map((r) => (r.id === d.id ? d : r))
  target.value = d
}

function openDoc(d: OutboundRequest): void {
  target.value = d
  panel.value = 'detail'
  showSheet.value = true
}

function closeSheet(): void {
  if (submitting.value) return
  showSheet.value = false
  target.value = null
}

function openRegister(): void {
  regRelease.value = ''
  regLocation.value = ''
  panel.value = 'register'
}

async function onPrint(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  uni.showModal({
    title: '受理出库单',
    content: '将在手机上完成打印标记（纸单可在电脑端补打），随后登记出库。',
    confirmText: '开始作业',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        const vo = await wkApi.outbound.print(d.id)
        replaceRow(vo)
        uni.showToast({ title: '已打印，请核对登记', icon: 'success' })
        openRegister()
      } catch {
        /* request 已 toast */
      } finally {
        submitting.value = false
      }
    },
  })
}

async function submitRegister(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  if (locationEnabled.value && !regLocation.value.trim()) {
    uni.showToast({ title: '货位功能已开启，请填写拣出货位', icon: 'none' })
    return
  }
  const rel = regRelease.value.trim()
  if (rel !== '' && (!Number.isFinite(Number(rel)) || Number(rel) < 0)) {
    uni.showToast({ title: '请输入正确的托盘数', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const vo = await wkApi.outbound.register(d.id, {
      palletRelease: rel === '' ? undefined : Number(rel),
      location: regLocation.value.trim() || undefined,
    })
    replaceRow(vo)
    uni.showToast({ title: '登记完成，库存已扣减', icon: 'success' })
    showSheet.value = false
    target.value = null
  } catch {
    /* request 已 toast */
  } finally {
    submitting.value = false
  }
}

async function onRevert(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  uni.showModal({
    title: '回退待受理',
    content: '该单退回待受理队列，可重新核对与打印。',
    confirmText: '回退',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        const vo = await wkApi.outbound.revertToPending(d.id)
        replaceRow(vo)
        uni.showToast({ title: '已回退', icon: 'success' })
      } catch {
        /* request 已 toast */
      } finally {
        submitting.value = false
      }
    },
  })
}

function onConfirmWithdraw(): void {
  const d = target.value
  if (!d || submitting.value) return
  uni.showModal({
    title: '同意撤回',
    content: '同意后该出库单取消，库存将自动回补。',
    confirmText: '同意撤回',
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        const vo = await wkApi.outbound.confirmWithdraw(d.id)
        replaceRow(vo)
        uni.showToast({ title: '已同意撤回', icon: 'success' })
        showSheet.value = false
        target.value = null
      } catch {
        /* request 已 toast */
      } finally {
        submitting.value = false
      }
    },
  })
}

function onRejectWithdraw(): void {
  const d = target.value
  if (!d || submitting.value) return
  uni.showModal({
    title: '拒绝撤回',
    content: '拒绝后商户将收到通知，单据继续履约，请尽快登记出库。',
    confirmText: '拒绝撤回',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        const vo = await wkApi.outbound.rejectWithdraw(d.id)
        replaceRow(vo)
        uni.showToast({ title: '已拒绝，继续履约', icon: 'success' })
      } catch {
        /* request 已 toast */
      } finally {
        submitting.value = false
      }
    },
  })
}

function sourceLabel(s: string | null | undefined): string {
  return (s && OUTBOUND_SOURCE_LABELS[s]) ?? ''
}

function outStatusLabel(s: string | null | undefined): string {
  return (s && OUTBOUND_STATUS_LABELS[s]) ?? s ?? '—'
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void loadConfig()
  reloadFirst()
})

onShow(() => {
  if (guard()) reloadFirst()
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
    <!-- 分段 -->
    <view class="tabs">
      <view
        v-for="t in SEGMENTS"
        :key="t.v"
        class="tabs__item"
        :class="{ 'tabs__item--on': segment === t.v }"
        @click="pickSegment(t.v)"
      >{{ t.label }}</view>
    </view>

    <view class="toolbar">
      <text class="toolbar__tip">{{ segment === 'PENDING_ACCEPT' ? '待受理队列：意向单自动转 / 商户申请' : segment === 'PRINTED' ? '已打印待登记：核对货位与件数后登记出库' : '出库单列表，支持下拉刷新' }}</text>
      <view class="toolbar__add" @click="goCreate">＋ 代建出库</view>
    </view>

    <!-- 列表 -->
    <view v-if="rows.length" class="list">
      <view v-for="d in rows" :key="String(d.id)" class="card" @click="openDoc(d)">
        <view class="card__head">
          <text class="card__no mono">{{ d.docNo }}</text>
          <view class="card__tags">
            <text v-if="sourceLabel(d.source)" class="card__src">{{ sourceLabel(d.source) }}</text>
            <text class="tag" :class="`tag--${statusTone(d.status, 'out')}`">{{ outStatusLabel(d.status) }}</text>
          </view>
        </view>
        <view class="card__main">
          <view class="card__line1">
            <text class="card__name">{{ skuLabel(d.wholesalerId, d.skuId) || 'SKU' }}</text>
            <text class="card__qty">{{ d.qty }} 件</text>
          </view>
          <view class="card__line2">
            <text class="card__merchant">{{ merchantOf(d) || '商户' }}</text>
            <text v-if="d.palletQty != null" class="card__sub">{{ d.palletQty }} 托</text>
          </view>
          <view v-if="d.withdrawRequested" class="card__warn">商户申请撤回，待二次确认</view>
        </view>
        <view class="card__meta">
          <text class="card__time">{{ fmtDateTime(d.completedAt || d.createdAt) }}</text>
          <text class="card__act">{{ d.status === 'PENDING_ACCEPT' ? '受理作业' : d.status === 'PRINTED' ? '登记出库' : '查看' }} ›</text>
        </view>
      </view>
      <view v-if="hasMore && !loading" class="list__more" @click="fetchPage(pageNo + 1)">加载更多</view>
    </view>

    <view v-else-if="!loading" class="empty">
      <text class="empty__icon">发</text>
      <text class="empty__t">暂无{{ SEGMENTS.find((s) => s.v === segment)?.label }}出库单</text>
    </view>

    <!-- 详情/操作弹层 -->
    <view v-if="showSheet && target" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <template v-if="panel === 'detail'">
          <view class="sheet__title">出库单</view>
          <view class="kv">
            <view class="kv__row"><text class="kv__k">单号</text><text class="kv__v mono">{{ target.docNo }}</text></view>
            <view class="kv__row"><text class="kv__k">来源</text><text class="kv__v">{{ sourceLabel(target.source) || '—' }}</text></view>
            <view class="kv__row"><text class="kv__k">商户</text><text class="kv__v">{{ merchantOf(target) || '—' }}</text></view>
            <view class="kv__row"><text class="kv__k">货品</text><text class="kv__v">{{ skuLabel(target.wholesalerId, target.skuId) || '—' }}</text></view>
            <view class="kv__row"><text class="kv__k">出库件数</text><text class="kv__v">{{ target.qty }} 件</text></view>
            <view class="kv__row"><text class="kv__k">托盘</text><text class="kv__v">{{ target.palletQty ?? 0 }} 托</text></view>
            <view v-if="target.status !== 'PENDING_ACCEPT'" class="kv__row">
              <text class="kv__k">打印</text>
              <text class="kv__v">{{ fmtDateTime(target.printedAt) }}（{{ target.printCount ?? 0 }} 次）</text>
            </view>
            <view v-if="target.completedAt" class="kv__row">
              <text class="kv__k">完成时间</text>
              <text class="kv__v">{{ fmtDateTime(target.completedAt) }}{{ target.location ? ` · 货位 ${target.location}` : '' }}</text>
            </view>
            <view class="kv__row"><text class="kv__k">创建时间</text><text class="kv__v">{{ fmtDateTime(target.createdAt) }}</text></view>
            <view v-if="target.withdrawRequested" class="kv__row">
              <text class="kv__k">撤回申请</text>
              <text class="kv__v">商户于 {{ fmtDateTime(target.withdrawRequestedAt) }} 申请撤回</text>
            </view>
          </view>

          <button v-if="target.status === 'PENDING_ACCEPT'" class="btn-primary sheet__btn" :loading="submitting" @click="onPrint">受理并登记</button>

          <template v-if="target.status === 'PRINTED'">
            <button class="btn-primary sheet__btn" @click="openRegister">登记出库</button>
            <view class="link-row">
              <text class="link-row__btn" @click="onRevert">回退待受理</text>
              <text v-if="target.withdrawRequested" class="link-row__btn" @click="onConfirmWithdraw">同意撤回</text>
              <text v-if="target.withdrawRequested" class="link-row__btn" @click="onRejectWithdraw">拒绝撤回</text>
            </view>
          </template>
        </template>

        <template v-else-if="panel === 'register'">
          <view class="sheet__title">登记出库</view>
          <view class="reject-sum">
            <text class="reject-sum__sku">{{ target && skuLabel(target.wholesalerId, target.skuId) }}</text>
            <text class="reject-sum__qty">{{ target?.docNo }} · 出库 {{ target?.qty }} 件</text>
          </view>
          <view v-if="locationEnabled" class="expiry-warn">
            <text>货位功能已开启：拣出货位必填；如需纸单可在电脑端补打。</text>
          </view>
          <view class="field-label">释放托盘（可空=按默认建议值；填 0 表示托盘未腾空）</view>
          <input v-model="regRelease" class="input" type="number" placeholder="可空" placeholder-class="ph" />
          <view v-if="locationEnabled" class="field-label">拣出货位</view>
          <input v-if="locationEnabled" v-model="regLocation" class="input" type="text" placeholder="如 B-02" placeholder-class="ph" maxlength="64" />
          <view class="sheet__btns">
            <button class="btn-ghost" @click="panel = 'detail'">返回</button>
            <button class="btn-primary" :loading="submitting" @click="submitRegister">确认登记出库</button>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 20rpx 24rpx 40rpx;
  box-sizing: border-box;
}

.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;

  &__item {
    flex: 1;
    text-align: center;
    padding: 14rpx 0;
    border-radius: 12rpx;
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
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;

  &__tip {
    flex: 1;
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__add {
    flex-shrink: 0;
    padding: 10rpx 22rpx;
    border-radius: 999rpx;
    background: $cc-bg-1;
    border: 2rpx solid $cc-accent;
    color: $cc-accent;
    font-size: 23rpx;
    font-weight: 600;
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;

  &__more {
    text-align: center;
    padding: 20rpx 0;
    font-size: 23rpx;
    color: $cc-fg-4;
  }
}

.card {
  padding: 24rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12rpx;
    gap: 10rpx;
  }

  &__no {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__tags {
    display: flex;
    align-items: center;
    gap: 10rpx;
  }

  &__src {
    font-size: 18rpx;
    color: $cc-fg-4;
    padding: 2rpx 8rpx;
    border-radius: 4rpx;
    background: $cc-bg-3;
  }

  &__main {
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__line1 {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10rpx;
  }

  &__name {
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__qty {
    font-size: 25rpx;
    font-weight: 700;
    color: $cc-warning;
  }

  &__line2 {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 22rpx;
  }

  &__merchant {
    color: $cc-fg-3;
  }

  &__sub {
    color: $cc-fg-4;
  }

  &__warn {
    margin-top: 8rpx;
    padding: 8rpx 12rpx;
    border-radius: 8rpx;
    background: $cc-danger-bg;
    color: $cc-danger;
    font-size: 21rpx;
  }

  &__meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 14rpx;
    padding-top: 14rpx;
    border-top: 2rpx solid $cc-bg-2;
  }

  &__time {
    font-size: 20rpx;
    color: $cc-fg-4;
  }

  &__act {
    font-size: 23rpx;
    color: $cc-accent;
    font-weight: 600;
  }
}

.tag {
  font-size: 20rpx;
  padding: 4rpx 14rpx;
  border-radius: 6rpx;

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }

  &--ok {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--muted {
    background: $cc-bg-3;
    color: $cc-fg-3;
  }

  &--info {
    background: $cc-info-bg;
    color: $cc-accent;
  }
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 120rpx 0;

  &__icon {
    width: 96rpx;
    height: 96rpx;
    line-height: 96rpx;
    text-align: center;
    border-radius: 24rpx;
    background: $cc-bg-3;
    color: $cc-fg-4;
    font-size: 40rpx;
  }

  &__t {
    font-size: 28rpx;
    font-weight: 600;
    color: $cc-fg-2;
  }
}

/* 弹层 */
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
  max-height: 86vh;
  overflow-y: auto;
  padding: 36rpx 32rpx;
  padding-bottom: calc(36rpx + env(safe-area-inset-bottom));
  border-radius: 28rpx 28rpx 0 0;
  background: $cc-bg-1;
  box-sizing: border-box;

  &__title {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;
    margin-bottom: 20rpx;
  }

  &__btn {
    margin-top: 24rpx;
    height: 92rpx;
    font-size: 29rpx;
    font-weight: 600;
  }

  &__btns {
    display: flex;
    gap: 16rpx;
    margin-top: 30rpx;

    .btn-primary,
    .btn-ghost {
      flex: 1;
      height: 92rpx;
      font-size: 29rpx;
      font-weight: 600;
    }
  }
}

.kv {
  &__row {
    display: flex;
    padding: 10rpx 0;
    font-size: 25rpx;
    line-height: 1.5;
  }

  &__k {
    width: 150rpx;
    flex-shrink: 0;
    color: $cc-fg-4;
  }

  &__v {
    flex: 1;
    color: $cc-fg-2;
    word-break: break-all;
  }
}

.reject-sum {
  padding: 18rpx;
  border-radius: 10rpx;
  background: $cc-bg-2;
  margin-bottom: 20rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;

  &__sku {
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__qty {
    font-size: 22rpx;
    color: $cc-fg-3;
  }
}

.expiry-warn {
  padding: 16rpx 18rpx;
  margin-bottom: 16rpx;
  border-radius: 10rpx;
  background: $cc-info-bg;
  color: $cc-accent;
  font-size: 22rpx;
  line-height: 1.6;
}

.field-label {
  font-size: 24rpx;
  font-weight: 600;
  color: $cc-fg-2;
  margin: 16rpx 0 10rpx;
}

.input {
  height: 84rpx;
  padding: 0 20rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 10rpx;
  font-size: 28rpx;
  color: $cc-fg-1;
  box-sizing: border-box;
}

.ph {
  color: $cc-fg-4;
}

.link-row {
  display: flex;
  flex-wrap: wrap;
  gap: 28rpx;
  justify-content: center;
  margin-top: 22rpx;

  &__btn {
    font-size: 24rpx;
    color: $cc-fg-3;
    text-decoration: underline;
  }
}

.mono {
  font-family: monospace;
}

.btn-ghost {
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 14rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-1;
  color: $cc-fg-3;
  font-size: 26rpx;

  &::after {
    border: none;
  }
}
</style>
