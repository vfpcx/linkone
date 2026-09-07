<script setup lang="ts">
// WA/WE 入库确认（US-WA-09 移动化 · F5 现场代建入库商户侧闭环）：
// 待确认（status=PENDING_WA_CONFIRM 后端按 72h 倒计时升序）/ 全部 双页签；
// 点行看登记详情（件数/货位/批次/登记照片）→ 确认接受 / 异议（预设理由 + 补充 ≤512 +
// 附件 ≤5 拍照，提交前实时库存预估：在库/冲销/差额，差额>0 进平台定责）→ 结果回显（YY- 仲裁单）。
import { computed, onBeforeUnmount, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import type { InboundDisputeResult, InboundRequest, InboundStockPreview, Sku } from '@cangchu/api-types'
import { waInboundApi } from '../../../api/waInbound'
import { skuApi } from '../../../api/sku'
import { uploadImage } from '../../../utils/request'
import { fmtDateTime } from '../../../utils/format'
import { hasWaScope, readWork, type WaWork } from '../../../utils/session'

type TabKey = 'pending' | 'all'

const STATUS_META: Record<string, { text: string; cls: string }> = {
  PENDING_WA_CONFIRM: { text: '待确认', cls: 'chip--warn' },
  CONFIRMED: { text: '已确认', cls: 'chip--ok' },
  DISPUTED: { text: '争议中', cls: 'chip--err' },
  SUBMITTED: { text: '待受理', cls: 'chip--warn' },
  ACCEPTED: { text: '已受理', cls: 'chip--ok' },
  REJECTED: { text: '已驳回', cls: 'chip--err' },
  WITHDRAWN: { text: '已撤回', cls: 'chip--void' },
  REVOKED: { text: '已撤销', cls: 'chip--void' },
}
const statusMeta = (s: string): { text: string; cls: string } =>
  STATUS_META[s] ?? { text: s, cls: 'chip--void' }

const SOURCE_LABEL: Record<string, string> = {
  WK_CREATED: '仓库代建',
  WA_SUBMIT: '我方提交',
}
const sourceLabel = (s: string | null): string => (s ? (SOURCE_LABEL[s] ?? s) : '—')

const DISPUTE_REASONS: Array<{ code: string; label: string }> = [
  { code: 'QTY', label: '数量不符' },
  { code: 'QUALITY', label: '质量问题' },
  { code: 'BATCH', label: '批次不符' },
  { code: 'OTHER', label: '其他' },
]

const work = ref<WaWork | null>(null)
const tab = ref<TabKey>('pending')
const rows = ref<InboundRequest[]>([])
const loading = ref(true)
const page = ref(1)
const finished = ref(false)
const submitting = ref(false)

const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING_WA_CONFIRM').length)

/** skuId → 品名（移动端拉「我的商品」建映射，失败则回退 skuId） */
const skuMap = new Map<string, Sku>()
const skuLabel = (id: unknown): string => {
  const s = skuMap.get(String(id))
  return s ? (s.spec ? `${s.name}（${s.spec}）` : s.name) : String(id)
}

// ===== 72h 倒计时（1s 刷新；LocalDateTime 无时区偏移，按本地解析） =====
const nowTick = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null
function startTick(): void {
  if (tickTimer) return
  tickTimer = setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
}
onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer)
})

function remainMs(row: InboundRequest): number | null {
  if (!row.waConfirmDeadline) return null
  const t = new Date(String(row.waConfirmDeadline)).getTime()
  return Number.isFinite(t) ? t - nowTick.value : null
}
function countdownText(row: InboundRequest): string {
  const ms = remainMs(row)
  if (ms === null) return ''
  if (ms <= 0) return '已到期 · 将自动接受'
  const total = Math.floor(ms / 1000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `还剩 ${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}
function countdownDanger(row: InboundRequest): boolean {
  const ms = remainMs(row)
  return ms !== null && ms > 0 && ms < 12 * 3600 * 1000
}

// ===== 列表加载（分页） =====
async function load(reset: boolean): Promise<void> {
  if (!work.value) return
  if (reset) {
    page.value = 1
    finished.value = false
    rows.value = []
  }
  loading.value = true
  try {
    const data = await waInboundApi.list(
      tab.value === 'pending' ? 'PENDING_WA_CONFIRM' : undefined,
      page.value,
      20,
    )
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
  uni.previewImage({ urls, current: urls[i] })
}

// ===== 确认 =====
async function doConfirm(): Promise<void> {
  const row = active.value
  if (!row || submitting.value) return
  uni.showModal({
    title: '确认入库',
    content: `按登记件数 ${row.qty} 件确认入库并开始计费？超时未回应将自动接受。`,
    confirmText: '确认接受',
    confirmColor: '#4F46E5',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        await waInboundApi.confirm(row.id)
        uni.showToast({ title: '已确认入库', icon: 'success' })
        detailOpen.value = false
        await load(true)
      } catch {
        // 50331/50332 已 toast → 刷新回显实际状态
        await load(true)
      } finally {
        submitting.value = false
      }
    },
  })
}

// ===== 异议 =====
const disputeOpen = ref(false)
const preview = ref<InboundStockPreview | null>(null)
const reason = ref('')
const note = ref('')
const imgs = ref<string[]>([])
const updatingImg = ref(false)
const result = ref<InboundDisputeResult | null>(null)

function openDispute(row: InboundRequest): void {
  active.value = row
  reason.value = ''
  note.value = ''
  imgs.value = []
  preview.value = null
  result.value = null
  disputeOpen.value = true
  detailOpen.value = false
  void waInboundApi
    .stockPreview(row.id)
    .then((p) => {
      preview.value = p
    })
    .catch(() => {
      /* 预览失败不阻塞表单 */
    })
}

function choosePhotos(): void {
  const remain = 5 - imgs.value.length
  if (remain <= 0) return
  uni.chooseImage({
    count: remain,
    sizeType: ['compressed'],
    success: (r) => {
      void uploadAll(Array.isArray(r.tempFilePaths) ? r.tempFilePaths : [r.tempFilePaths])
    },
  })
}
async function uploadAll(paths: string[]): Promise<void> {
  updatingImg.value = true
  try {
    for (const p of paths) {
      const url = await uploadImage(p)
      imgs.value.push(url)
      if (imgs.value.length >= 5) break
    }
  } catch {
    /* request 已 toast */
  } finally {
    updatingImg.value = false
  }
}
function removeImg(i: number): void {
  imgs.value.splice(i, 1)
}

async function doDispute(): Promise<void> {
  const row = active.value
  if (!row || submitting.value) return
  if (!reason.value) {
    uni.showToast({ title: '请选择异议原因', icon: 'none' })
    return
  }
  const shortfall = preview.value?.expectedShortfall ?? 0
  const confirmBody = () => {
    void waInboundApi
      .dispute(row.id, {
        reason: reason.value,
        ...(note.value.trim() ? { note: note.value.trim() } : {}),
        ...(imgs.value.length ? { attachments: imgs.value } : {}),
      })
      .then((res) => {
        result.value = res
        uni.showToast({ title: '已提交异议', icon: 'success' })
      })
      .catch(() => {
        // 超窗/并发 → 刷新回显
        void load(true)
      })
  }
  if (shortfall > 0) {
    uni.showModal({
      title: '差额进入定责',
      content: `预计冲销 ${preview.value?.expectedReversal ?? 0} 件，差额 ${shortfall} 件疑已销售，将移交平台仲裁定责。`,
      confirmText: '仍要异议',
      confirmColor: '#E5484D',
      success: (r) => {
        if (r.confirm) confirmBody()
      },
    })
    return
  }
  confirmBody()
}

function closeDispute(): void {
  disputeOpen.value = false
  result.value = null
}

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
  startTick()
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

// ===== 展示辅助 =====
const timeText = (v: string | null): string => (v ? fmtDateTime(v) : '—')
const moneyText = (n: number | null | undefined): string => (n == null ? '0' : String(n))
</script>

<template>
  <view class="page">
    <!-- 全局口径横幅 -->
    <view class="banner">仓库代建入库请在 72 小时内确认，超时自动接受；数量/质量/批次不符可异议，将冲销并交平台定责。</view>

    <!-- 页签 -->
    <view class="filters">
      <view
        v-for="f in [
          { key: 'pending', label: '待确认' },
          { key: 'all', label: '全部' },
        ] as Array<{ key: TabKey; label: string }>"
        :key="f.key"
        class="fchip"
        :class="{ 'fchip--on': tab === f.key }"
        @click="switchTab(f.key)"
      >
        {{ f.label }}
        <text v-if="f.key === 'pending' && pendingCount > 0" class="fchip__n">{{ pendingCount }}</text>
      </view>
    </view>

    <!-- 列表 -->
    <view v-if="loading && !rows.length" class="hint"><text class="hint__t">加载中…</text></view>
    <view v-else-if="!rows.length" class="hint">
      <text class="hint__t">{{ tab === 'pending' ? '没有待确认的入库单，仓库代建后会实时出现' : '暂无入库记录' }}</text>
    </view>

    <view v-else class="list">
      <view v-for="row in rows" :key="String(row.id)" class="card" @click="openDetail(row)">
        <view class="card__head">
          <text class="card__doc">{{ row.docNo }}</text>
          <view class="card__badges">
            <text v-if="row.source === 'WK_CREATED'" class="chip chip--done">仓库代建</text>
            <text class="chip" :class="statusMeta(row.status).cls">{{ statusMeta(row.status).text }}</text>
          </view>
        </view>
        <view class="card__main">
          <text class="card__name">{{ skuLabel(row.skuId) }}</text>
          <text class="card__qty">{{ row.qty }} 件</text>
        </view>
        <view v-if="row.batchNo" class="card__meta">批次 {{ row.batchNo }}<template v-if="row.expiryDate"> · 效期至 {{ row.expiryDate }}</template></view>
        <view v-if="row.palletQty != null && row.palletQty > 0" class="card__meta">托盘 {{ row.palletQty }}</view>
        <view class="card__sub">
          <text>{{ timeText(row.createdAt) }}</text>
          <text v-if="row.status === 'PENDING_WA_CONFIRM'" class="cd" :class="{ 'cd--danger': countdownDanger(row) }">
            {{ countdownText(row) }}
          </text>
          <text v-else-if="row.status === 'CONFIRMED' && row.autoAccepted === 1" class="card__auto">已超时自动接受</text>
          <text v-else-if="row.status === 'DISPUTED'" class="card__auto">已异议 · 移交平台</text>
        </view>
        <view v-if="row.status === 'PENDING_WA_CONFIRM'" class="card__foot">
          <view class="ops">
            <view class="ops__btn ops__btn--ghost" @click.stop="openDispute(row)">异议</view>
            <view class="ops__btn ops__btn--main" @click.stop="openDetail(row)">确认</view>
          </view>
        </view>
      </view>
      <view v-if="loading" class="more">加载中…</view>
      <view v-else-if="finished" class="more">没有更多了</view>
    </view>

    <!-- 详情 / 确认 sheet -->
    <view v-if="detailOpen && active" class="mask" @click="closeDetail">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">入库单 {{ active.docNo }}</text>
          <text class="sheet__close" @click="closeDetail">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view class="statusline">
            <text class="chip" :class="statusMeta(active.status).cls">{{ statusMeta(active.status).text }}</text>
            <text class="statusline__src">{{ sourceLabel(active.source) }}</text>
            <text v-if="active.status === 'CONFIRMED' && active.autoAccepted === 1" class="statusline__src">72h 超时自动接受</text>
          </view>

          <view class="info">
            <view class="info__row">
              <text class="info__k">商品</text>
              <text class="info__v">{{ skuLabel(active.skuId) }}</text>
            </view>
            <view class="info__row">
              <text class="info__k">登记件数</text>
              <text class="info__v">{{ active.qty }} 件</text>
            </view>
            <view v-if="active.palletQty != null && active.palletQty > 0" class="info__row">
              <text class="info__k">托盘</text>
              <text class="info__v">{{ active.palletQty }}</text>
            </view>
            <view v-if="active.location" class="info__row">
              <text class="info__k">货位</text>
              <text class="info__v">{{ active.location }}</text>
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
              <text class="info__k">登记备注</text>
              <text class="info__v">{{ active.remark }}</text>
            </view>
            <view class="info__row">
              <text class="info__k">登记时间</text>
              <text class="info__v">{{ timeText(active.createdAt) }}</text>
            </view>
            <view v-if="active.status === 'PENDING_WA_CONFIRM'" class="info__row">
              <text class="info__k">确认截止</text>
              <text class="info__v">{{ timeText(active.waConfirmDeadline) }}</text>
            </view>
            <view v-if="active.disputedAt" class="info__row">
              <text class="info__k">异议时间</text>
              <text class="info__v">{{ timeText(active.disputedAt) }}</text>
            </view>
          </view>

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
          <view v-else class="nophoto">本单无登记照片</view>

          <view v-if="active.status === 'PENDING_WA_CONFIRM'" class="ops-bar">
            <view class="ops-bar__ghost" @click="openDispute(active)">提出异议</view>
            <button class="ops-bar__main" :loading="submitting" @click="doConfirm">确认入库</button>
          </view>
          <view v-else-if="active.status === 'DISPUTED'" class="done-box">
            <text class="done-box__t">本单已异议并冲销，差额与责任由平台仲裁决定</text>
          </view>
          <view v-else class="done-box">
            <text class="done-box__t">该单已确认，无需再操作</text>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 异议 sheet -->
    <view v-if="disputeOpen && active" class="mask" @click="closeDispute">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <text class="sheet__title">异议 · {{ active.docNo }}</text>
          <text class="sheet__close" @click="closeDispute">✕</text>
        </view>
        <scroll-view scroll-y class="sheet__body">
          <view class="dispute__tip">
            登记 {{ active.qty }} 件 · 异议将即时冲销在库部分并通知平台复核；已售差额进入定责。
          </view>

          <view v-if="preview" class="pv">
            <view class="pv__cell">
              <text class="pv__n">{{ moneyText(preview.onhand) }}</text>
              <text class="pv__l">当前在库</text>
            </view>
            <view class="pv__cell">
              <text class="pv__n pv__n--rev">{{ moneyText(preview.expectedReversal) }}</text>
              <text class="pv__l">预计冲销</text>
            </view>
            <view class="pv__cell">
              <text class="pv__n" :class="{ 'pv__n--sf': (preview.expectedShortfall ?? 0) > 0 }">
                {{ moneyText(preview.expectedShortfall) }}
              </text>
              <text class="pv__l">预计差额</text>
            </view>
          </view>
          <view v-else class="dispute__tip">库存预估加载中…（冲销量以后台计算为准）</view>

          <view class="sec">异议原因</view>
          <view class="reasons">
            <view
              v-for="r in DISPUTE_REASONS"
              :key="r.code"
              class="reason"
              :class="{ 'reason--on': reason === r.code }"
              @click="reason = r.code"
            >
              {{ r.label }}
            </view>
          </view>

          <view class="sec">补充说明</view>
          <textarea
            v-model="note"
            class="ta"
            :maxlength="512"
            placeholder="货物实际情况、到货件数等（可空）"
            placeholder-class="ph"
          />

          <view class="sec">
            举证照片
            <text class="sec__cnt">{{ imgs.length }}/5</text>
          </view>
          <view class="photos">
            <view class="photos__row">
              <view v-for="(u, i) in imgs" :key="u" class="photos__box">
                <image class="photos__img" :src="u" mode="aspectFill" @click="previewImage(imgs, i)" />
                <view class="photos__del" @click="removeImg(i)">✕</view>
              </view>
              <view v-if="imgs.length < 5" class="photos__add" :class="{ 'photos__add--busy': updatingImg }" @click="choosePhotos">
                <text class="photos__add-t">{{ updatingImg ? '上传中…' : '+' }}</text>
              </view>
            </view>
          </view>

          <view class="ops-bar">
            <button class="ops-bar__main ops-bar__main--danger" :loading="submitting" @click="doDispute">提交异议</button>
          </view>
          <view class="sheet__tip2">提交后即时冲销并通知平台复核，可在全部页签跟踪状态</view>

          <!-- 异议结果 -->
          <view v-if="result" class="res">
            <view class="res__t">异议已受理</view>
            <view class="res__row">
              <text class="res__k">登记</text>
              <text class="res__v">{{ result.registeredQty }} 件</text>
            </view>
            <view class="res__row">
              <text class="res__k">已冲销</text>
              <text class="res__v">{{ result.reversedQty }} 件</text>
            </view>
            <view v-if="result.shortfallQty > 0" class="res__row">
              <text class="res__k">差额（进定责）</text>
              <text class="res__v">{{ result.shortfallQty }} 件</text>
            </view>
            <view class="res__row">
              <text class="res__k">仲裁单</text>
              <text class="res__v">{{ result.arbitrationDocNo }}</text>
            </view>
            <button class="ops-bar__main" @click="closeDispute">知道了</button>
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

  &__meta {
    margin-top: 10rpx;
    font-size: 22rpx;
    color: $cc-fg-3;
  }

  &__sub {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 12rpx;
    font-size: 22rpx;
    color: $cc-fg-4;
  }

  &__auto {
    font-size: 21rpx;
    color: $cc-fg-3;
  }

  &__foot {
    display: flex;
    justify-content: flex-end;
    margin-top: 16rpx;
  }
}

.cd {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: $cc-warning;

  &--danger {
    color: $cc-danger;
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
    color: $cc-fg-3;
  }

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }
}

.ops {
  display: flex;
  gap: 16rpx;

  &__btn {
    padding: 12rpx 40rpx;
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

  &__src {
    font-size: 23rpx;
    color: $cc-fg-3;
  }
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

.photos {
  margin-top: 16rpx;

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: 16rpx;
  }

  &__box {
    position: relative;
  }

  &__img {
    width: 150rpx;
    height: 150rpx;
    border-radius: 12rpx;
    background: $cc-bg-3;
  }

  &__del {
    position: absolute;
    top: -14rpx;
    right: -14rpx;
    width: 40rpx;
    height: 40rpx;
    line-height: 40rpx;
    text-align: center;
    border-radius: 999rpx;
    background: rgba(2, 6, 23, 0.7);
    color: #fff;
    font-size: 22rpx;
  }

  &__add {
    width: 150rpx;
    height: 150rpx;
    border-radius: 12rpx;
    border: 2rpx dashed $cc-border-2;
    display: flex;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;

    &--busy {
      opacity: 0.5;
    }

    &-t {
      font-size: 56rpx;
      color: $cc-fg-4;
      line-height: 1;
    }
  }
}

.nophoto {
  margin-top: 16rpx;
  padding: 20rpx;
  text-align: center;
  border-radius: 12rpx;
  background: $cc-bg-2;
  font-size: 22rpx;
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
    color: $cc-fg-2;
  }

  &__main {
    flex: 2;
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

.dispute__tip {
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: $cc-danger-bg;
  font-size: 23rpx;
  color: $cc-danger;
  line-height: 1.6;
}

.pv {
  display: flex;
  gap: 14rpx;
  margin-top: 18rpx;

  &__cell {
    flex: 1;
    padding: 18rpx 10rpx;
    border-radius: 12rpx;
    background: $cc-bg-2;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6rpx;
  }

  &__n {
    font-size: 32rpx;
    font-weight: 700;
    color: $cc-fg-1;

    &--rev {
      color: $cc-danger;
    }

    &--sf {
      color: $cc-warning;
    }
  }

  &__l {
    font-size: 20rpx;
    color: $cc-fg-4;
  }
}

.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.reason {
  padding: 12rpx 28rpx;
  border-radius: 999rpx;
  background: $cc-bg-1;
  border: 2rpx solid $cc-border-2;
  font-size: 24rpx;
  color: $cc-fg-2;

  &--on {
    background: $cc-danger;
    border-color: $cc-danger;
    color: #fff;
    font-weight: 600;
  }
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

.res {
  margin-top: 24rpx;
  padding: 24rpx 20rpx;
  border-radius: 14rpx;
  background: $cc-success-bg;

  &__t {
    font-size: 26rpx;
    font-weight: 700;
    color: $cc-success;
    margin-bottom: 10rpx;
  }

  &__row {
    display: flex;
    justify-content: space-between;
    padding: 8rpx 0;
  }

  &__k {
    font-size: 24rpx;
    color: $cc-fg-3;
  }

  &__v {
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }
}

.ph {
  color: $cc-fg-4;
}
</style>
