<script setup lang="ts">
/**
 * 顶栏 · 老板多仓切换器
 *
 * - 显示当前仓名 + 状态徽章（PENDING/ACTIVE/REJECTED）
 * - 下拉列出名下所有仓，选择即 switchWarehouse → 整页刷新（各页 onMounted 会带新 X-Tenant-Id 重新拉数）
 * - 底部「+ 新建仓库」→ 弹窗（name + contactPhone 必填）→ createWarehouse → 切到新仓
 *
 * 数据源：stores/warehouse.ts（拉取 GET /api/v1/tenant/warehouses）
 * 契约：backend TenantController + WarehouseVo + TenantApplyDto
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, OfficeBuilding, Plus, Check } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { StatusBadge } from '@cangchu/ui-shared'
import type { WarehouseStatus, CreateWarehouseRequest } from '@cangchu/api-types'
import { useWarehouseStore } from '@/stores/warehouse'
import { ApiError } from '@/api/http'

const warehouseStore = useWarehouseStore()

const STATUS_META: Record<
  WarehouseStatus,
  { variant: 'success' | 'warning' | 'danger'; label: string }
> = {
  ACTIVE: { variant: 'success', label: '已通过' },
  PENDING: { variant: 'warning', label: '待审核' },
  REJECTED: { variant: 'danger', label: '已驳回' },
}

function statusMeta(status: string) {
  return STATUS_META[status as WarehouseStatus] ?? { variant: 'warning' as const, label: status }
}

const currentName = computed(() => warehouseStore.currentName || '未选择仓库')
const currentStatus = computed(() => warehouseStore.current?.status ?? null)

// ============ 切换 / 新建 ============
const CREATE_CMD = '__create__'

async function onCommand(cmd: string) {
  if (cmd === CREATE_CMD) {
    openCreate()
    return
  }
  if (cmd === warehouseStore.currentTenantId) return
  warehouseStore.switchWarehouse(cmd)
  // 整页刷新：让当前页 onMounted 携带新 X-Tenant-Id 重新拉取该仓数据
  window.location.reload()
}

// ============ 新建仓库弹窗 ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<CreateWarehouseRequest>({
  name: '',
  contactPhone: '',
  addressText: '',
})

const rules: FormRules<CreateWarehouseRequest> = {
  name: [
    { required: true, message: '请输入仓库名称', trigger: 'blur' },
    { max: 128, message: '仓库名称最多 128 字', trigger: 'blur' },
  ],
  contactPhone: [
    { required: true, message: '请输入联系手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
}

function openCreate() {
  form.name = ''
  form.contactPhone = ''
  form.addressText = ''
  dialogVisible.value = true
}

async function submitCreate() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload: CreateWarehouseRequest = {
      name: form.name.trim(),
      contactPhone: form.contactPhone.trim(),
    }
    if (form.addressText?.trim()) payload.addressText = form.addressText.trim()
    await warehouseStore.createWarehouse(payload)
    ElMessage.success('仓库创建成功，已切换至新仓')
    dialogVisible.value = false
    window.location.reload()
  } catch (e) {
    // http 拦截器已 Toast；此处仅兜底非 ApiError
    if (!(e instanceof ApiError)) ElMessage.error('创建失败，请重试')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (warehouseStore.warehouses.length === 0) {
    warehouseStore.fetchWarehouses().catch(() => undefined)
  }
})
</script>

<template>
  <el-dropdown
    class="wh-switcher"
    trigger="click"
    placement="bottom-start"
    @command="onCommand"
  >
    <span class="wh-switcher__trigger" role="button" tabindex="0">
      <el-icon class="wh-switcher__icon"><OfficeBuilding /></el-icon>
      <span class="wh-switcher__name">{{ currentName }}</span>
      <StatusBadge
        v-if="currentStatus"
        size="sm"
        :variant="statusMeta(currentStatus).variant"
        :text="statusMeta(currentStatus).label"
      />
      <el-icon class="wh-switcher__caret"><ArrowDown /></el-icon>
    </span>

    <template #dropdown>
      <el-dropdown-menu class="wh-menu">
        <el-dropdown-item
          v-for="w in warehouseStore.warehouses"
          :key="w.tenantId"
          :command="w.tenantId"
          :class="{ 'is-current': w.tenantId === warehouseStore.currentTenantId }"
        >
          <span class="wh-item">
            <el-icon class="wh-item__check">
              <Check v-if="w.tenantId === warehouseStore.currentTenantId" />
            </el-icon>
            <span class="wh-item__name">{{ w.name }}</span>
            <StatusBadge
              size="sm"
              :variant="statusMeta(w.status).variant"
              :text="statusMeta(w.status).label"
            />
          </span>
        </el-dropdown-item>

        <el-dropdown-item
          v-if="warehouseStore.warehouses.length === 0"
          disabled
        >
          <span class="wh-item__empty">暂无仓库</span>
        </el-dropdown-item>

        <el-dropdown-item divided :command="CREATE_CMD">
          <span class="wh-item wh-item--create">
            <el-icon><Plus /></el-icon>
            <span>新建仓库</span>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>

  <!-- 新建仓库弹窗 -->
  <el-dialog
    v-model="dialogVisible"
    title="新建仓库"
    width="440px"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="88px"
      @submit.prevent
    >
      <el-form-item label="仓库名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入仓库名称" maxlength="128" />
      </el-form-item>
      <el-form-item label="联系手机" prop="contactPhone">
        <el-input v-model="form.contactPhone" placeholder="请输入联系手机号" maxlength="11" />
      </el-form-item>
      <el-form-item label="仓库地址">
        <el-input
          v-model="form.addressText"
          type="textarea"
          :rows="2"
          placeholder="选填"
          maxlength="200"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitCreate">
        创建并切换
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wh-switcher {
  display: inline-flex;
  align-items: center;
}
.wh-switcher__trigger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 4px 10px;
  border-radius: var(--radius-base);
  color: var(--color-brand-primary-on, #fff);
  outline: none;
  transition: background var(--duration-fast) var(--easing-standard);
}
.wh-switcher__trigger:hover,
.wh-switcher__trigger:focus-visible {
  background: rgba(255, 255, 255, 0.12);
}
.wh-switcher__icon {
  opacity: 0.85;
}
.wh-switcher__name {
  font-weight: var(--font-weight-medium);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wh-switcher__caret {
  opacity: 0.7;
  font-size: 12px;
}

/* 下拉项 */
.wh-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 200px;
}
.wh-item__check {
  width: 16px;
  color: var(--color-brand-accent);
  flex-shrink: 0;
}
.wh-item__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wh-item__empty {
  color: var(--color-fg-4);
}
.wh-item--create {
  color: var(--color-brand-accent);
  font-weight: var(--font-weight-medium);
  min-width: auto;
}
.wh-menu :deep(.is-current) {
  background: var(--color-info-bg);
}

/* 窄屏（DEF-4 配套）：仓名收窄留位给顶栏右区 */
@media (max-width: 480px) {
  .wh-switcher__name {
    max-width: 96px;
  }
}
</style>
