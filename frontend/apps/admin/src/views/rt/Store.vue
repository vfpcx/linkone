<script setup lang="ts">
/**
 * RT 扫码进店 H5（phase-1 B2/C2 · 移动优先 · 公开无需登录）
 *
 * 来源：
 *  - 契约：backend/.../storefront/controller/RtStoreController.java  GET /api/v1/rt/store?code=
 *          backend/.../document/controller/RtInquiryController.java  POST /api/v1/rt/inquiry
 *  - 类型：@cangchu/api-types  StoreFront / SubmitInquiryRequest / Inquiry
 *  - api：@/api/rt  rtApi.getStore / rtApi.submitInquiry
 *
 * 范围（最小可验证）：进店（?code= 或 ?storeId=/?wholesalerId 略）浏览店内批发商 + 在售 SKU
 *   （名称/规格/单价/起批价/起批量/库存）→ 每 SKU 填数量加入询价 → 底部填手机号 → 提交拿单号。
 * 移动优先布局，不套 TA 后台 shell。归属/tenantId 由后端 code→store→tenant 解析，前端不传。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type {
  RtPriceGroup,
  RtPriceItem,
  RtPriceList,
  RtStoreFront,
  RtStoreSku,
  RtStoreWholesaler,
  SubmitInquiryRequest,
} from '@cangchu/api-types'
import { rtApi } from '@/api/rt'

const route = useRoute()

/** 进店码：优先 query.code，兼容 path 参数 :code */
const storeCode = computed<string>(() => {
  const q = route.query.code
  const p = route.params.code
  const raw = (Array.isArray(q) ? q[0] : q) ?? (Array.isArray(p) ? p[0] : p) ?? ''
  return String(raw).trim()
})

const loading = ref(false)
const loadError = ref('')
const store = ref<RtStoreFront | null>(null)

// ============ 撮合出参消费（P5-A W4 · 18-p5-design §4.4） ============
/** 置顶批发商 id 集（featuredSkuIds/pinnedWholesalerIds 为服务端排序权威） */
const pinnedIds = computed<Set<string>>(
  () => new Set((store.value?.pinnedWholesalerIds ?? []).map(String)),
)
const featuredSkuIds = computed<Set<string>>(
  () => new Set((store.value?.featuredSkuIds ?? []).map(String)),
)

/** 置顶批发商前置；同一级内保持服务端顺序 */
const orderedWholesalers = computed(() => {
  const ws = store.value?.wholesalers ?? []
  if (pinnedIds.value.size === 0) return ws
  const pinned = ws.filter((w) => pinnedIds.value.has(String(w.wholesalerId)))
  const rest = ws.filter((w) => !pinnedIds.value.has(String(w.wholesalerId)))
  return [...pinned, ...rest]
})

/** 主推商品前置（同一批发商内） */
const orderedSkus = (w: RtStoreWholesaler): RtStoreSku[] => {
  const skus = w?.skus ?? []
  if (featuredSkuIds.value.size === 0) return skus
  const featured = skus.filter((s) => featuredSkuIds.value.has(String(s.skuId)))
  const rest = skus.filter((s) => !featuredSkuIds.value.has(String(s.skuId)))
  return [...featured, ...rest]
}

const isFeaturedSku = (skuId: string) => featuredSkuIds.value.has(String(skuId))
const isPinnedWholesaler = (wholesalerId: string) => pinnedIds.value.has(String(wholesalerId))

/** 询价数量草稿：skuId -> qty（>0 视为加入询价） */
const qtyMap = reactive<Record<string, number>>({})

const rtPhone = ref('')
const submitting = ref(false)

// ============ C1 我的价目（专属价复购，architecture/23-p5-c-c1） ============
const priceListLoading = ref(false)
const priceList = ref<RtPriceList | null>(null)
const priceSheetOpen = ref(false)

/** 成功后展示的单号（docNo），非空即显示成功态 */
const submittedDocNo = ref('')

// ============ 进店加载 ============
async function loadStore() {
  if (!storeCode.value) {
    loadError.value = '缺少进店码，请扫描店铺二维码进入'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    store.value = await rtApi.getStore(storeCode.value)
  } catch {
    // http 拦截器已 toast；此处置错误态
    loadError.value = '进店失败，请稍后重试或确认店铺码是否正确'
  } finally {
    loading.value = false
  }
}

onMounted(loadStore)

// ============ 询价草稿 ============
function stepQty(skuId: string, delta: number, max: number) {
  const cur = qtyMap[skuId] ?? 0
  let next = cur + delta
  if (next < 0) next = 0
  if (next > max) next = max
  qtyMap[skuId] = next
}

function onQtyInput(skuId: string, val: string | number, max: number) {
  let n = Math.floor(Number(val))
  if (!Number.isFinite(n) || n < 0) n = 0
  if (n > max) n = max
  qtyMap[skuId] = n
}

/**
 * 当前有数量(>0)的明细，按批发商聚合（浏览在售 + C1 价目勾选的并集，同 sku 去重）。
 * 价目行可能含浏览外 SKU（缺货/下架前已设专属价的可询商品），并入同组后
 * 底部提交与抽屉内组提交都拿到完整明细。提交仍沿用一次仅一个批发商约束。
 */
const selectedByWholesaler = computed(() => {
  const groups: Record<string, { wholesalerId: string; name: string; items: Array<{ skuId: string; qty: number }> }> = {}
  const qtyBySku: Record<string, Record<string, number>> = {}
  if (!store.value) return groups
  const ensure = (wholesalerId: string, name: string) => {
    if (!qtyBySku[wholesalerId]) qtyBySku[wholesalerId] = {}
    if (!groups[wholesalerId]) groups[wholesalerId] = { wholesalerId, name, items: [] }
  }
  for (const w of store.value.wholesalers) {
    for (const s of w.skus) {
      const qty = qtyMap[s.skuId] ?? 0
      if (qty > 0) {
        ensure(w.wholesalerId, w.name)
        qtyBySku[w.wholesalerId][s.skuId] = qty
      }
    }
  }
  for (const g of priceList.value?.wholesalers ?? []) {
    for (const i of g.items) {
      const qty = qtyMap[i.skuId] ?? 0
      if (i.listed && qty > 0) {
        ensure(g.wholesalerId, g.name)
        qtyBySku[g.wholesalerId][i.skuId] = qty
      }
    }
  }
  for (const [wholesalerId, skuQty] of Object.entries(qtyBySku)) {
    groups[wholesalerId].items = Object.entries(skuQty).map(([skuId, qty]) => ({ skuId, qty }))
  }
  return groups
})

const selectedGroupList = computed(() => Object.values(selectedByWholesaler.value))
const selectedCount = computed(() =>
  selectedGroupList.value.reduce((sum, g) => sum + g.items.length, 0),
)

const PHONE_RE = /^1\d{10}$/

// ============ 提交询价（浏览底部提交 + C1 价目抽屉提交共用链路） ============
/** 执行提交（payload 已就绪）；成功后进入成功态，返回是否成功。 */
async function doSubmit(group: {
  wholesalerId: string
  name: string
  items: Array<{ skuId: string; qty: number }>
}): Promise<boolean> {
  if (submitting.value) return false
  if (!group.items.length) {
    ElMessage.warning('请先为至少一个商品填写数量')
    return false
  }
  if (!PHONE_RE.test(rtPhone.value.trim())) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return false
  }
  const payload: SubmitInquiryRequest = {
    code: storeCode.value,
    wholesalerId: group.wholesalerId,
    rtPhone: rtPhone.value.trim(),
    items: group.items,
  }
  submitting.value = true
  try {
    const res = await rtApi.submitInquiry(payload)
    submittedDocNo.value = res.docNo || res.id
    ElMessage.success('询价提交成功')
    return true
  } catch {
    // http 拦截器已 toast 具体错误码
    return false
  } finally {
    submitting.value = false
  }
}

async function submit() {
  const groups = selectedGroupList.value
  if (groups.length === 0) {
    ElMessage.warning('请先为至少一个商品填写数量')
    return
  }
  if (groups.length > 1) {
    ElMessage.warning('一次仅能向一个批发商提交询价，请分别提交')
    return
  }
  await doSubmit(groups[0])
}

// ============ C1 我的价目（专属价复购，architecture/23-p5-c-c1） ============

/** 打开「我的价目」抽屉：拉取当前店为该手机号维护的客户专属价目 */
async function openPriceList() {
  const phone = rtPhone.value.trim()
  if (!PHONE_RE.test(phone)) {
    ElMessage.warning('请输入正确的 11 位手机号后再查看专属价目')
    return
  }
  priceListLoading.value = true
  try {
    priceList.value = await rtApi.getMyPriceList({ code: storeCode.value, rtPhone: phone })
    priceSheetOpen.value = true
  } catch {
    // http 拦截器已 toast 具体错误码
  } finally {
    priceListLoading.value = false
  }
}

/** 价目行步进上限：有库存按库存；0 库存允许缺货询（放开上限） */
function priceStepMax(item: RtPriceItem) {
  return item.stockQty > 0 ? item.stockQty : 99999
}

function priceQty(item: RtPriceItem) {
  return qtyMap[item.skuId] ?? 0
}

function stepPriceQty(item: RtPriceItem, delta: number) {
  stepQty(item.skuId, delta, priceStepMax(item))
}

function onPriceQtyInput(item: RtPriceItem, val: string | number) {
  onQtyInput(item.skuId, val, priceStepMax(item))
}

/** 组内可提交明细（listed 且数量>0） */
function priceGroupItems(g: RtPriceGroup) {
  return g.items
    .filter((i) => i.listed && (qtyMap[i.skuId] ?? 0) > 0)
    .map((i) => ({ skuId: i.skuId, qty: qtyMap[i.skuId] ?? 0 }))
}

function priceGroupCount(g: RtPriceGroup) {
  return priceGroupItems(g).length
}

/** 从价目抽屉直接提交某批发商组询价（复用 doSubmit 链路与成功态） */
async function submitPriceGroup(g: RtPriceGroup) {
  const items = priceGroupItems(g)
  if (items.length === 0) {
    ElMessage.warning('请先在该批发商价目下为商品填写数量')
    return
  }
  const ok = await doSubmit({ wholesalerId: g.wholesalerId, name: g.name, items })
  if (ok) {
    priceSheetOpen.value = false
  }
}

/** 展示用日期：截取 YYYY-MM-DD */
function fmtDate(v: string) {
  return v.slice(0, 10)
}

/** 再来一单：清空草稿回到浏览态 */
function resetForNext() {
  submittedDocNo.value = ''
  for (const k of Object.keys(qtyMap)) delete qtyMap[k]
}
</script>

<template>
  <div class="rt-page">
    <!-- 顶部店铺名 -->
    <header class="rt-header">
      <div class="rt-header__title">{{ store?.storeName || '店铺' }}</div>
      <div v-if="store?.storeCode" class="rt-header__code">店铺码 {{ store.storeCode }}</div>
    </header>

    <!-- loading -->
    <div v-if="loading" class="rt-state">加载中…</div>

    <!-- 错误 / 空码 -->
    <div v-else-if="loadError" class="rt-state rt-state--error">
      <p>{{ loadError }}</p>
      <button class="rt-btn rt-btn--ghost" @click="loadStore">重试</button>
    </div>

    <!-- 成功态 -->
    <div v-else-if="submittedDocNo" class="rt-success">
      <div class="rt-success__icon">✓</div>
      <div class="rt-success__title">询价提交成功</div>
      <div class="rt-success__no">单号：{{ submittedDocNo }}</div>
      <p class="rt-success__tip">批发商确认后会与您联系，请留意来电。</p>
      <button class="rt-btn rt-btn--primary" @click="resetForNext">继续浏览 / 再来一单</button>
    </div>

    <!-- 空态：无在售批发商 -->
    <div
      v-else-if="store && (!store.wholesalers || store.wholesalers.length === 0)"
      class="rt-state"
    >
      本店暂无在售商品
    </div>

    <!-- 浏览 + 询价 -->
    <template v-else-if="store">
      <main class="rt-body">
        <section
          v-for="w in orderedWholesalers"
          :key="w.wholesalerId"
          class="rt-wholesaler"
          :class="{ 'rt-wholesaler--pinned': isPinnedWholesaler(w.wholesalerId) }"
        >
          <div class="rt-wholesaler__name">
            {{ w.name }}
            <span v-if="isPinnedWholesaler(w.wholesalerId)" class="rt-tag rt-tag--pinned">置顶</span>
          </div>
          <p v-if="w.intro" class="rt-wholesaler__intro">{{ w.intro }}</p>

          <div v-if="!w.skus || w.skus.length === 0" class="rt-empty-sku">
            该批发商暂无在售商品
          </div>

          <ul v-else class="rt-sku-list">
            <li
              v-for="s in orderedSkus(w)"
              :key="s.skuId"
              class="rt-sku"
              :class="{ 'rt-sku--featured': isFeaturedSku(s.skuId) }"
            >
              <div class="rt-sku__main">
                <div class="rt-sku__name">
                  {{ s.name }}
                  <span v-if="isFeaturedSku(s.skuId)" class="rt-tag rt-tag--featured">主推</span>
                </div>
                <div v-if="s.spec" class="rt-sku__spec">{{ s.spec }}</div>
                <div class="rt-sku__price">
                  <!-- 命中客户专属价：绿色专属价为成交价，公开价划线次要展示 -->
                  <template v-if="s.matchedPrice != null">
                    <span class="rt-sku__matched">专属 ¥{{ s.matchedPrice }}</span>
                    <span class="rt-sku__unit rt-sku__unit--struck">¥{{ s.unitPrice }}</span>
                  </template>
                  <span v-else class="rt-sku__unit">¥{{ s.unitPrice }}</span>
                  <span class="rt-sku__moq">起批 ¥{{ s.moqPrice }} / {{ s.moqQty }}件</span>
                </div>
                <div class="rt-sku__stock">库存 {{ s.stockQty }}</div>
              </div>
              <div class="rt-stepper">
                <button
                  class="rt-stepper__btn"
                  :disabled="(qtyMap[s.skuId] ?? 0) <= 0"
                  @click="stepQty(s.skuId, -1, s.stockQty)"
                >−</button>
                <input
                  class="rt-stepper__input"
                  type="number"
                  inputmode="numeric"
                  :value="qtyMap[s.skuId] ?? 0"
                  @input="onQtyInput(s.skuId, ($event.target as HTMLInputElement).value, s.stockQty)"
                />
                <button
                  class="rt-stepper__btn"
                  :disabled="(qtyMap[s.skuId] ?? 0) >= s.stockQty"
                  @click="stepQty(s.skuId, 1, s.stockQty)"
                >＋</button>
              </div>
            </li>
          </ul>
        </section>
      </main>

      <!-- 底部提交栏 -->
      <footer class="rt-footer">
        <div class="rt-footer__row">
          <input
            v-model="rtPhone"
            class="rt-phone"
            type="tel"
            inputmode="numeric"
            maxlength="11"
            placeholder="请输入手机号"
          />
          <button
            class="rt-btn rt-btn--ghost rt-footer__price"
            :disabled="priceListLoading || !PHONE_RE.test(rtPhone.trim())"
            @click="openPriceList"
          >
            {{ priceListLoading ? '查询中…' : '我的价目' }}
          </button>
        </div>
        <button
          class="rt-btn rt-btn--primary rt-footer__submit"
          :disabled="submitting || selectedCount === 0"
          @click="submit"
        >
          {{ submitting ? '提交中…' : `提交询价${selectedCount ? `（${selectedCount}）` : ''}` }}
        </button>
      </footer>
    </template>

    <!-- C1 我的价目 bottom sheet（architecture/23-p5-c-c1 §7） -->
    <div v-if="priceSheetOpen" class="rt-sheet-mask" @click.self="priceSheetOpen = false">
      <div class="rt-sheet">
        <div class="rt-sheet__head">
          <div class="rt-sheet__title">
            我的价目<span v-if="priceList?.rtPhoneLast4">（尾号 {{ priceList.rtPhoneLast4 }}）</span>
          </div>
          <button class="rt-sheet__close" @click="priceSheetOpen = false">✕</button>
        </div>

        <div v-if="!priceList || priceList.wholesalers.length === 0" class="rt-sheet__empty">
          暂无专属价目，可在店铺页按公开价询价
        </div>

        <template v-else>
          <section v-for="g in priceList.wholesalers" :key="g.wholesalerId" class="rt-price-group">
            <div class="rt-price-group__name">{{ g.name }}</div>
            <ul class="rt-sku-list">
              <li
                v-for="i in g.items"
                :key="i.skuId"
                class="rt-sku"
                :class="{ 'rt-sku--unlisted': !i.listed }"
              >
                <div class="rt-sku__main">
                  <div class="rt-sku__name">
                    {{ i.name }}
                    <span v-if="!i.listed" class="rt-tag rt-tag--off">已下架</span>
                  </div>
                  <div v-if="i.spec" class="rt-sku__spec">{{ i.spec }}</div>
                  <div class="rt-sku__price">
                    <span class="rt-sku__matched">专属 ¥{{ i.customerPrice }}</span>
                    <span class="rt-sku__unit rt-sku__unit--struck">¥{{ i.unitPrice }}</span>
                    <span v-if="i.moqPrice != null && i.moqQty" class="rt-sku__moq">
                      起批 ¥{{ i.moqPrice }} / {{ i.moqQty }}件
                    </span>
                  </div>
                  <div class="rt-sku__meta">
                    <span class="rt-tag rt-tag--source">
                      {{ i.source === 'from_inquiry' ? '议价沉淀' : '商户价' }}
                    </span>
                    <span v-if="i.expireAt" class="rt-sku__expire">有效期至 {{ fmtDate(i.expireAt) }}</span>
                    <span :class="i.stockQty > 0 ? 'rt-sku__stock' : 'rt-sku__stock rt-sku__stock--zero'">
                      {{ i.stockQty > 0 ? '库存 ' + i.stockQty : '缺货（可询）' }}
                    </span>
                  </div>
                </div>
                <div v-if="i.listed" class="rt-stepper">
                  <button
                    class="rt-stepper__btn"
                    :disabled="priceQty(i) <= 0"
                    @click="stepPriceQty(i, -1)"
                  >−</button>
                  <input
                    class="rt-stepper__input"
                    type="number"
                    inputmode="numeric"
                    :value="priceQty(i)"
                    @input="onPriceQtyInput(i, ($event.target as HTMLInputElement).value)"
                  />
                  <button
                    class="rt-stepper__btn"
                    :disabled="priceQty(i) >= priceStepMax(i)"
                    @click="stepPriceQty(i, 1)"
                  >＋</button>
                </div>
                <div v-else class="rt-sku__off-note">已下架不可询</div>
              </li>
            </ul>
            <div class="rt-price-group__actions">
              <span v-if="priceGroupCount(g) > 0" class="rt-price-group__count">
                已选 {{ priceGroupCount(g) }} 项
              </span>
              <button
                class="rt-btn rt-btn--primary rt-price-group__submit"
                :disabled="submitting || priceGroupCount(g) === 0"
                @click="submitPriceGroup(g)"
              >
                {{ submitting ? '提交中…' : `提交本组询价${priceGroupCount(g) ? `（${priceGroupCount(g)}）` : ''}` }}
              </button>
            </div>
          </section>
          <p class="rt-sheet__tip">提交后批发商将与您联系确认；专属价为当前有效期报价。</p>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 移动优先：单列、指触友好、底部固定提交栏 */
.rt-page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 88px; /* 给固定底栏留位 */
  box-sizing: border-box;
  -webkit-font-smoothing: antialiased;
  color: #1f2329;
}

.rt-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
  padding: 14px 16px;
  border-bottom: 1px solid #eceef1;
}
.rt-header__title {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
}
.rt-header__code {
  margin-top: 2px;
  font-size: 12px;
  color: #8a9099;
}

.rt-state {
  padding: 48px 16px;
  text-align: center;
  color: #8a9099;
  font-size: 14px;
}
.rt-state--error {
  color: #d9534f;
}

.rt-body {
  padding: 12px;
}

.rt-wholesaler {
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
}
.rt-wholesaler__name {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}
.rt-wholesaler__intro {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8a9099;
}
/* 置顶批发商：浅金描边高亮（P5-A W4） */
.rt-wholesaler--pinned {
  border: 1px solid #f0d58a;
  background: #fffdf5;
}
.rt-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.6;
  vertical-align: middle;
}
.rt-tag--featured {
  background: #fff1e0;
  color: #d46b08;
  border: 1px solid #ffd591;
}
.rt-tag--pinned {
  background: #fffbe6;
  color: #ad6800;
  border: 1px solid #ffe58f;
}
.rt-empty-sku {
  padding: 16px 0;
  text-align: center;
  color: #b0b5bd;
  font-size: 13px;
}

.rt-sku-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}
.rt-sku {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 0;
  border-top: 1px solid #f0f1f3;
}
.rt-sku__main {
  flex: 1;
  min-width: 0;
}
.rt-sku__name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rt-sku__spec {
  margin-top: 2px;
  font-size: 12px;
  color: #8a9099;
}
.rt-sku__price {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.rt-sku__unit {
  font-size: 16px;
  font-weight: 600;
  color: #f5222d;
}
/* 命中专属价时：公开价降级为次要划线小字 */
.rt-sku__unit--struck {
  font-size: 12px;
  font-weight: 400;
  color: #b0b5bd;
  text-decoration: line-through;
}
/* 客户专属价（成交价）：绿色高亮 */
.rt-sku__matched {
  font-size: 16px;
  font-weight: 600;
  color: #34a853;
}
.rt-sku__moq {
  font-size: 12px;
  color: #8a9099;
}
.rt-sku__stock {
  margin-top: 2px;
  font-size: 12px;
  color: #a8adb5;
}

.rt-stepper {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.rt-stepper__btn {
  width: 30px;
  height: 30px;
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 6px;
  font-size: 18px;
  line-height: 1;
  color: #333;
  cursor: pointer;
}
.rt-stepper__btn:disabled {
  color: #c8ccd2;
  cursor: not-allowed;
}
.rt-stepper__input {
  width: 44px;
  height: 30px;
  margin: 0 6px;
  text-align: center;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  font-size: 14px;
  -moz-appearance: textfield;
}
.rt-stepper__input::-webkit-outer-spin-button,
.rt-stepper__input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.rt-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #eceef1;
}
.rt-footer__row {
  display: flex;
  gap: 10px;
}
.rt-phone {
  flex: 1;
  height: 42px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  font-size: 15px;
  box-sizing: border-box;
}
/* C1：价目入口按钮（ghost 基础样式被通用 .rt-btn--ghost 的 margin-top:12px 影响，此处归零） */
.rt-footer__price {
  margin-top: 0;
  flex-shrink: 0;
  height: 42px;
  padding: 0 12px;
  font-size: 14px;
  white-space: nowrap;
}
.rt-footer__submit {
  flex-shrink: 0;
  width: 100%;
  height: 44px;
}

.rt-btn {
  height: 42px;
  padding: 0 18px;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
}
.rt-btn--primary {
  background: #2f6bff;
  color: #fff;
}
.rt-btn--primary:disabled {
  background: #a8c0ff;
  cursor: not-allowed;
}
.rt-btn--ghost {
  background: #fff;
  border: 1px solid #dcdfe6;
  color: #333;
  margin-top: 12px;
}
.rt-btn--ghost:disabled {
  color: #b0b5bd;
  border-color: #e5e7eb;
  background: #f7f8fa;
  cursor: not-allowed;
}

.rt-success {
  padding: 64px 24px;
  text-align: center;
}
.rt-success__icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background: #e8f5e9;
  color: #34a853;
  font-size: 32px;
  line-height: 56px;
}
.rt-success__title {
  font-size: 18px;
  font-weight: 600;
}
.rt-success__no {
  margin-top: 10px;
  font-size: 15px;
  color: #2f6bff;
  font-weight: 500;
}
.rt-success__tip {
  margin: 12px 0 24px;
  font-size: 13px;
  color: #8a9099;
}

/* ============ C1 我的价目 bottom sheet（architecture/23-p5-c-c1 §7） ============ */
.rt-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.rt-sheet {
  width: 100%;
  max-width: 520px;
  max-height: 82vh;
  overflow-y: auto;
  background: #fff;
  border-radius: 16px 16px 0 0;
  padding: 12px 16px calc(16px + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.rt-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0 10px;
  border-bottom: 1px solid #f0f1f3;
}
.rt-sheet__title {
  font-size: 16px;
  font-weight: 600;
}
.rt-sheet__close {
  width: 30px;
  height: 30px;
  border: none;
  background: #f2f3f5;
  border-radius: 50%;
  font-size: 14px;
  color: #666;
  cursor: pointer;
}
.rt-sheet__empty {
  padding: 40px 16px;
  text-align: center;
  color: #8a9099;
  font-size: 14px;
}
.rt-price-group {
  padding: 10px 0 4px;
}
.rt-price-group + .rt-price-group {
  border-top: 1px dashed #eceef1;
  margin-top: 10px;
}
.rt-price-group__name {
  font-size: 15px;
  font-weight: 600;
}
.rt-price-group__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 8px 0 4px;
}
.rt-price-group__count {
  font-size: 12px;
  color: #34a853;
}
.rt-price-group__submit {
  height: 38px;
  padding: 0 16px;
  font-size: 14px;
}
/* 下架行：整行降透明度置灰 */
.rt-sku--unlisted .rt-sku__main {
  opacity: 0.55;
}
.rt-sku__meta {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.rt-tag--off {
  background: #f2f3f5;
  color: #8a9099;
  border: 1px solid #e5e7eb;
}
.rt-tag--source {
  background: #e8f3ff;
  color: #1f5fa8;
  border: 1px solid #b8d9f5;
}
.rt-sku__expire {
  font-size: 12px;
  color: #8a9099;
}
.rt-sku__stock--zero {
  color: #d46b08;
}
.rt-sku__off-note {
  flex-shrink: 0;
  font-size: 12px;
  color: #b0b5bd;
}
.rt-sheet__tip {
  margin: 6px 0 4px;
  font-size: 12px;
  color: #8a9099;
  text-align: center;
}
</style>
