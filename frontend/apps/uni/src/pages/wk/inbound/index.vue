<script setup lang="ts">
// WK 入库作业（F5-W1 · F7-1 扩现场代建）：两链合一页。
//  - 正向申请链：WA 商户提交的入库申请受理/驳回/登记（SUBMITTED→ACCEPTED→CONFIRMED）。
//  - 现场代建链：WK 替商户登记入库（现场到货直接入库，拍照按 TA 拍照开关执行）→
//    PENDING_WA_CONFIRM 72h 商户确认/异议；异议 → TA 仲裁，判定成立冲销撤销 REVOKED。
// 段：现场代建（集中看代建单） + 待受理 / 待登记 / 已驳回 / 已完成；差异登记备注必填、过期批次二次确认。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type { InboundRequest, InboundRejectReason, SnowflakeId } from '@cangchu/api-types'
import { rejectReasonOptions, wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import { INBOUND_STATUS_LABELS, fmtDateTime, statusTone } from '../../../utils/warehouse'

function inStatusLabel(s: string | null | undefined): string {
  return (s && INBOUND_STATUS_LABELS[s]) ?? s ?? '—'
}

function rejectLabel(r?: string | null): string {
  return rejectReasonOptions.find((o) => o.value === r)?.label ?? r ?? '—'
}

const SEGMENTS = [
  { value: 'WK_CREATED', label: '现场代建' },
  { value: 'SUBMITTED', label: '待受理' },
  { value: 'ACCEPTED', label: '待登记' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CONFIRMED', label: '已完成' },
]

const loading = ref(false)
const docs = ref<InboundRequest[]>([])
const segment = ref('SUBMITTED')

const merchantName = new Map<string, string>()
const skuName = new Map<string, string>()
const skuSpec = new Map<string, string | null>()

const locationEnabled = ref(false)
const batchEnabled = ref(false)

// 详情弹层：panel = detail / reject / register
const showSheet = ref(false)
const panel = ref<'detail' | 'reject' | 'register'>('detail')
const target = ref<InboundRequest | null>(null)
const submitting = ref(false)

// 驳回表单
const rejectReason = ref<InboundRejectReason>('QTY')
const rejectRemark = ref('')

// 登记表单
const regQty = ref('')
const regPallet = ref('')
const regRemark = ref('')
const regLocation = ref('')
const regExpiredOk = ref(false)

const visible = computed(() => {
  const s = segment.value
  return docs.value.filter((d) =>
    s === 'WK_CREATED' ? d.source === 'WK_CREATED' : d.source === 'WA_SUBMIT' && d.status === s,
  )
})

const segCount = (s: string) =>
  docs.value.filter((d) =>
    s === 'WK_CREATED' ? d.source === 'WK_CREATED' : d.source === 'WA_SUBMIT' && d.status === s,
  ).length

function merchantOf(wid?: SnowflakeId | null): string {
  return wid ? merchantName.get(String(wid)) ?? '' : ''
}

function skuLabel(wid?: SnowflakeId | null, sid?: SnowflakeId | null): string {
  if (!wid || !sid) return ''
  const n = skuName.get(`${wid}:${sid}`) ?? ''
  const spec = skuSpec.get(`${wid}:${sid}`) ?? ''
  return spec ? `${n}（${spec}）` : n
}

function goLogin(): void {
  uni.reLaunch({ url: '/pages/wa/login/index' })
}

function goCreate(): void {
  uni.navigateTo({ url: '/pages/wk/inbound/create' })
}

/** 现场代建链确认状态副文案（PENDING_WA_CONFIRM 72h → CONFIRMED/DISPUTED；DISPUTED → TA 仲裁） */
function agentStatusText(d: InboundRequest): string {
  if (d.status === 'PENDING_WA_CONFIRM') {
    return isDeadlinePast(d) ? '窗口已过，待自动确认' : '待商户确认'
  }
  if (d.status === 'CONFIRMED') return d.autoAccepted === 1 ? '逾期自动确认' : '商户已确认'
  if (d.status === 'DISPUTED') return '商户异议，TA 仲裁中'
  if (d.status === 'REVOKED') return '已撤销（已冲销）'
  return inStatusLabel(d.status)
}

function isDeadlinePast(d: InboundRequest): boolean {
  if (!d.waConfirmDeadline) return false
  return new Date(d.waConfirmDeadline).getTime() < Date.now()
}

function previewPhotos(urls: string[], i: number): void {
  if (urls.length) uni.previewImage({ urls, current: urls[i] ?? urls[0] })
}

function guard(): boolean {
  const auth = readAuth()
  const work = readWork()
  return !!(auth && work && work.tenantId && work.role === 'WK')
}

async function loadConfig(): Promise<void> {
  const work = readWork()
  if (!work) return
  try {
    const cfg = await wkApi.batchConfig(work.tenantId)
    locationEnabled.value = cfg.locationEnabled === 1
    batchEnabled.value = cfg.batchEnabled === 1
  } catch {
    /* 读失败按关闭处理 */
  }
}

async function loadDocs(): Promise<void> {
  if (!guard()) {
    goLogin()
    return
  }
  loading.value = true
  try {
    docs.value = await wkApi.inbound.list()
    // 名称映射：商户全量 + 涉及商户的 SKU 全量（页容量小，按需拉取）
    const ws = await wkApi.listWholesalers()
    ws.forEach((w) => merchantName.set(String(w.id), w.name))
    const needed = new Set<string>()
    docs.value.forEach((d) => {
      if (d.wholesalerId) needed.add(String(d.wholesalerId))
    })
    const list = await Promise.all(
      Array.from(needed).map(async (wid) => {
        try {
          return await wkApi.listSkuByWholesaler(wid as unknown as SnowflakeId)
        } catch {
          return []
        }
      }),
    )
    list.flat().forEach((s) => {
      skuName.set(`${s.wholesalerId}:${s.id}`, s.name)
      skuSpec.set(`${s.wholesalerId}:${s.id}`, s.spec)
    })
  } catch {
    // request 已 toast
  } finally {
    loading.value = false
  }
}

function pickSegment(v: string): void {
  segment.value = v
}

function openDoc(d: InboundRequest): void {
  target.value = d
  panel.value = 'detail'
  showSheet.value = true
}

function closeSheet(): void {
  if (submitting.value) return
  showSheet.value = false
  target.value = null
}

function openReject(d: InboundRequest): void {
  rejectReason.value = 'QTY'
  rejectRemark.value = ''
  target.value = d
  panel.value = 'reject'
}

function openRegister(d: InboundRequest): void {
  regQty.value = String(d.requestedQty ?? d.qty ?? '')
  regPallet.value = d.palletQty != null ? String(d.palletQty) : '0'
  regRemark.value = ''
  regLocation.value = ''
  regExpiredOk.value = false
  target.value = d
  panel.value = 'register'
}

function pickRejectReason(r: InboundRejectReason): void {
  rejectReason.value = r
}

function isExpiredDoc(d: InboundRequest | null): boolean {
  if (!d?.expiryDate) return false
  const today = new Date().toISOString().slice(0, 10)
  return d.expiryDate <= today
}

function toNum(s: string): number {
  const n = Number(s)
  return Number.isFinite(n) ? n : NaN
}

async function onAccept(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  uni.showModal({
    title: '受理入库申请',
    content: '受理后商户不可再撤回；请核对货物后点击登记完成入库。',
    confirmText: '确认受理',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        await wkApi.inbound.accept(d.id)
        uni.showToast({ title: '已受理', icon: 'success' })
        showSheet.value = false
        await loadDocs()
      } catch {
        /* request 已 toast */
      } finally {
        submitting.value = false
      }
    },
  })
}

async function submitReject(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  if (!rejectRemark.value.trim()) {
    uni.showToast({ title: '请填写驳回说明', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await wkApi.inbound.reject(d.id, { reason: rejectReason.value, remark: rejectRemark.value.trim() })
    uni.showToast({ title: '已驳回，商户将收到通知', icon: 'success' })
    showSheet.value = false
    await loadDocs()
  } catch {
    /* request 已 toast */
  } finally {
    submitting.value = false
  }
}

async function submitRegister(): Promise<void> {
  const d = target.value
  if (!d || submitting.value) return
  const qty = toNum(regQty.value)
  if (!Number.isFinite(qty) || qty <= 0) {
    uni.showToast({ title: '请输入正确的实收件数', icon: 'none' })
    return
  }
  const applied = d.requestedQty ?? d.qty
  const diff = qty !== applied
  if (diff && !regRemark.value.trim()) {
    uni.showToast({ title: `与申请件数不同，请填写差异备注`, icon: 'none' })
    return
  }
  if (isExpiredDoc(d) && !regExpiredOk.value) {
    uni.showToast({ title: '该批次已过期，请勾选二次确认', icon: 'none' })
    return
  }
  if (locationEnabled.value && !regLocation.value.trim()) {
    uni.showToast({ title: '货位功能已开启，请填写货位号', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const pallet = toNum(regPallet.value)
    await wkApi.inbound.registerForward(d.id, {
      actualQty: qty,
      palletQty: Number.isFinite(pallet) && pallet >= 0 ? pallet : undefined,
      remark: regRemark.value.trim() || undefined,
      expiredConfirmed: isExpiredDoc(d) ? true : undefined,
      location: regLocation.value.trim() || undefined,
    })
    uni.showToast({ title: '登记完成，库存已增加', icon: 'success' })
    showSheet.value = false
    await loadDocs()
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
  void loadConfig()
  void loadDocs()
})

onShow(() => {
  if (guard()) void loadDocs()
})

onPullDownRefresh(async () => {
  await loadDocs()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 现场代建入库入口（F7-1） -->
    <view class="toolbar">
      <text class="toolbar__tip">商户现场到货可代商户登记入库（提交即增库存），商户 72h 内确认或提异议</text>
      <button class="btn-primary toolbar__btn" @click="goCreate">＋ 现场代建入库</button>
    </view>

    <!-- 分段 -->
    <view class="tabs">
      <view
        v-for="t in SEGMENTS"
        :key="t.value"
        class="tabs__item"
        :class="{ 'tabs__item--on': segment === t.value }"
        @click="pickSegment(t.value)"
      >
        {{ t.label }}
        <text v-if="segCount(t.value)" class="tabs__cnt">{{ segCount(t.value) }}</text>
      </view>
    </view>

    <!-- 列表 -->
    <view v-if="visible.length" class="list">
      <view v-for="d in visible" :key="String(d.id)" class="card" @click="openDoc(d)">
        <view class="card__head">
          <text class="card__no mono">{{ d.docNo }}</text>
          <text class="tag" :class="`tag--${statusTone(d.status, 'in')}`">{{ inStatusLabel(d.status) }}</text>
        </view>
        <view class="card__main">
          <view class="card__line1">
            <text class="card__name">{{ skuLabel(d.wholesalerId, d.skuId) || 'SKU' }}</text>
            <text v-if="d.batchNo" class="card__batch mono">批次 {{ d.batchNo }}</text>
          </view>
          <view class="card__line2">
            <text class="card__merchant">{{ merchantOf(d.wholesalerId) || '商户' }}</text>
            <text class="card__qty">{{ d.source === 'WK_CREATED' ? `登记 ${d.qty} 件` : `申请 ${d.requestedQty ?? d.qty} 件` }}</text>
          </view>
        </view>
        <view class="card__meta">
          <text class="card__time">{{ fmtDateTime(d.createdAt) }}</text>
          <text v-if="d.source === 'WK_CREATED'" class="card__act">{{ agentStatusText(d) }} ›</text>
          <text v-else class="card__act">{{ d.status === 'SUBMITTED' ? '去受理' : d.status === 'ACCEPTED' ? '去登记' : '查看' }} ›</text>
        </view>
      </view>
    </view>

    <view v-else-if="!loading" class="empty">
      <text class="empty__icon">{{ segment === 'WK_CREATED' ? '建' : '收' }}</text>
      <text class="empty__t">{{ segment === 'WK_CREATED' ? '暂无现场代建单' : `暂无${SEGMENTS.find((s) => s.value === segment)?.label}申请` }}</text>
      <text class="empty__s">{{ segment === 'WK_CREATED' ? '现场登记的单据将在此展示，点击顶部「＋ 现场代建入库」新建' : '商户提交的入库申请将在此待办受理与登记' }}</text>
    </view>

    <!-- 详情/操作弹层 -->
    <view v-if="showSheet && target" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <template v-if="panel === 'detail'">
          <view class="sheet__title">{{ target.source === 'WK_CREATED' ? '现场代建入库单' : '入库申请单' }}</view>
          <view class="kv">
            <view class="kv__row"><text class="kv__k">单号</text><text class="kv__v mono">{{ target.docNo }}</text></view>
            <view class="kv__row"><text class="kv__k">商户</text><text class="kv__v">{{ merchantOf(target.wholesalerId) || '—' }}</text></view>
            <view class="kv__row"><text class="kv__k">货品</text><text class="kv__v">{{ skuLabel(target.wholesalerId, target.skuId) || '—' }}</text></view>
            <view v-if="target.source !== 'WK_CREATED'" class="kv__row"><text class="kv__k">申请件数</text><text class="kv__v">{{ target.requestedQty ?? target.qty }} 件</text></view>
            <view v-if="target.source !== 'WK_CREATED'" class="kv__row"><text class="kv__k">申请托盘</text><text class="kv__v">{{ target.palletQty ?? 0 }} 托</text></view>
            <view v-if="target.source === 'WK_CREATED'" class="kv__row"><text class="kv__k">登记件数</text><text class="kv__v">{{ target.qty }} 件</text></view>
            <view v-if="target.source === 'WK_CREATED'" class="kv__row"><text class="kv__k">登记托盘</text><text class="kv__v">{{ target.palletQty ?? 0 }} 托</text></view>
            <template v-if="batchEnabled">
              <view class="kv__row"><text class="kv__k">批次号</text><text class="kv__v mono">{{ target.batchNo ?? '—' }}</text></view>
              <view class="kv__row"><text class="kv__k">生产/效期</text><text class="kv__v">{{ target.productionDate ?? '—' }} / {{ target.expiryDate ?? '—' }}</text></view>
            </template>
            <view class="kv__row"><text class="kv__k">{{ target.source === 'WK_CREATED' ? '登记时间' : '提交时间' }}</text><text class="kv__v">{{ fmtDateTime(target.createdAt) }}</text></view>
            <view v-if="target.remark" class="kv__row"><text class="kv__k">备注</text><text class="kv__v">{{ target.remark }}</text></view>
            <!-- 现场代建确认状态（F7-1 · 72h 商户确认/异议 → TA 仲裁） -->
            <view v-if="target.source === 'WK_CREATED' && target.status === 'PENDING_WA_CONFIRM'" class="kv__row">
              <text class="kv__k">商户确认</text>
              <text class="kv__v">截止 {{ fmtDateTime(target.waConfirmDeadline) }}{{ isDeadlinePast(target) ? ' · 窗口已过，待系统自动确认' : '' }}</text>
            </view>
            <view v-if="target.source === 'WK_CREATED' && target.status === 'CONFIRMED'" class="kv__row">
              <text class="kv__k">商户确认</text>
              <text class="kv__v">{{ target.autoAccepted === 1 ? '逾期自动确认' : '商户已确认' }} · {{ fmtDateTime(target.waConfirmAt) }}</text>
            </view>
            <view v-if="target.source === 'WK_CREATED' && target.status === 'DISPUTED'" class="kv__row">
              <text class="kv__k">异议</text>
              <text class="kv__v">商户已提出异议，TA 仲裁处理中</text>
            </view>
            <view v-if="target.source === 'WK_CREATED' && target.status === 'REVOKED'" class="kv__row">
              <text class="kv__k">异议</text>
              <text class="kv__v">仲裁判定异议成立，单据已撤销（库存已冲销）</text>
            </view>
            <view v-if="target.status === 'REJECTED'" class="kv__row">
              <text class="kv__k">驳回</text>
              <text class="kv__v">理由：{{ rejectLabel(target.rejectReason) }}；{{ target.rejectRemark ?? '' }}</text>
            </view>
            <view v-if="target.status === 'WITHDRAWN'" class="kv__row"><text class="kv__k">已撤回</text><text class="kv__v">{{ target.withdrawReason ?? '商户撤回' }}</text></view>
          </view>
          <!-- 登记照片（F7-1：现场拍照附件 ≤5 落库回显，点击放大预览） -->
          <view v-if="target.attachments?.length" class="detail-photos">
            <text class="detail-photos__label">登记照片（{{ target.attachments.length }}）</text>
            <view class="detail-photos__grid">
              <image
                v-for="(u, i) in target.attachments"
                :key="u"
                class="detail-photos__img"
                :src="u"
                mode="aspectFill"
                @click="previewPhotos(target.attachments ?? [], i)"
              />
            </view>
          </view>
          <view v-if="target.source !== 'WK_CREATED' && target.status === 'SUBMITTED'" class="ops">
            <button class="btn-ghost" @click="openReject(target)">驳回</button>
            <button class="btn-primary ops__main" :loading="submitting" @click="onAccept">受理</button>
          </view>
          <button v-if="target.source !== 'WK_CREATED' && target.status === 'ACCEPTED'" class="btn-primary sheet__btn" @click="openRegister(target)">登记入库</button>
        </template>

        <template v-else-if="panel === 'reject'">
          <view class="sheet__title">驳回入库申请</view>
          <view class="reject-sum">
            <text class="reject-sum__sku">{{ skuLabel(target.wholesalerId, target.skuId) }}</text>
            <text class="reject-sum__qty">申请 {{ target.requestedQty ?? target.qty }} 件</text>
          </view>
          <view class="field-label">驳回原因</view>
          <view class="chips">
            <view
              v-for="r in rejectReasonOptions"
              :key="r.value"
              class="chip"
              :class="{ 'chip--on': rejectReason === r.value }"
              @click="pickRejectReason(r.value)"
            >{{ r.label }}</view>
          </view>
          <view class="field-label">驳回说明（必填，将通知商户）</view>
          <textarea v-model="rejectRemark" class="textarea" placeholder="如：数量不符 / 批次与单据不一致等" placeholder-class="ph" :maxlength="200" />
          <view class="sheet__btns">
            <button class="btn-ghost" @click="panel = 'detail'">返回</button>
            <button class="btn-primary sheet__btn--danger" :loading="submitting" @click="submitReject">确认驳回</button>
          </view>
        </template>

        <template v-else-if="panel === 'register'">
          <view class="sheet__title">登记入库</view>
          <view class="reject-sum">
            <text class="reject-sum__sku">{{ skuLabel(target.wholesalerId, target.skuId) }}</text>
            <text class="reject-sum__qty">申请 {{ target.requestedQty ?? target.qty }} 件 · 批次 {{ target.batchNo ?? '—' }}</text>
          </view>
          <view v-if="isExpiredDoc(target)" class="expiry-warn">
            <text>该批次到效期 {{ target.expiryDate }} 已过期，登记入库属于过期批次入库，需二次确认。</text>
          </view>
          <view class="field-label">实收件数</view>
          <input v-model="regQty" class="input" type="number" placeholder="0" placeholder-class="ph" />
          <view class="field-label">托盘数（可空，默认沿用申请）</view>
          <input v-model="regPallet" class="input" type="number" placeholder="0" placeholder-class="ph" />
          <view v-if="locationEnabled" class="field-label">货位号（开启后必填）</view>
          <input v-if="locationEnabled" v-model="regLocation" class="input" type="text" placeholder="如 A-01-02" placeholder-class="ph" maxlength="64" />
          <view class="field-label">备注（实收 ≠ 申请件数时必填）</view>
          <textarea v-model="regRemark" class="textarea" placeholder="差异原因，如：破损 2 件" placeholder-class="ph" :maxlength="200" />
          <view v-if="isExpiredDoc(target)" class="check-line" @click="regExpiredOk = !regExpiredOk">
            <view class="check-line__box" :class="{ 'check-line__box--on': regExpiredOk }">{{ regExpiredOk ? '✓' : '' }}</view>
            <text class="check-line__t">我已知悉批次过期，仍确认登记入库</text>
          </view>
          <view class="sheet__btns">
            <button class="btn-ghost" @click="panel = 'detail'">返回</button>
            <button class="btn-primary" :loading="submitting" @click="submitRegister">确认登记入库</button>
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
  margin-bottom: 20rpx;

  &__item {
    flex: 1;
    text-align: center;
    padding: 16rpx 0;
    border-radius: 12rpx;
    background: $cc-bg-1;
    border: 2rpx solid $cc-border-1;
    font-size: 24rpx;
    color: $cc-fg-2;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6rpx;

    &--on {
      background: $cc-info-bg;
      border-color: $cc-accent;
      color: $cc-accent;
      font-weight: 600;
    }
  }

  &__cnt {
    background: rgba(2, 6, 23, 0.06);
    border-radius: 999rpx;
    padding: 0 8rpx;
    font-size: 18rpx;
    line-height: 24rpx;
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
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
  }

  &__no {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__main {
    display: flex;
    flex-direction: column;
    gap: 6rpx;
  }

  &__line1 {
    display: flex;
    align-items: center;
    gap: 14rpx;
  }

  &__name {
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__batch {
    font-size: 20rpx;
    color: $cc-fg-4;
    padding: 2rpx 8rpx;
    border-radius: 4rpx;
    background: $cc-bg-2;
  }

  &__line2 {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10rpx;
  }

  &__merchant {
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__qty {
    font-size: 22rpx;
    color: $cc-fg-2;
  }

  &__sub {
    font-size: 20rpx;
    color: $cc-fg-4;
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

  &__s {
    font-size: 22rpx;
    color: $cc-fg-4;
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

  &__btn {
    margin-top: 30rpx;
    height: 92rpx;
    font-size: 29rpx;
    font-weight: 600;
  }

  &__btn--danger {
    background: $cc-danger;
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

.ops {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;

  .btn-ghost {
    flex: 1;
    height: 92rpx;
  }

  &__main {
    flex: 2;
    height: 92rpx;
    font-size: 29rpx;
    font-weight: 600;
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
  background: $cc-danger-bg;
  color: $cc-danger;
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

.textarea {
  width: 100%;
  height: 140rpx;
  padding: 16rpx 20rpx;
  border: 2rpx solid $cc-border-1;
  border-radius: 10rpx;
  font-size: 26rpx;
  box-sizing: border-box;
}

.ph {
  color: $cc-fg-4;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.chip {
  padding: 12rpx 24rpx;
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

/* 现场代建入库入口（F7-1） */
.toolbar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 18rpx;

  &__tip {
    flex: 1;
    font-size: 20rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }

  &__btn {
    flex-shrink: 0;
    height: 76rpx;
    line-height: 76rpx;
    margin: 0;
    padding: 0 24rpx;
    font-size: 25rpx;
    font-weight: 600;

    &::after {
      border: none;
    }
  }
}

/* 登记照片预览（F7-1） */
.detail-photos {
  margin-top: 18rpx;

  &__label {
    display: block;
    font-size: 23rpx;
    color: $cc-fg-4;
    margin-bottom: 12rpx;
  }

  &__grid {
    display: flex;
    flex-wrap: wrap;
    gap: 12rpx;
  }

  &__img {
    width: 128rpx;
    height: 128rpx;
    border-radius: 10rpx;
    background: $cc-bg-2;
  }
}
</style>
