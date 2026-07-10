<script setup lang="ts">
/**
 * 议价沉淀弹窗 · PriceSettleDialog（WA 询价确认 · P2 slice 4b）
 *
 * 作用：WA 确认询价单前，逐行确认/改写「成交价」，并可勾选「沉淀为客户专属价」，
 *       把成交价≠公开价的行沉淀为该买家（手机号）的客户专属价。
 *
 * 契约（后端固定）：
 *   POST /tenant/inquiry/{id}/confirm
 *   body ConfirmInquiryRequest = {
 *     items?: { inquiryItemId: string; dealPrice: number }[]
 *     settleAsCustomerPrice?: boolean
 *   }
 *   （整体省略 body = 旧行为：成交价=公开价快照，不沉淀）
 *
 * 数据来源：
 *   - inquiry.items[i].id            → inquiryItemId（明细行雪花 id，稳定）
 *   - inquiry.items[i].unitPriceSnapshot → 快照公开价，成交价默认值
 *   - inquiry.wholesalerId + rtPhone → 拉该买家现有专属价，命中同 SKU 则提示「将覆盖」
 *
 * 本弹窗只负责收集意图并 emit('confirm', payload)，实际调用/刷新由父页面处理。
 */

import { ref, reactive, computed, watch } from 'vue'
import { MoneyDisplay } from '@cangchu/ui-shared'
import type { Inquiry, ConfirmInquiryRequest } from '@cangchu/api-types'
import { ElMessage } from 'element-plus'
import { pricingApi } from '@/api/pricing'

/** 弹窗可见性（v-model） */
const visible = defineModel<boolean>({ default: false })

const props = defineProps<{
  /** 待确认询价单（含 items） */
  inquiry: Inquiry | null
  /** 父页面确认中（禁用/loading 确认按钮） */
  submitting?: boolean
}>()

const emit = defineEmits<{
  confirm: [payload: ConfirmInquiryRequest]
}>()

// ============ 成交价草稿（inquiryItemId -> dealPrice） ============
const dealPrices = reactive<Record<string, number>>({})
/** 是否沉淀为客户专属价 */
const settleChecked = ref(false)

// ============ 该买家现有专属价（skuId -> 专属单价，仅 ACTIVE 且同手机号） ============
const existingPriceMap = reactive<Record<string, number>>({})
const loadingExisting = ref(false)

const items = computed(() => props.inquiry?.items ?? [])

const docNo = computed(() => props.inquiry?.docNo ?? '')
const rtPhone = computed(() => props.inquiry?.rtPhone ?? '')

/** 弹窗打开时：重置成交价为快照默认值 + 拉取现有专属价 */
watch(
  () => [visible.value, props.inquiry] as const,
  ([open]) => {
    if (!open || !props.inquiry) return
    for (const k of Object.keys(dealPrices)) delete dealPrices[k]
    for (const it of props.inquiry.items) {
      dealPrices[String(it.id)] = Number(it.unitPriceSnapshot)
    }
    settleChecked.value = false
    void fetchExisting()
  },
  { immediate: true },
)

async function fetchExisting() {
  for (const k of Object.keys(existingPriceMap)) delete existingPriceMap[k]
  const inq = props.inquiry
  if (!inq) return
  loadingExisting.value = true
  try {
    const list = await pricingApi.listCustomerPrices(String(inq.wholesalerId))
    for (const cp of list) {
      if (cp.rtPhone === inq.rtPhone && cp.status === 'ACTIVE') {
        existingPriceMap[String(cp.skuId)] = Number(cp.unitPrice)
      }
    }
  } catch {
    // 拉取失败不阻塞结算：降级为通用提示（无「将覆盖」精确文案）
  } finally {
    loadingExisting.value = false
  }
}

// ============ 逐行判定 ============
/** 成交价是否与公开价快照不同（两位小数容差） */
const isChanged = (itemId: string, snapshot: number): boolean => {
  const dp = dealPrices[itemId]
  return dp != null && Math.abs(Number(dp) - Number(snapshot)) > 0.001
}

/** 该行现有专属价（命中同手机号+同 SKU 的 ACTIVE），无则 undefined */
const existingOf = (skuId: string): number | undefined => existingPriceMap[String(skuId)]

/** 勾选沉淀后，成交价≠公开价的行数（用于底部汇总提示） */
const settleCount = computed(() => {
  if (!settleChecked.value) return 0
  return items.value.filter((it) => isChanged(String(it.id), Number(it.unitPriceSnapshot))).length
})

// ============ 提交 ============
function onSubmit() {
  const inq = props.inquiry
  if (!inq) return
  // 成交价合法性：全部 > 0
  for (const it of inq.items) {
    const dp = dealPrices[String(it.id)]
    if (dp == null || !Number.isFinite(Number(dp)) || Number(dp) <= 0) {
      ElMessage.warning('成交价必须大于 0，请检查')
      return
    }
  }
  const payload: ConfirmInquiryRequest = {
    items: inq.items.map((it) => ({
      inquiryItemId: String(it.id),
      dealPrice: Number(dealPrices[String(it.id)]),
    })),
    settleAsCustomerPrice: settleChecked.value,
  }
  emit('confirm', payload)
}

function onCancel() {
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="确认询价并结算"
    width="640px"
    :close-on-click-modal="false"
    class="settle-dialog"
  >
    <div v-if="inquiry" class="settle">
      <!-- 单据摘要 -->
      <div class="settle__summary">
        <span class="settle__doc">询价单「{{ docNo }}」</span>
        <span class="settle__phone">买家 {{ rtPhone || '—' }}</span>
      </div>

      <!-- 逐行成交价 -->
      <el-table :data="items" size="small" class="settle__table">
        <el-table-column label="SKU" min-width="150">
          <template #default="{ row: it }">
            <span class="cell-name">{{ it.skuId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="72" align="right">
          <template #default="{ row: it }">{{ it.qty }}</template>
        </el-table-column>
        <el-table-column label="公开价" width="96" align="right">
          <template #default="{ row: it }">
            <MoneyDisplay :value="it.unitPriceSnapshot" size="sm" />
          </template>
        </el-table-column>
        <el-table-column label="成交价" width="150" align="right">
          <template #default="{ row: it }">
            <el-input-number
              v-model="dealPrices[String(it.id)]"
              :min="0.01"
              :precision="2"
              :step="1"
              :controls="false"
              size="small"
              class="settle__price-input"
            />
          </template>
        </el-table-column>
        <el-table-column label="沉淀提示" min-width="150">
          <template #default="{ row: it }">
            <template v-if="settleChecked && isChanged(String(it.id), Number(it.unitPriceSnapshot))">
              <span v-if="existingOf(String(it.skuId)) !== undefined" class="settle__note settle__note--warn">
                已有专属价 ¥{{ existingOf(String(it.skuId))!.toFixed(2) }}，将覆盖
              </span>
              <span v-else class="settle__note settle__note--new">将新建专属价</span>
            </template>
            <span v-else class="settle__note settle__note--muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 沉淀开关 -->
      <div class="settle__settle-row">
        <el-checkbox v-model="settleChecked">
          沉淀为客户专属价
        </el-checkbox>
        <span class="settle__settle-hint">
          勾选后，成交价与公开价不同的明细将写入该买家（{{ rtPhone || '手机号' }}）的客户专属价，下次自动命中。
        </span>
      </div>
      <div v-if="settleChecked" class="settle__settle-summary">
        <template v-if="settleCount > 0">
          本次将沉淀 <strong>{{ settleCount }}</strong> 条专属价（成交价≠公开价的明细）。
        </template>
        <template v-else>
          当前所有成交价均等于公开价，勾选后不会产生专属价。
        </template>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="onCancel">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!inquiry" @click="onSubmit">
        确认并出库
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.settle {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.settle__summary {
  display: flex;
  align-items: baseline;
  gap: var(--space-4);
}
.settle__doc {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.settle__phone {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.settle__table {
  width: 100%;
}
.settle__price-input {
  width: 120px;
}

.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}

.settle__note {
  font-size: var(--font-size-caption);
}
.settle__note--warn {
  color: var(--color-warning, #e6a23c);
  font-weight: var(--font-weight-medium);
}
.settle__note--new {
  color: var(--color-success, #34a853);
}
.settle__note--muted {
  color: var(--color-fg-4);
}

.settle__settle-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.settle__settle-hint {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  line-height: 1.5;
}
.settle__settle-summary {
  padding: var(--space-2) var(--space-3);
  background: var(--color-info-bg, #eef3ff);
  border-radius: var(--radius-sm);
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
}
</style>
