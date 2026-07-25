<script setup lang="ts" generic="T extends Record<string, any>">
/**
 * EntityPickerDialog · 开放实体集弹窗选择器（UX 规范 2026-07-25 用户拍板）
 *
 * 交互规范（全仓统一）：
 *  - 有限枚举（固定 ≤20 项）→ 保持普通 el-select，不用本组件；
 *  - 开放实体集（商品/SKU/商户/专属价等会无限增长）→ 本组件：
 *      触发控件 = 只读 input + 放大镜图标（点击开弹窗），已选后显示名称并可清除；
 *      弹窗 = 关键字搜索框 + 分页表格（多列展示上下文）+ 确认。
 *
 * 用法（单选）：
 *  <EntityPickerDialog v-model="skuId" title="选择商品" :columns="cols"
 *    :fetch="fetchFn" :selected-label="skuNameMap[skuId]" @change="onChange" />
 * 多选（批量场景，如批量调价 skuIds 1..200）加 multiple，v-model 为 string[]，
 * 触发控件显示「已选 N 项」。
 *
 * 数据加载：fetch({keyword,page,pageSize}) → {rows,total}；后端 list 端点
 * 暂不支持 keyword/page 时用 makeClientPickerFetch 做前端过滤分页。
 *
 * 375 窄屏：弹窗宽度收敛为 calc(100vw - 24px)，表格内部横向滚动。
 */
import { computed, nextTick, ref, type Ref } from 'vue'
import {
  ElButton,
  ElDialog,
  ElIcon,
  ElInput,
  ElPagination,
  ElTable,
  ElTableColumn,
  vLoading,
  type TableInstance,
} from 'element-plus'
import { CircleClose, Search } from '@element-plus/icons-vue'
import type { EntityPickerColumn, EntityPickerFetch } from './entityPicker'

interface Props {
  /** 单选：id 字符串（'' = 未选）；多选：id 数组 */
  modelValue: string | string[]
  /** 弹窗表格列 */
  columns: EntityPickerColumn<T>[]
  /** 数据加载函数（服务端分页或 makeClientPickerFetch 前端分页） */
  fetch: EntityPickerFetch<T>
  /** 弹窗标题 */
  title?: string
  /** 触发控件占位文案 */
  placeholder?: string
  /** 多选模式（批量场景） */
  multiple?: boolean
  /** 行主键字段，默认 id */
  rowKey?: string
  /** 触发控件回显字段，默认 name */
  labelKey?: string
  /** 单选回显文案（父级用 name map 提供，优先于内部记录，覆盖编辑回显场景） */
  selectedLabel?: string
  disabled?: boolean
  clearable?: boolean
  pageSize?: number
  searchPlaceholder?: string
  emptyText?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '选择',
  placeholder: '点击选择',
  multiple: false,
  rowKey: 'id',
  labelKey: 'name',
  selectedLabel: '',
  disabled: false,
  clearable: true,
  pageSize: 8,
  searchPlaceholder: '输入关键字搜索',
  emptyText: '暂无数据',
})

const emit = defineEmits<{
  /** v-model 更新（单选 string / 多选 string[]） */
  (e: 'update:modelValue', value: string | string[]): void
  /** 确认且值发生变化时触发（含清除），语义对齐 el-select @change */
  (e: 'change', value: string | string[]): void
  (e: 'clear'): void
}>()

const idOf = (row: T): string => String(row[props.rowKey])
const labelOf = (row: T): string => String(row[props.labelKey] ?? idOf(row))

// ============ 触发控件 ============
/** 单选：最近一次确认的行文案（selectedLabel 缺省时回显用） */
const pickedLabel = ref('')

const hasValue = computed(() =>
  props.multiple ? (props.modelValue as string[]).length > 0 : Boolean(props.modelValue),
)

const displayText = computed(() => {
  if (props.multiple) {
    const n = (props.modelValue as string[]).length
    return n > 0 ? `已选 ${n} 项` : ''
  }
  if (!props.modelValue) return ''
  return props.selectedLabel || pickedLabel.value || String(props.modelValue)
})

const onClear = () => {
  pickedLabel.value = ''
  const empty = props.multiple ? [] : ''
  emit('update:modelValue', empty)
  emit('change', empty)
  emit('clear')
}

// ============ 弹窗 ============
const visible = ref(false)
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const rows = ref([]) as Ref<T[]>
const loading = ref(false)
const tableRef = ref<TableInstance>()

/** 单选：当前高亮行 id / 行对象 */
const currentId = ref('')
const currentRow = ref(null) as Ref<T | null>
/** 多选：已选但尚未在任何已加载页出现过的 id（无法预勾选，确认时原样保留） */
const unseenIds = ref<string[]>([])
/** 多选：表格当前勾选（reserve-selection 跨页保留） */
const selectedRows = ref([]) as Ref<T[]>

const load = async () => {
  loading.value = true
  try {
    const res = await props.fetch({
      keyword: keyword.value,
      page: page.value,
      pageSize: props.pageSize,
    })
    rows.value = res.rows
    total.value = res.total
    await nextTick()
    syncSelection()
  } finally {
    loading.value = false
  }
}

/** 数据落表后同步选中态（单选高亮 / 多选预勾选） */
const syncSelection = () => {
  const table = tableRef.value
  if (!table) return
  if (props.multiple) {
    const stillUnseen: string[] = []
    for (const id of unseenIds.value) {
      const row = rows.value.find((r) => idOf(r) === id)
      if (row) table.toggleRowSelection(row, true)
      else stillUnseen.push(id)
    }
    unseenIds.value = stillUnseen
  } else {
    const row = rows.value.find((r) => idOf(r) === currentId.value)
    table.setCurrentRow(row)
  }
}

const openDialog = () => {
  if (props.disabled) return
  keyword.value = ''
  page.value = 1
  if (props.multiple) {
    unseenIds.value = [...(props.modelValue as string[])]
    selectedRows.value = []
    tableRef.value?.clearSelection()
  } else {
    currentId.value = String(props.modelValue ?? '')
    currentRow.value = null
  }
  visible.value = true
  void load()
}

const onSearch = () => {
  page.value = 1
  void load()
}

const onPageChange = () => {
  void load()
}

const onCurrentChange = (row: T | null) => {
  if (props.multiple) return
  currentRow.value = row
  currentId.value = row ? idOf(row) : ''
}

const onSelectionChange = (sel: T[]) => {
  if (!props.multiple) return
  selectedRows.value = sel
}

const confirmDisabled = computed(() => (props.multiple ? false : !currentId.value))

const emitIfChanged = (value: string | string[]) => {
  const prev = props.modelValue
  const changed = props.multiple
    ? JSON.stringify([...(prev as string[])].sort()) !==
      JSON.stringify([...(value as string[])].sort())
    : prev !== value
  emit('update:modelValue', value)
  if (changed) emit('change', value)
}

const onConfirm = () => {
  if (props.multiple) {
    const ids = [...unseenIds.value, ...selectedRows.value.map(idOf)]
    emitIfChanged(ids)
  } else {
    if (!currentRow.value) return
    pickedLabel.value = labelOf(currentRow.value)
    emitIfChanged(currentId.value)
  }
  visible.value = false
}

/** 单选双击行 = 选中并确认（快捷路径） */
const onRowDblclick = (row: T) => {
  if (props.multiple) return
  currentRow.value = row
  currentId.value = idOf(row)
  onConfirm()
}
</script>

<template>
  <div class="cc-picker" :class="{ 'is-disabled': disabled }">
    <!-- 触发控件：只读 input + 放大镜（点击开弹窗），已选可清除 -->
    <el-input
      :model-value="displayText"
      readonly
      :placeholder="placeholder"
      :disabled="disabled"
      class="cc-picker__trigger"
      @click="openDialog"
    >
      <template #suffix>
        <el-icon
          v-if="clearable && hasValue && !disabled"
          class="cc-picker__clear"
          @click.stop="onClear"
        >
          <CircleClose />
        </el-icon>
        <el-icon class="cc-picker__lens"><Search /></el-icon>
      </template>
    </el-input>

    <el-dialog
      v-model="visible"
      :title="title"
      class="cc-picker-dialog"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="cc-picker-dialog__search">
        <el-input
          v-model="keyword"
          :placeholder="searchPlaceholder"
          clearable
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" @click="onSearch">搜索</el-button>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        :row-key="idOf"
        :highlight-current-row="!multiple"
        :empty-text="emptyText"
        class="cc-picker-dialog__table"
        @current-change="onCurrentChange"
        @selection-change="onSelectionChange"
        @row-dblclick="onRowDblclick"
      >
        <el-table-column v-if="multiple" type="selection" width="42" reserve-selection />
        <el-table-column
          v-for="col in columns"
          :key="col.label"
          :prop="col.prop"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :align="col.align"
          show-overflow-tooltip
        >
          <template v-if="col.formatter" #default="{ row }">
            {{ col.formatter?.(row as T) }}
          </template>
        </el-table-column>
      </el-table>

      <div class="cc-picker-dialog__pager">
        <span v-if="multiple" class="cc-picker-dialog__count">
          已选 {{ unseenIds.length + selectedRows.length }} 项
        </span>
        <el-pagination
          v-model:current-page="page"
          layout="prev, pager, next, total"
          :total="total"
          :page-size="pageSize"
          @current-change="onPageChange"
        />
      </div>

      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="confirmDisabled" @click="onConfirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cc-picker {
  width: 100%;
}
/* 只读触发框：整体可点，光标用 pointer 表意 */
.cc-picker__trigger :deep(.el-input__inner) {
  cursor: pointer;
}
.cc-picker__trigger :deep(.el-input__wrapper) {
  cursor: pointer;
}
.cc-picker__lens {
  color: var(--color-fg-3, #6b7280);
}
.cc-picker__clear {
  cursor: pointer;
  color: var(--color-fg-4, #9ca3af);
}
.cc-picker__clear:hover {
  color: var(--color-fg-2, #4b5563);
}
.cc-picker.is-disabled .cc-picker__trigger :deep(.el-input__inner),
.cc-picker.is-disabled .cc-picker__trigger :deep(.el-input__wrapper) {
  cursor: not-allowed;
}
</style>

<!-- 弹窗 append-to-body 挂到 body，需非 scoped 样式；375 窄屏宽度收敛 -->
<style>
.el-dialog.cc-picker-dialog {
  width: 680px;
  max-width: calc(100vw - 24px);
}
.cc-picker-dialog__search {
  display: flex;
  gap: var(--space-3, 12px);
  margin-bottom: var(--space-4, 16px);
}
.cc-picker-dialog__table {
  width: 100%;
}
.cc-picker-dialog__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3, 12px);
  margin-top: var(--space-4, 16px);
}
.cc-picker-dialog__count {
  font-size: 12px;
  color: var(--color-fg-3, #6b7280);
}
@media (max-width: 768px) {
  .el-dialog.cc-picker-dialog {
    width: calc(100vw - 24px);
  }
  .cc-picker-dialog__pager {
    justify-content: center;
    flex-wrap: wrap;
  }
}
</style>
