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
import type { RtStoreFront, SubmitInquiryRequest } from '@cangchu/api-types'
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

/** 询价数量草稿：skuId -> qty（>0 视为加入询价） */
const qtyMap = reactive<Record<string, number>>({})

const rtPhone = ref('')
const submitting = ref(false)

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

/** 当前有数量(>0)的明细，按批发商聚合，用于提交（一次仅提交一个批发商的询价） */
const selectedByWholesaler = computed(() => {
  const groups: Record<string, { wholesalerId: string; name: string; items: Array<{ skuId: string; qty: number }> }> = {}
  if (!store.value) return groups
  for (const w of store.value.wholesalers) {
    for (const s of w.skus) {
      const qty = qtyMap[s.skuId] ?? 0
      if (qty > 0) {
        if (!groups[w.wholesalerId]) {
          groups[w.wholesalerId] = { wholesalerId: w.wholesalerId, name: w.name, items: [] }
        }
        groups[w.wholesalerId].items.push({ skuId: s.skuId, qty })
      }
    }
  }
  return groups
})

const selectedGroupList = computed(() => Object.values(selectedByWholesaler.value))
const selectedCount = computed(() =>
  selectedGroupList.value.reduce((sum, g) => sum + g.items.length, 0),
)

const PHONE_RE = /^1\d{10}$/

// ============ 提交询价 ============
async function submit() {
  if (submitting.value) return

  const groups = selectedGroupList.value
  if (groups.length === 0) {
    ElMessage.warning('请先为至少一个商品填写数量')
    return
  }
  if (groups.length > 1) {
    ElMessage.warning('一次仅能向一个批发商提交询价，请分别提交')
    return
  }
  if (!PHONE_RE.test(rtPhone.value.trim())) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return
  }

  const group = groups[0]
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
  } catch {
    // http 拦截器已 toast 具体错误码
  } finally {
    submitting.value = false
  }
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
          v-for="w in store.wholesalers"
          :key="w.wholesalerId"
          class="rt-wholesaler"
        >
          <div class="rt-wholesaler__name">{{ w.name }}</div>
          <p v-if="w.intro" class="rt-wholesaler__intro">{{ w.intro }}</p>

          <div v-if="!w.skus || w.skus.length === 0" class="rt-empty-sku">
            该批发商暂无在售商品
          </div>

          <ul v-else class="rt-sku-list">
            <li v-for="s in w.skus" :key="s.skuId" class="rt-sku">
              <div class="rt-sku__main">
                <div class="rt-sku__name">{{ s.name }}</div>
                <div v-if="s.spec" class="rt-sku__spec">{{ s.spec }}</div>
                <div class="rt-sku__price">
                  <span class="rt-sku__unit">¥{{ s.unitPrice }}</span>
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
        <input
          v-model="rtPhone"
          class="rt-phone"
          type="tel"
          inputmode="numeric"
          maxlength="11"
          placeholder="请输入手机号"
        />
        <button
          class="rt-btn rt-btn--primary rt-footer__submit"
          :disabled="submitting || selectedCount === 0"
          @click="submit"
        >
          {{ submitting ? '提交中…' : `提交询价${selectedCount ? `（${selectedCount}）` : ''}` }}
        </button>
      </footer>
    </template>
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
}
.rt-wholesaler__intro {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8a9099;
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
  gap: 10px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #eceef1;
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
.rt-footer__submit {
  flex-shrink: 0;
  min-width: 130px;
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
</style>
