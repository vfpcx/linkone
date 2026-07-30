<script setup lang="ts">
/**
 * WA 入库异议弹窗（P3 FE-W1 · 09 PRD §1.1 reason 口径 + §6 文案规则）
 *
 * 表单：
 *  - 理由：预设单选（未到货/数量不符/质量问题/其他，09 §1.1 收口口径）+ 补充说明，
 *    合成 reason 提交，必填 ≤512（对齐 InboundDisputeDto）；
 *  - 附件：≤5 张（AttachmentUpload → POST /files 取回 URL）。
 *
 * 口径提示（09 §6.1/§6.2）：
 *  - 异议仅覆盖仍在库部分，已售出部分进入差额定责；
 *  - 实时数字接 GET /{id}/stock-preview（M3 补口，FE-W1 契约偏差③已闭环）：
 *    弹窗打开即拉「实时在库 / 预计冲销 / 预计差额」三数字（轻量快照，实际冲销
 *    以提交后回显为准）；端点异常时降级回口径文案。
 *  - 预计差额 > 0 时提交前强制二次确认（已售部分不可冲销、进定责）。
 *
 * 提交由父页面执行（emit submit），50331/50332 等错误由父页面/全局拦截器处理。
 */

import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  InboundRequest,
  InboundDisputeRequest,
  InboundStockPreview,
} from '@cangchu/api-types'
import { waInboundApi } from '@/api/waInbound'
import AttachmentUpload from '@/components/AttachmentUpload.vue'

interface Props {
  modelValue: boolean
  /** 目标入库单（PENDING_WA_CONFIRM） */
  row: InboundRequest | null
  submitting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  submitting: false,
})

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', payload: InboundDisputeRequest): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

/** 预设快捷理由（09 PRD §1.1：预设单选 + 其他） */
const PRESETS = ['未到货', '数量不符', '质量问题', '其他'] as const

const preset = ref<string>('')
const detail = ref('')
const attachments = ref<string[]>([])

// ============ 异议前在库预览（M3 三数字） ============
const preview = ref<InboundStockPreview | null>(null)
const previewLoading = ref(false)

const fetchPreview = async () => {
  const row = props.row
  if (!row) return
  previewLoading.value = true
  preview.value = null
  try {
    preview.value = await waInboundApi.stockPreview(String(row.id))
  } catch {
    // 端点异常降级：保留口径文案展示，不阻塞异议提交
  } finally {
    previewLoading.value = false
  }
}

// 每次打开重置表单 + 拉实时在库预览
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      preset.value = ''
      detail.value = ''
      attachments.value = []
      void fetchPreview()
    }
  },
)

/** 合成 reason：`[预设] 补充说明`；「其他」必须有补充说明 */
const composedReason = computed(() => {
  const d = detail.value.trim()
  if (!preset.value) return d
  return d ? `[${preset.value}] ${d}` : `[${preset.value}]`
})

/** 补充说明可输入上限：512 − 预设前缀占用 */
const detailMax = computed(() =>
  Math.max(0, 512 - (preset.value ? preset.value.length + 3 : 0)),
)

const onSubmit = async () => {
  const reason = composedReason.value
  if (!preset.value && !detail.value.trim()) {
    ElMessage.warning('请选择异议理由或填写说明')
    return
  }
  if (preset.value === '其他' && !detail.value.trim()) {
    ElMessage.warning('选择「其他」时请填写补充说明')
    return
  }
  if (!reason) {
    ElMessage.warning('异议理由不能为空')
    return
  }
  if (reason.length > 512) {
    ElMessage.warning('异议理由最长 512 字')
    return
  }
  // 预计差额 > 0：强制二次确认（已售部分不可冲销，进入差额定责）
  const p = preview.value
  if (p && p.expectedShortfall > 0) {
    try {
      await ElMessageBox.confirm(
        `已售出约 ${p.expectedShortfall} 件不可冲销，仅冲销仍在库的约 ${p.expectedReversal} 件；差额将进入店长仲裁定责。确认继续提交异议？`,
        '存在已售差额',
        { confirmButtonText: '继续提交', cancelButtonText: '再想想', type: 'warning' },
      )
    } catch {
      return
    }
  }
  emit('submit', {
    reason,
    ...(attachments.value.length ? { attachments: [...attachments.value] } : {}),
  })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`异议 · 代建入库 ${row?.docNo ?? ''}`"
    width="560px"
    top="6vh"
    :close-on-click-modal="false"
    class="dispute-dialog"
    data-test="dispute-dialog"
  >
    <template v-if="row">
      <!-- 全局口径文案（09 §6.1，弹窗必须展示） -->
      <el-alert type="warning" :closable="false" class="dispute-dialog__policy">
        代建入库即视为可售，72 小时内可提异议，异议仅覆盖仍在库部分；已售出部分将进入差额定责。
      </el-alert>

      <!-- 冲销数字口径（09 §6.2；实时三数字接 stock-preview，端点异常降级口径文案） -->
      <div v-loading="previewLoading" class="dispute-dialog__facts" data-test="dispute-facts">
        <div class="fact">
          <span class="fact__label">登记件数</span>
          <span class="fact__value">{{ row.qty }} 件</span>
        </div>
        <template v-if="preview">
          <div class="fact">
            <span class="fact__label">实时在库</span>
            <span class="fact__value" data-test="preview-onhand">{{ preview.onhand }} 件</span>
          </div>
          <div class="fact">
            <span class="fact__label">预计冲销</span>
            <span class="fact__value" data-test="preview-reversal">
              {{ preview.expectedReversal }} 件（按在库封顶，实际以提交后回显为准）
            </span>
          </div>
          <div class="fact">
            <span class="fact__label">预计差额</span>
            <span
              class="fact__value"
              :class="{ 'fact__value--warn': preview.expectedShortfall > 0 }"
              data-test="preview-shortfall"
            >
              {{ preview.expectedShortfall }} 件
              <template v-if="preview.expectedShortfall > 0">
                （已售出，不可冲销，将进入差额定责）
              </template>
            </span>
          </div>
        </template>
        <template v-else>
          <div class="fact">
            <span class="fact__label">将冲销</span>
            <span class="fact__value">仍在库部分（按在库封顶，提交后回显实际件数）</span>
          </div>
          <div class="fact">
            <span class="fact__label">已售部分</span>
            <span class="fact__value fact__value--warn">不可冲销，将进入差额定责</span>
          </div>
        </template>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="异议理由（必选）" required>
          <el-radio-group v-model="preset" data-test="dispute-preset">
            <el-radio v-for="p in PRESETS" :key="p" :value="p">{{ p }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item :label="preset === '其他' ? '补充说明（必填）' : '补充说明（选填）'">
          <el-input
            v-model="detail"
            type="textarea"
            :rows="3"
            :maxlength="detailMax"
            show-word-limit
            placeholder="补充异议细节，将与预设理由一并提交（合计 ≤512 字）"
            data-test="dispute-detail"
          />
        </el-form-item>

        <el-form-item label="附件（选填，最多 5 张）">
          <AttachmentUpload v-model="attachments" :max="5" :disabled="submitting" />
        </el-form-item>
      </el-form>
    </template>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button
        type="danger"
        :loading="submitting"
        data-test="dispute-submit"
        @click="onSubmit"
      >
        提交异议
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dispute-dialog__policy {
  margin-bottom: var(--space-4);
}

.dispute-dialog__facts {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.fact {
  display: flex;
  gap: var(--space-3);
  font-size: var(--font-size-body);
  line-height: 1.5;
}
.fact__label {
  color: var(--color-fg-3);
  flex-shrink: 0;
  width: 64px;
}
.fact__value {
  color: var(--color-fg-1);
}
.fact__value--warn {
  color: var(--color-warning);
}
</style>
