<script setup lang="ts">
/**
 * WA 出库客诉弹窗（P3 FE-W2 · 09 PRD §3 30 天客诉，复用 FE-W1 异议弹窗模式）
 *
 * 表单：
 *  - 理由：预设单选（数量不符/货品损坏/未同意此次出库/其他）+ 补充说明，
 *    合成 reason 提交，必填 ≤512（对齐 OutboundComplainDto）；
 *  - 附件：≤5 张（AttachmentUpload → POST /files 取回 URL）。
 *
 * 口径提示（09 PRD §3 / D43）：
 *  - 仅仓库代建且已出库的单可诉，窗口为实际出库后 30 天（超窗 50339）；
 *  - 客诉由平台运维仲裁，结论仅判责（不动库存与账单），作为线下赔偿依据。
 *
 * 提交由父页面执行（emit submit），50331/50339 等错误由父页面/全局拦截器处理。
 */

import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { OutboundRequest, OutboundComplainRequest } from '@cangchu/api-types'
import AttachmentUpload from '@/components/AttachmentUpload.vue'

interface Props {
  modelValue: boolean
  /** 目标出库单（COMPLETED ∧ source=WK_CREATED ∧ 30 天窗口内） */
  row: OutboundRequest | null
  submitting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  submitting: false,
})

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', payload: OutboundComplainRequest): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

/** 预设快捷理由（对齐 09 PRD §3 客诉场景） */
const PRESETS = ['数量不符', '货品损坏', '未同意此次出库', '其他'] as const

const preset = ref<string>('')
const detail = ref('')
const attachments = ref<string[]>([])

// 每次打开重置表单
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      preset.value = ''
      detail.value = ''
      attachments.value = []
    }
  },
)

/** 剩余客诉天数（completedAt + 30 天窗口；仅展示，超窗由后端 50339 兜底） */
const remainDays = computed(() => {
  const anchor = props.row?.completedAt ?? props.row?.createdAt
  if (!anchor) return null
  const t = new Date(String(anchor)).getTime()
  if (!Number.isFinite(t)) return null
  const remain = t + 30 * 24 * 3600 * 1000 - Date.now()
  return Math.max(0, Math.ceil(remain / (24 * 3600 * 1000)))
})

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

const onSubmit = () => {
  const reason = composedReason.value
  if (!preset.value && !detail.value.trim()) {
    ElMessage.warning('请选择客诉理由或填写说明')
    return
  }
  if (preset.value === '其他' && !detail.value.trim()) {
    ElMessage.warning('选择「其他」时请填写补充说明')
    return
  }
  if (!reason) {
    ElMessage.warning('客诉理由不能为空')
    return
  }
  if (reason.length > 512) {
    ElMessage.warning('客诉理由最长 512 字')
    return
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
    :title="`客诉 · 代建出库 ${row?.docNo ?? ''}`"
    width="560px"
    top="6vh"
    :close-on-click-modal="false"
    class="complain-dialog"
    data-test="complain-dialog"
  >
    <template v-if="row">
      <!-- 全局口径文案（09 PRD §3 / D43） -->
      <el-alert type="warning" :closable="false" class="complain-dialog__policy">
        仓库代建的出库单可在实际出库后 30 天内发起客诉，由平台运维仲裁；
        结论仅作判责与线下赔偿依据，不改库存与账单。同一出库单仅可发起一次客诉。
      </el-alert>

      <div class="complain-dialog__facts" data-test="complain-facts">
        <div class="fact">
          <span class="fact__label">出库件数</span>
          <span class="fact__value">{{ row.qty }} 件</span>
        </div>
        <div class="fact">
          <span class="fact__label">出库时间</span>
          <span class="fact__value">
            {{ String(row.completedAt ?? '').replace('T', ' ').slice(0, 19) || '—' }}
          </span>
        </div>
        <div class="fact">
          <span class="fact__label">客诉窗口</span>
          <span
            class="fact__value"
            :class="{ 'fact__value--warn': remainDays !== null && remainDays <= 3 }"
            data-test="complain-remain-days"
          >
            <template v-if="remainDays !== null">剩余约 {{ remainDays }} 天</template>
            <template v-else>—</template>
          </span>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="客诉理由（必选）" required>
          <el-radio-group v-model="preset" data-test="complain-preset">
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
            placeholder="补充客诉细节，将与预设理由一并提交（合计 ≤512 字）"
            data-test="complain-detail"
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
        data-test="complain-submit"
        @click="onSubmit"
      >
        提交客诉
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.complain-dialog__policy {
  margin-bottom: var(--space-4);
}

.complain-dialog__facts {
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
  font-weight: var(--font-weight-semibold);
}
</style>
