<script setup lang="ts">
/**
 * 图片附件上传 · AttachmentUpload（P3 FE-W1 · 异议/客诉共用）
 *
 * 对接 POST /api/v1/files（登录用户，multipart 单文件）→ { url }；
 * v-model 为 URL 数组（提交时直接作为 attachments 字段）。
 *
 * 约束（与后端 50340 校验一致，前端预检省一次往返）：
 *  - ≤max 张（默认 5，对齐 InboundDisputeDto @Size(max=5)）
 *  - 单张 ≤5MB，仅 jpg/png/webp（后端魔数校验仍是权威）
 * 预览：url 为 /files/** 静态映射（免登录），dev 由 vite 代理到后端。
 */

import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, ZoomIn, Delete } from '@element-plus/icons-vue'
import { ApiError } from '@/api/http'
import { fileApi, FILE_MAX_SIZE, FILE_ACCEPT_MIMES } from '@/api/file'

interface Props {
  modelValue: string[]
  /** 最大张数（异议附件契约 5） */
  max?: number
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  max: 5,
  disabled: false,
})

const emit = defineEmits<{
  (e: 'update:modelValue', urls: string[]): void
}>()

const uploading = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)
const previewVisible = ref(false)
const previewIndex = ref(0)

const canAdd = computed(
  () => !props.disabled && !uploading.value && props.modelValue.length < props.max,
)

const pick = () => {
  if (!canAdd.value) return
  inputRef.value?.click()
}

const onFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一文件
  if (!file) return

  if (!FILE_ACCEPT_MIMES.includes(file.type)) {
    ElMessage.warning('仅支持 jpg / png / webp 图片')
    return
  }
  if (file.size > FILE_MAX_SIZE) {
    ElMessage.warning('单张图片不能超过 5MB')
    return
  }
  if (props.modelValue.length >= props.max) {
    ElMessage.warning(`附件最多 ${props.max} 张`)
    return
  }

  uploading.value = true
  try {
    const { url } = await fileApi.upload(file)
    emit('update:modelValue', [...props.modelValue, url])
  } catch (err) {
    // 50340 等由全局 toast 提示；此处兜底
    if (!(err instanceof ApiError)) {
      ElMessage.error('上传失败，请稍后重试')
    }
  } finally {
    uploading.value = false
  }
}

const removeAt = (idx: number) => {
  if (props.disabled) return
  const next = [...props.modelValue]
  next.splice(idx, 1)
  emit('update:modelValue', next)
}

const openPreview = (idx: number) => {
  previewIndex.value = idx
  previewVisible.value = true
}
</script>

<template>
  <div class="cc-attach" data-test="attachment-upload">
    <div v-for="(url, idx) in modelValue" :key="url" class="cc-attach__item">
      <img :src="url" class="cc-attach__img" alt="附件" />
      <div class="cc-attach__mask">
        <el-icon class="cc-attach__op" title="预览" @click="openPreview(idx)"><ZoomIn /></el-icon>
        <el-icon
          v-if="!disabled"
          class="cc-attach__op"
          title="删除"
          data-test="attachment-remove"
          @click="removeAt(idx)"
        >
          <Delete />
        </el-icon>
      </div>
    </div>

    <button
      v-if="modelValue.length < max"
      type="button"
      class="cc-attach__add"
      :class="{ 'is-disabled': !canAdd }"
      :disabled="!canAdd"
      data-test="attachment-add"
      @click="pick"
    >
      <el-icon v-if="!uploading"><Plus /></el-icon>
      <span v-else class="cc-attach__uploading">上传中…</span>
    </button>

    <span class="cc-attach__hint">{{ modelValue.length }}/{{ max }} · jpg/png/webp ≤5MB</span>

    <input
      ref="inputRef"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      class="cc-attach__input"
      data-test="attachment-input"
      @change="onFileChange"
    />

    <el-image-viewer
      v-if="previewVisible"
      :url-list="modelValue"
      :initial-index="previewIndex"
      @close="previewVisible = false"
    />
  </div>
</template>

<style scoped>
.cc-attach {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.cc-attach__item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.cc-attach__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.cc-attach__mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  background: rgba(2, 6, 23, 0.45);
  opacity: 0;
  transition: opacity 0.15s;
}
.cc-attach__item:hover .cc-attach__mask {
  opacity: 1;
}
.cc-attach__op {
  color: #fff;
  font-size: 18px;
  cursor: pointer;
}

.cc-attach__add {
  width: 72px;
  height: 72px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border-2);
  background: var(--color-bg-2);
  color: var(--color-fg-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 20px;
  flex-shrink: 0;
  transition:
    border-color 0.15s,
    color 0.15s;
}
.cc-attach__add:hover:not(.is-disabled) {
  border-color: var(--color-brand-accent);
  color: var(--color-brand-accent);
}
.cc-attach__add.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.cc-attach__uploading {
  font-size: var(--font-size-caption);
}

.cc-attach__hint {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.cc-attach__input {
  display: none;
}
</style>
