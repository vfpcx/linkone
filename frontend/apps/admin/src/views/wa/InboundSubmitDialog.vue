<script setup lang="ts">
/**
 * 新建入库申请弹窗（P3b T1-FE · PRD 11 §1.2 / 线框 A）
 *
 * 契约（权威：WholesalerInboundController.submit + InboundSubmitDto 实测）：
 *  POST /wholesaler/inbound-requests {wholesalerId, items:[{skuId, qty, palletQty?, remark?}]}
 *  - items ≥1 行、≤50 行（40001）；单事务拆 N 张一单一 SKU 的申请单（共享 batchSubmitId）
 *  - 提交全程零库存零计费（登记才加库存）；商户非营业 50313（R14）；WE 需 INBOUND_SUBMIT（42004）
 *  - ⚠️ 契约偏差：后端提交入参无附件字段（PRD §1.2 照片附件挂单据留证后置），本表单不含附件
 *
 * 商品选择：开放实体集 → EntityPickerDialog（UX 规范 2026-07-25），含已下架标识。
 * 复制重建（R2 一键复制重建）：父级经 prefill 带入原单字段。
 */

import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import {
  EntityPickerDialog,
  makeClientPickerFetch,
  type EntityPickerColumn,
} from '@cangchu/ui-shared'
import type { Sku, InboundSubmitRequest, InboundRequest } from '@cangchu/api-types'
import { waInboundApi } from '@/api/waInbound'

const MAX_ROWS = 50

interface RowDraft {
  skuId: string
  qty: number | undefined
  palletQty: number | undefined
  remark: string
}

interface Props {
  modelValue: boolean
  /** 本账号绑定商户 id（提交必传） */
  wholesalerId: string
  /** 本商户 SKU 全集（父级拉取，含已下架标识） */
  skus: Sku[]
  /** 入库仓库名（只读展示） */
  storeName?: string
  /** 复制重建预填行（R2 驳回后带出原字段） */
  prefill?: { skuId: string; qty: number; palletQty?: number; remark?: string } | null
}

const props = withDefaults(defineProps<Props>(), {
  storeName: '',
  prefill: null,
})

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 提交成功（返回后端拆出的 N 张新单） */
  (e: 'submitted', rows: InboundRequest[]): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const emptyRow = (): RowDraft => ({ skuId: '', qty: undefined, palletQty: undefined, remark: '' })

const rows = ref<RowDraft[]>([emptyRow()])
const submitting = ref(false)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    rows.value = props.prefill
      ? [
          {
            skuId: props.prefill.skuId,
            qty: props.prefill.qty,
            palletQty: props.prefill.palletQty,
            remark: props.prefill.remark ?? '',
          },
        ]
      : [emptyRow()]
  },
)

// ============ SKU 选择器（开放实体集） ============
const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of props.skus) map[String(s.id)] = s.listed ? s.name : `${s.name}（已下架）`
  return map
})

const skuPickerColumns: EntityPickerColumn<Sku>[] = [
  { label: '商品名称', prop: 'name', minWidth: 160 },
  { label: '规格', formatter: (s) => s.spec || '—', minWidth: 100 },
  { label: '状态', formatter: (s) => (s.listed ? '在售' : '已下架'), width: 90 },
]

const fetchSkuPage = makeClientPickerFetch<Sku>(
  () => props.skus,
  (s, kw) => s.name.toLowerCase().includes(kw) || (s.spec ?? '').toLowerCase().includes(kw),
)

// ============ 行操作 ============
const addRow = () => {
  if (rows.value.length >= MAX_ROWS) {
    ElMessage.warning(`单次最多提交 ${MAX_ROWS} 行`)
    return
  }
  rows.value.push(emptyRow())
}

const removeRow = (idx: number) => {
  rows.value.splice(idx, 1)
  if (rows.value.length === 0) rows.value.push(emptyRow())
}

// ============ 校验 + 提交 ============
/** 行内校验（线框：件数为 0/非法 → 行内红字、提交置灰） */
const rowError = (r: RowDraft): string => {
  if (!r.skuId) return '请选择商品'
  if (r.qty === undefined || r.qty === null) return '请输入申请件数'
  if (!Number.isInteger(Number(r.qty)) || Number(r.qty) <= 0) return '申请件数须为大于 0 的整数'
  if (
    r.palletQty !== undefined &&
    r.palletQty !== null &&
    (!Number.isInteger(Number(r.palletQty)) || Number(r.palletQty) < 0)
  ) {
    return '托盘数须为不小于 0 的整数'
  }
  return ''
}

const allValid = computed(() => rows.value.every((r) => !rowError(r)))

const onSubmit = async () => {
  if (!props.wholesalerId) {
    ElMessage.warning('未找到绑定商户，请重新登录')
    return
  }
  if (!allValid.value) {
    ElMessage.warning('请先补全商品行（红字提示处）')
    return
  }
  const n = rows.value.length
  // 线框 A：提交前确认拆单口径
  try {
    await ElMessageBox.confirm(
      `本次将生成 ${n} 张入库申请单（每商品一单），仓库可分别受理。提交不影响库存与计费，登记入库后次日开始计费。`,
      '确认提交',
      { confirmButtonText: '提交申请', cancelButtonText: '再改改', type: 'info' },
    )
  } catch {
    return
  }
  const payload: InboundSubmitRequest = {
    wholesalerId: props.wholesalerId,
    items: rows.value.map((r) => ({
      skuId: r.skuId,
      qty: Number(r.qty),
      ...(r.palletQty !== undefined && r.palletQty !== null
        ? { palletQty: Number(r.palletQty) }
        : {}),
      ...(r.remark.trim() ? { remark: r.remark.trim() } : {}),
    })),
  }
  submitting.value = true
  try {
    const created = await waInboundApi.submit(payload)
    ElMessage.success(`已提交 ${created.length} 张入库申请，等待仓库受理`)
    visible.value = false
    emit('submitted', created)
  } catch {
    // 50313 商户非营业 / 42004 无授权位 / 40xxx 校验：全局 toast 已提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="新建入库申请"
    width="720px"
    :close-on-click-modal="false"
    data-test="inbound-submit-dialog"
  >
    <p class="submit-store">
      入库到：<span class="cell-name">{{ storeName || '当前入驻仓库' }}</span>
    </p>

    <div
      v-for="(row, idx) in rows"
      :key="idx"
      class="submit-row"
      :data-test="`submit-row-${idx}`"
    >
      <div class="submit-row__head">
        <span class="submit-row__title">商品 {{ idx + 1 }}</span>
        <el-button
          v-if="rows.length > 1"
          text
          type="danger"
          size="small"
          :icon="Delete"
          data-test="row-remove"
          @click="removeRow(idx)"
        >
          删除行
        </el-button>
      </div>
      <div class="submit-row__fields">
        <div class="submit-field submit-field--sku">
          <span class="submit-field__label">商品 *</span>
          <EntityPickerDialog
            v-model="row.skuId"
            title="选择商品"
            placeholder="点击选择商品"
            :columns="skuPickerColumns"
            :fetch="fetchSkuPage"
            :selected-label="skuNameMap[row.skuId] || ''"
            class="full-width"
          />
        </div>
        <div class="submit-field">
          <span class="submit-field__label">申请件数 *</span>
          <el-input-number
            v-model="row.qty"
            :min="1"
            :precision="0"
            :step="1"
            placeholder="大于 0"
            class="full-width"
            data-test="row-qty"
          />
        </div>
        <div class="submit-field">
          <span class="submit-field__label">占用托盘</span>
          <el-input-number
            v-model="row.palletQty"
            :min="0"
            :precision="0"
            :step="1"
            placeholder="选填"
            class="full-width"
            data-test="row-pallet"
          />
        </div>
        <div class="submit-field submit-field--remark">
          <span class="submit-field__label">备注</span>
          <el-input v-model="row.remark" maxlength="200" placeholder="选填 ≤200 字" />
        </div>
      </div>
      <p v-if="rowError(row)" class="submit-row__error" data-test="row-error">
        {{ rowError(row) }}
      </p>
    </div>

    <el-button
      text
      type="primary"
      :icon="Plus"
      :disabled="rows.length >= MAX_ROWS"
      data-test="row-add"
      @click="addRow"
    >
      添加商品（{{ rows.length }}/{{ MAX_ROWS }}）
    </el-button>

    <el-alert type="info" :closable="false" class="submit-hint">
      提交后按商品行生成 {{ rows.length }} 张申请单（每商品一单），仓库可分别受理；
      提交与受理不影响库存与计费，货物登记入库后次日开始计费。
    </el-alert>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!allValid"
        data-test="submit-btn"
        @click="onSubmit"
      >
        提交申请
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.submit-store {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}

.submit-row {
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  margin-bottom: var(--space-3);
  background: var(--color-bg-2);
}
.submit-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.submit-row__title {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  font-size: var(--font-size-body);
}
.submit-row__fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}
.submit-field {
  flex: 1 1 120px;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
}
.submit-field--sku {
  flex: 2 1 200px;
}
.submit-field--remark {
  flex: 2 1 180px;
}
.submit-field__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.submit-row__error {
  margin: var(--space-2) 0 0;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}

.submit-hint {
  margin-top: var(--space-3);
}
.submit-hint :deep(.el-alert__description) {
  margin: 0;
}

.full-width {
  width: 100%;
}

@media (max-width: 768px) {
  .submit-field {
    flex: 1 1 100%;
  }
}
</style>
