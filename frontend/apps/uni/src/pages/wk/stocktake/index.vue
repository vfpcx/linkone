<script setup lang="ts">
// WK 库存盘点（F5-W2 · US-WK-03）：盘点单全链（13 §5.2 / api-types CountSheet）。
// 建草稿 → 保存(create/update) → 提交(submit 定格 systemQty → TA 审批 → GAIN/LOSS 封顶生效)。
// 移动端范围：按 SKU 总数盘（批次分支盘盈批次归属等随 P5/方案 A 顺延，仅差异行录托盘覆盖值可选）。
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import type {
  CountSheet,
  CountSheetItem,
  InventoryItem,
  Sku,
  SnowflakeId,
  StocktakeInTransitHint,
  Wholesaler,
} from '@cangchu/api-types'
import { wkApi } from '../../../api/wk'
import { readAuth, readWork } from '../../../utils/session'
import { fmtDateTime, STOCKTAKE_STATUS_LABELS, stocktakeTone } from '../../../utils/warehouse'

const SEGMENTS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_APPROVAL', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

interface EdRow {
  skuId: SnowflakeId
  name: string
  system: number
  actual: string
  remark: string
}

const loading = ref(false)
const allSheets = ref<CountSheet[]>([])
const seg = ref('DRAFT')
const whNameOf = new Map<string, string>()
const merchants = ref<Wholesaler[]>([])

// 详情只读
const view = ref<CountSheet | null>(null)

// 编辑器
const editing = ref(false)
const editId = ref<SnowflakeId | null>(null) // 已有单（草稿/驳回重提）
const editWh = ref<SnowflakeId | null>(null)
const editWhName = ref('')
const hint = ref<StocktakeInTransitHint | null>(null)
const edRows = ref<EdRow[]>([])
const submitting = ref(false)

const shown = computed(() => {
  if (!seg.value) return allSheets.value
  return allSheets.value.filter((s) => s.status === seg.value)
})

const segmentCount = (v: string): number => allSheets.value.filter((s) => s.status === v).length

const diffSummary = computed(() => {
  const gains = edRows.value.filter((r) => diffOf(r) > 0)
  const loss = edRows.value.filter((r) => diffOf(r) < 0)
  return {
    gains: gains.reduce((a, r) => a + diffOf(r), 0),
    loss: loss.reduce((a, r) => a + diffOf(r), 0),
    rows: edRows.value.length,
  }
})

function diffOf(r: EdRow): number {
  const n = Number(r.actual)
  return Number.isFinite(n) ? n - r.system : NaN
}

function diffText(it: CountSheetItem): string {
  const d = it.diff ?? 0
  const base = d > 0 ? `+${d}` : String(d)
  if (it.appliedDiff != null && it.appliedDiff !== d) return `${base} / 生效${it.appliedDiff}`
  return base
}

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

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const [sheets, ws] = await Promise.all([wkApi.stocktake.list(), wkApi.listWholesalers()])
    allSheets.value = sheets
    merchants.value = ws
    whNameOf.clear()
    ws.forEach((w: Wholesaler) => whNameOf.set(String(w.id), w.name))
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function openView(s: CountSheet): void {
  view.value = s
  if (!s.items) {
    void wkApi.stocktake
      .detail(s.id)
      .then((d) => {
        view.value = d
      })
      .catch(() => undefined)
  }
}

function closeView(): void {
  view.value = null
}

async function openEditor(s?: CountSheet): Promise<void> {
  editing.value = true
  view.value = null
  editId.value = s ? s.id : null
  editWh.value = s ? s.wholesalerId : null
  editWhName.value = s ? (s.wholesalerName ?? whNameOf.get(String(s.wholesalerId)) ?? '') : ''
  edRows.value = []
  hint.value = null
  if (s) {
    await loadEditorExisting(s.id)
  }
}

async function loadEditorExisting(id: SnowflakeId): Promise<void> {
  loading.value = true
  try {
    const d = await wkApi.stocktake.detail(id)
    view.value = d
    editWhName.value = d.wholesalerName ?? editWhName.value
    const items = d.items ?? []
    edRows.value = items.map((it: CountSheetItem) => ({
      skuId: it.skuId,
      name: it.skuName ?? 'SKU ' + it.skuId,
      system: it.systemQty,
      actual: String(it.actualQty),
      remark: it.remark ?? '',
    }))
    hint.value = d.inTransitHint
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

async function pickWh(w: Wholesaler): Promise<void> {
  editWh.value = w.id
  editWhName.value = w.name
  edRows.value = []
  hint.value = null
  loading.value = true
  try {
    const [h, inv, skus] = await Promise.all([
      wkApi.stocktake.inTransitHint(w.id),
      wkApi.listInventories(w.id),
      wkApi.listSkuByWholesaler(w.id),
    ])
    hint.value = h
    const skuMap = new Map(skus.map((s) => [String(s.id), s]))
    edRows.value = inv.map((it: InventoryItem) => {
      const sku = skuMap.get(String(it.skuId))
      return {
        skuId: it.skuId,
        name: sku ? labelOf(sku) : 'SKU ' + it.skuId,
        system: it.qty,
        actual: String(it.qty),
        remark: '',
      }
    })
  } catch {
    /* request 已 toast */
  } finally {
    loading.value = false
  }
}

function closeEditor(): void {
  if (submitting.value) return
  editing.value = false
  editId.value = null
  editWh.value = null
  edRows.value = []
  hint.value = null
}

function backfillEmptyActual(): void {
  edRows.value.forEach((r) => {
    if (r.actual.trim() === '') r.actual = String(r.system)
  })
}

function buildItems(): Array<{ skuId: SnowflakeId; actualQty: number; remark?: string }> {
  backfillEmptyActual()
  const items: Array<{ skuId: SnowflakeId; actualQty: number; remark?: string }> = []
  for (const r of edRows.value) {
    const n = Number(r.actual)
    if (!Number.isFinite(n) || n < 0) {
      throw new Error(`「${r.name}」实盘件数不正确`)
    }
    const item: { skuId: SnowflakeId; actualQty: number; remark?: string } = { skuId: r.skuId, actualQty: n }
    if (r.remark.trim()) item.remark = r.remark.trim()
    items.push(item)
  }
  if (!items.length) throw new Error('至少需要一行盘点明细')
  return items
}

async function save(andSubmit: boolean): Promise<void> {
  if (submitting.value) return
  if (!editWh.value) {
    uni.showToast({ title: '请先选择被盘商户', icon: 'none' })
    return
  }
  let items: Array<{ skuId: SnowflakeId; actualQty: number; remark?: string }>
  try {
    items = buildItems()
  } catch (err) {
    uni.showToast({ title: err instanceof Error ? err.message : '明细不正确', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    let sheetId: SnowflakeId
    if (editId.value) {
      await wkApi.stocktake.update(editId.value, { items })
      sheetId = editId.value
    } else {
      const created = await wkApi.stocktake.create({ wholesalerId: editWh.value, items })
      sheetId = created.id
      editId.value = created.id
    }
    if (andSubmit) {
      await wkApi.stocktake.submit(sheetId)
      uni.showToast({ title: '已提交，等待租户管理员审批', icon: 'success' })
      seg.value = 'PENDING_APPROVAL'
      closeEditor()
    } else {
      uni.showToast({ title: '草稿已保存', icon: 'none' })
      seg.value = 'DRAFT'
      closeEditor()
    }
    await loadAll()
  } catch {
    // request 已 toast；create 成功但 submit 失败的草稿已留存（editId 已置），可回列表续提
  } finally {
    submitting.value = false
  }
}

function confirmSubmit(): void {
  uni.showModal({
    title: '提交盘点单',
    content: '提交后明细账面数将定格并通知租户管理员审批；盘亏按审批时刻剩余在库封顶生效。',
    confirmText: '提交',
    success: (r) => {
      if (r.confirm) void save(true)
    },
  })
}

function confirmDeleteDraft(): void {
  if (!editId.value) return
  uni.showModal({
    title: '删除盘点草稿',
    content: '删除后不可恢复（仅草稿可删除）。',
    confirmText: '删除',
    success: (r) => {
      if (!r.confirm) return
      void (async () => {
        try {
          await wkApi.stocktake.remove(editId.value as SnowflakeId)
          uni.showToast({ title: '草稿已删除', icon: 'none' })
          closeEditor()
          await loadAll()
        } catch {
          /* request 已 toast */
        }
      })()
    },
  })
}

function submitDraft(s: CountSheet): void {
  uni.showModal({
    title: '提交盘点单',
    content: `「${whNameOf.get(String(s.wholesalerId)) ?? ''}」盘盈/盘亏将提交审批，确认？`,
    confirmText: '提交',
    success: (r) => {
      if (!r.confirm) return
      void (async () => {
        try {
          await wkApi.stocktake.submit(s.id)
          uni.showToast({ title: '已提交', icon: 'none' })
          await loadAll()
        } catch {
          /* request 已 toast */
        }
      })()
    },
  })
}

function deleteDraft(s: CountSheet): void {
  uni.showModal({
    title: '删除盘点草稿',
    content: `删除「${s.docNo}」？不可恢复。`,
    confirmText: '删除',
    success: (r) => {
      if (!r.confirm) return
      void (async () => {
        try {
          await wkApi.stocktake.remove(s.id)
          uni.showToast({ title: '已删除', icon: 'none' })
          await loadAll()
        } catch {
          /* request 已 toast */
        }
      })()
    },
  })
}

function openNew(): void {
  if (merchants.value.length === 0) {
    uni.showToast({ title: '暂无商户', icon: 'none' })
    return
  }
  editing.value = true
  view.value = null
  editId.value = null
  editWh.value = null
  editWhName.value = ''
  edRows.value = []
  hint.value = null
}

onLoad(() => {
  if (!guard()) {
    goLogin()
    return
  }
  void loadAll()
})

onShow(() => {
  if (guard() && !editing.value && !view.value) void loadAll()
})

onPullDownRefresh(async () => {
  await loadAll()
  uni.stopPullDownRefresh()
})
</script>

<template>
  <view class="page">
    <!-- 段 -->
    <view v-if="!editing" class="seg">
      <scroll-view scroll-x show-scrollbar="false">
        <view
          v-for="s in SEGMENTS"
          :key="s.value"
          class="seg__item"
          :class="{ 'seg__item--on': seg === s.value }"
          @click="seg = s.value"
        >{{ s.label }} <text class="seg__count">{{ segmentCount(s.value) }}</text></view>
      </scroll-view>
    </view>

    <!-- 列表 -->
    <template v-if="!editing">
      <view v-if="seg === 'DRAFT'" class="btn-new" @click="openNew">＋ 新建盘点（实物盘数 → 提交 TA 审批）</view>
      <view v-if="shown.length" class="list">
        <view v-for="s in shown" :key="String(s.id)" class="card">
          <view class="card__top">
            <view class="card__main" @click="openView(s)">
              <text class="card__no">{{ s.docNo }}</text>
              <text class="card__ws">{{ s.wholesalerName ?? whNameOf.get(String(s.wholesalerId)) ?? '商户' }}</text>
            </view>
            <view class="card__right">
              <text class="chip" :class="stocktakeTone(s.status) === 'ok' ? 'chip--ok' : stocktakeTone(s.status) === 'err' ? 'chip--err' : 'chip--warn'">
                {{ STOCKTAKE_STATUS_LABELS[s.status] ?? s.status }}
              </text>
            </view>
          </view>
          <view class="card__meta" @click="openView(s)">
            <text class="card__time">{{ fmtDateTime(s.createdAt) }}</text>
            <text v-if="s.decidedAt" class="card__time">审批 {{ fmtDateTime(s.decidedAt) }}</text>
            <text v-if="s.status === 'REJECTED' && s.rejectRemark" class="card__reject">驳回：{{ s.rejectRemark }}</text>
          </view>
          <view v-if="s.status === 'DRAFT'" class="card__ops">
            <view class="card__op" @click="openEditor(s)">继续盘点</view>
            <view class="card__op" @click="deleteDraft(s)">删除</view>
            <view class="card__op card__op--primary" @click="submitDraft(s)">提交审批</view>
          </view>
          <view v-else-if="s.status === 'REJECTED'" class="card__ops">
            <view class="card__op" @click="openView(s)">查看</view>
            <view class="card__op card__op--primary" @click="openEditor(s)">修正后重提</view>
          </view>
        </view>
      </view>
      <view v-else-if="!loading" class="empty">暂无盘点单</view>
    </template>

    <!-- 盘点录入 -->
    <view v-else class="editor">
      <view class="editor__head">
        <text class="editor__title">{{ editId ? `盘点单（${editWhName || '本仓'}）` : '新建盘点单' }}</text>
        <view v-if="editId && seg === 'DRAFT'" class="editor__del" @click="confirmDeleteDraft">删除草稿</view>
      </view>

      <view v-if="!editId && !editWh" class="sec">
        <view class="sec__label">1 · 选择被盘商户</view>
        <scroll-view v-if="merchants.length" scroll-x class="chips" show-scrollbar="false">
          <view
            v-for="w in merchants"
            :key="String(w.id)"
            class="chip"
            :class="{ 'chip--on': String(w.id) === String(editWh) }"
            @click="pickWh(w)"
          >{{ w.name }}</view>
        </scroll-view>
      </view>

      <template v-if="editWh">
        <view class="sec">
          <view class="sec__label">2 · 账面与在途核对（盘全部 SKU 总数）</view>
          <view v-if="hint && (hint.outboundQtyTotal > 0 || hint.returnQtyTotal > 0)" class="hintbar">
            <text class="hintbar__t">盘点护栏：当前 {{ hint.outboundDocCount }} 张已确认未出库单合计 {{ hint.outboundQtyTotal }} 件、{{ hint.returnDocCount }} 张在途退货合计 {{ hint.returnQtyTotal }} 件（账面含、实物或未在仓）——实盘数允许小于账面，属正常现象。</text>
          </view>
          <view v-else class="hintbar hintbar--ok"><text class="hintbar__t">无在途出库/退货，账面即仓内实物应数。</text></view>

          <view class="diffbar">
            <text class="diffbar__item">盘盈合计 <text class="diffbar__num diffbar__num--gain">+{{ diffSummary.gains }}</text></text>
            <text class="diffbar__item">盘亏合计 <text class="diffbar__num diffbar__num--loss">{{ diffSummary.loss }}</text></text>
            <text class="diffbar__item">{{ diffSummary.rows }} 个 SKU</text>
          </view>

          <view class="rows">
            <view v-for="(r, i) in edRows" :key="String(r.skuId)" class="row">
              <view class="row__info">
                <text class="row__name">{{ r.name }}</text>
                <text class="row__sys">账面 {{ r.system }} 件</text>
              </view>
              <view class="row__edit">
                <input
                  v-model="r.actual"
                  class="row__input"
                  type="number"
                  placeholder="实盘"
                  placeholder-class="ph"
                  @blur="backfillEmptyActual"
                />
                <view class="row__diff" :class="diffOf(r) > 0 ? 'row__diff--gain' : diffOf(r) < 0 ? 'row__diff--loss' : ''">
                  <text v-if="Number.isFinite(diffOf(r)) && diffOf(r) !== 0">{{ diffOf(r) > 0 ? `+${diffOf(r)}` : diffOf(r) }}</text>
                  <text v-else-if="Number.isFinite(diffOf(r))">平</text>
                  <text v-else>—</text>
                </view>
              </view>
              <input v-model="r.remark" class="row__remark" type="text" placeholder="差异理由（可空）" placeholder-class="ph" maxlength="100" />
            </view>
          </view>
        </view>

        <view class="editor__foot">
          <button class="btn-ghost editor__btn" :disabled="submitting" @click="save(false)">保存草稿</button>
          <button class="btn-primary editor__btn" :disabled="submitting" :loading="submitting" @click="confirmSubmit">提交并上报</button>
        </view>
      </template>
    </view>

    <!-- 只读详情弹层 -->
    <view v-if="view" class="mask" @click="closeView">
      <view class="sheet" @click.stop>
        <view class="sheet__head">
          <view class="sheet__titles">
            <text class="sheet__no">{{ view.docNo }}</text>
            <text class="chip" :class="stocktakeTone(view.status) === 'ok' ? 'chip--ok' : stocktakeTone(view.status) === 'err' ? 'chip--err' : 'chip--warn'">{{ STOCKTAKE_STATUS_LABELS[view.status] ?? view.status }}</text>
          </view>
          <text class="sheet__ws">{{ view.wholesalerName ?? whNameOf.get(String(view.wholesalerId)) ?? '' }}</text>
        </view>

        <view v-if="view.rejectRemark" class="sheet__reject">驳回：{{ view.rejectRemark }}</view>

        <view v-if="view.inTransitHint && (view.inTransitHint.outboundQtyTotal > 0 || view.inTransitHint.returnQtyTotal > 0)" class="sheet__hint">
          在途护栏：{{ view.inTransitHint.outboundDocCount }} 张出库单 {{ view.inTransitHint.outboundQtyTotal }} 件 / 在途退货 {{ view.inTransitHint.returnDocCount }} 张 {{ view.inTransitHint.returnQtyTotal }} 件
        </view>

        <view class="items">
          <view class="items__head"><text class="col col--name">货品</text><text class="col col--num">账面</text><text class="col col--num">实盘</text><text class="col col--num">差异</text></view>
          <view v-for="it in view.items ?? []" :key="String(it.id)" class="items__row">
            <view class="col col--name">
              <text class="items__sku">{{ it.skuName ?? 'SKU ' + it.skuId }}</text>
              <text v-if="it.remark" class="items__rmk">{{ it.remark }}</text>
            </view>
            <text class="col col--num">{{ it.systemQty }}</text>
            <text class="col col--num">{{ it.actualQty }}</text>
            <text class="col col--num" :class="(it.diff ?? 0) > 0 ? 'c--gain' : (it.diff ?? 0) < 0 ? 'c--loss' : 'c--flat'">{{ diffText(it) }}</text>
          </view>
        </view>
        <view v-if="view.status === 'APPROVED'" class="sheet__note">已通过：盘盈按 diff 生效入库、盘亏按审批时刻在库封顶（实际生效见 appliedDiff 带符号值）。</view>
        <view v-else-if="view.status === 'PENDING_APPROVAL'" class="sheet__note">已提交待审批，账面数已定格；审批通过后库存与计费联动生效。</view>

        <view class="sheet__close" @click="closeView">关闭</view>
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

.seg {
  white-space: nowrap;

  &__item {
    display: inline-block;
    padding: 12rpx 22rpx;
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

      .seg__count {
        color: rgba(255, 255, 255, 0.85);
      }
    }
  }

  &__count {
    font-size: 20rpx;
    color: $cc-fg-4;
  }
}

.btn-new {
  margin: 18rpx 0;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  border-radius: 14rpx;
  background: linear-gradient(135deg, #0f766e 0%, #134e4a 100%);
  color: #fff;
  font-size: 27rpx;
  font-weight: 600;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  margin-top: 18rpx;
}

.card {
  padding: 18rpx 20rpx;
  border-radius: $cc-card-radius;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 10rpx rgba(2, 6, 23, 0.04);

  &__top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12rpx;
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4rpx;
  }

  &__no {
    font-size: 28rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__ws {
    font-size: 23rpx;
    color: $cc-fg-4;
  }

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8rpx 18rpx;
    margin: 12rpx 0 4rpx;
  }

  &__time {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__reject {
    font-size: 21rpx;
    color: $cc-danger;
  }

  &__ops {
    display: flex;
    gap: 14rpx;
    margin-top: 14rpx;
  }

  &__op {
    flex: 1;
    height: 64rpx;
    line-height: 64rpx;
    text-align: center;
    border-radius: 10rpx;
    font-size: 24rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;

    &--primary {
      background: $cc-brand-primary;
      color: #fff;
      font-weight: 600;
    }
  }
}

.chip {
  padding: 4rpx 12rpx;
  border-radius: 999rpx;
  font-size: 20rpx;
  flex-shrink: 0;

  &--ok {
    background: $cc-success-bg;
    color: $cc-success;
  }

  &--err {
    background: $cc-danger-bg;
    color: $cc-danger;
  }

  &--warn {
    background: $cc-warning-bg;
    color: $cc-warning;
  }
}

.editor {
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16rpx;
  }

  &__title {
    font-size: 29rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__del {
    font-size: 23rpx;
    color: $cc-danger;
  }

  &__foot {
    display: flex;
    gap: 16rpx;
    margin-top: 26rpx;
  }

  &__btn {
    flex: 1;
    height: 92rpx;
    font-size: 29rpx;
    font-weight: 600;
  }
}

.sec {
  &__label {
    font-size: 24rpx;
    font-weight: 700;
    color: $cc-fg-2;
    margin-bottom: 12rpx;
  }
}

.chips {
  white-space: nowrap;
  margin-bottom: 20rpx;
}

.chip {
  display: inline-block;
  padding: 12rpx 24rpx;
  margin-right: 10rpx;
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

.hintbar {
  padding: 16rpx 18rpx;
  border-radius: 10rpx;
  background: $cc-warning-bg;
  margin-bottom: 12rpx;

  &--ok {
    background: $cc-success-bg;
  }

  &__t {
    font-size: 22rpx;
    color: $cc-warning;
    line-height: 1.6;

    .hintbar--ok & {
      color: $cc-success;
    }
  }
}

.diffbar {
  display: flex;
  gap: 24rpx;
  padding: 12rpx 2rpx;
  font-size: 23rpx;
  color: $cc-fg-3;
  margin-bottom: 6rpx;

  &__num {
    font-weight: 800;

    &--gain {
      color: $cc-success;
    }

    &--loss {
      color: $cc-danger;
    }
  }
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.row {
  padding: 16rpx 18rpx;
  border-radius: 12rpx;
  background: $cc-bg-1;
  box-shadow: 0 2rpx 8rpx rgba(2, 6, 23, 0.03);

  &__info {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 12rpx;
  }

  &__name {
    font-size: 25rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__sys {
    font-size: 21rpx;
    color: $cc-fg-4;
  }

  &__edit {
    display: flex;
    align-items: center;
    gap: 12rpx;
    margin-top: 10rpx;
  }

  &__input {
    flex: 1;
    height: 70rpx;
    padding: 0 18rpx;
    border: 2rpx solid $cc-border-1;
    border-radius: 10rpx;
    background: $cc-bg-2;
    font-size: 27rpx;
    font-weight: 700;
    color: $cc-fg-1;
  }

  &__diff {
    width: 100rpx;
    text-align: right;
    font-size: 27rpx;
    font-weight: 800;
    color: $cc-fg-4;

    &--gain {
      color: $cc-success;
    }

    &--loss {
      color: $cc-danger;
    }
  }

  &__remark {
    margin-top: 8rpx;
    height: 64rpx;
    padding: 0 14rpx;
    border-radius: 10rpx;
    background: $cc-bg-2;
    font-size: 22rpx;
    color: $cc-fg-2;
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
  max-height: 84vh;
  overflow-y: auto;
  padding: 30rpx 28rpx 24rpx;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  border-radius: 28rpx 28rpx 0 0;
  background: $cc-bg-1;
  box-sizing: border-box;

  &__head {
    display: flex;
    flex-direction: column;
    gap: 6rpx;
    margin-bottom: 14rpx;
  }

  &__titles {
    display: flex;
    align-items: center;
    gap: 12rpx;
  }

  &__no {
    font-size: 32rpx;
    font-weight: 800;
    color: $cc-fg-1;
  }

  &__ws {
    font-size: 23rpx;
    color: $cc-fg-4;
  }

  &__reject {
    padding: 14rpx 16rpx;
    border-radius: 10rpx;
    background: $cc-danger-bg;
    color: $cc-danger;
    font-size: 22rpx;
    margin-bottom: 12rpx;
  }

  &__hint {
    padding: 12rpx 14rpx;
    border-radius: 10rpx;
    background: $cc-warning-bg;
    color: $cc-warning;
    font-size: 21rpx;
    margin-bottom: 12rpx;
  }

  &__note {
    margin-top: 14rpx;
    font-size: 21rpx;
    color: $cc-fg-4;
    line-height: 1.6;
  }

  &__close {
    margin-top: 22rpx;
    height: 84rpx;
    line-height: 84rpx;
    text-align: center;
    border-radius: 14rpx;
    background: $cc-bg-3;
    color: $cc-fg-2;
    font-size: 28rpx;
  }
}

.items {
  display: flex;
  flex-direction: column;
  gap: 8rpx;

  &__head {
    display: flex;
    padding: 10rpx 0;
    border-bottom: 2rpx solid $cc-bg-2;
  }

  &__row {
    display: flex;
    align-items: flex-start;
    padding: 12rpx 0;
    border-bottom: 2rpx solid $cc-bg-3;
  }

  &__sku {
    font-size: 24rpx;
    font-weight: 600;
    color: $cc-fg-1;
  }

  &__rmk {
    display: block;
    margin-top: 4rpx;
    font-size: 20rpx;
    color: $cc-fg-4;
    line-height: 1.5;
  }
}

.col {
  font-size: 23rpx;
  color: $cc-fg-2;

  &--name {
    flex: 1;
    padding-right: 12rpx;
  }

  &--num {
    width: 108rpx;
    text-align: center;
    flex-shrink: 0;
  }
}

.c--gain {
  color: $cc-success;
  font-weight: 800;
}

.c--loss {
  color: $cc-danger;
  font-weight: 800;
}

.c--flat {
  color: $cc-fg-4;
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
