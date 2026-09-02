<script setup lang="ts">
/**
 * OPS 平台标品库（PC）— P5-D D56 · 22-p5-d56-catalog-design §3.1/§6（US-OPS-02）
 *
 * 契约（backend product/OpsSpuController，requireOps 42002）：
 *  - GET  /ops/spus?page=&size=&keyword=&categoryL1=&categoryL2=&status=  分页列表（含引用 SKU 数）
 *  - POST /ops/spus                         新增标品（两级品类下拉 + 编码可填可自动）
 *  - POST /ops/spus/{id}/offline            下架（ACTIVE→OFFLINE，存量引用保留）
 *  - POST /ops/spus/{id}/merge?targetSpuId= 合并（源→MERGED，引用 SKU 原子重指 + 快照刷新）
 *  - GET  /ops/spus/spu-categories          两级品类字典（预置 seed）
 *
 * 展示规范：状态英文码一律映射中文（ACTIVE=在用 / OFFLINE=已下架 / MERGED=已合并），禁直显。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Monitor,
  CircleClose,
  ScaleToOriginal,
  Stamp,
  Bell,
  Goods,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { AppTopbar } from '@cangchu/ui-shared'
import type { Spu, SpuCategoryGroup } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { opsApi } from '@/api/ops'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const handleSwitchRole = () => auth.showSwitcher()

const handleProfileMenu = async (key: string) => {
  switch (key) {
    case 'profile':
      ElMessage.info('个人资料页留给后续 Agent 实现')
      break
    case 'security':
      ElMessage.info('安全设置页留给后续 Agent 实现')
      break
    case 'logout':
      try {
        await ElMessageBox.confirm('确认退出登录？', '退出确认', {
          confirmButtonText: '退出',
          cancelButtonText: '取消',
          type: 'warning',
        })
        await accountApi.logout().catch(() => undefined)
        auth.clear()
        router.replace('/login')
      } catch {
        /* cancel */
      }
      break
  }
}

// ============ 菜单（OPS 端 6 项统一锚点：运营控制台/租户审核/黑名单/公告管理/标品库/客诉仲裁） ============
const activeMenu = ref('/ops/spu-catalog')

const menus = [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor },
  { key: '/ops/tenant-audit', label: '租户审核', icon: Stamp },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose },
  { key: '/ops/announcements', label: '公告管理', icon: Bell },
  { key: '/ops/spu-catalog', label: '标品库', icon: Goods },
  { key: '/ops/arbitrations', label: '客诉仲裁', icon: ScaleToOriginal },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ops/spu-catalog') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ops/dashboard' ||
    key === '/ops/tenant-audit' ||
    key === '/ops/blacklist' ||
    key === '/ops/announcements' ||
    key === '/ops/arbitrations'
  ) {
    router.push(key)
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 品类字典（两级联动同源） ============
const categories = ref<SpuCategoryGroup[]>([])

const categoryL2Options = (l1: string) => categories.value.find((c) => c.l1 === l1)?.l2s ?? []

const fetchCategories = async () => {
  try {
    categories.value = await opsApi.listSpuCategories()
  } catch {
    // 全局 toast 已提示
  }
}

// ============ 列表 ============
const loading = ref(false)
const list = ref<Spu[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)

const filters = reactive({
  keyword: '',
  categoryL1: '',
  categoryL2: '',
  status: '' as '' | 'ACTIVE' | 'OFFLINE' | 'MERGED',
})

const onCategoryL1Change = () => {
  filters.categoryL2 = ''
}

const fetchList = async () => {
  loading.value = true
  try {
    const data = await opsApi.listSpus({
      page: page.value,
      size: size.value,
      keyword: filters.keyword || undefined,
      categoryL1: filters.categoryL1 || undefined,
      categoryL2: filters.categoryL2 || undefined,
      status: filters.status || undefined,
    })
    list.value = data?.records ?? []
    total.value = data?.total ?? 0
    if (data?.page) page.value = data.page
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onSearch = () => {
  page.value = 1
  void fetchList()
}

const onReset = () => {
  filters.keyword = ''
  filters.categoryL1 = ''
  filters.categoryL2 = ''
  filters.status = ''
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

/** 状态中文映射（展示规范：禁直显英文码） */
const statusMeta: Record<string, { text: string; type: 'success' | 'info' | 'warning' }> = {
  ACTIVE: { text: '在用', type: 'success' },
  OFFLINE: { text: '已下架', type: 'info' },
  MERGED: { text: '已合并', type: 'warning' },
}

const fmtDate = (v: string | undefined) => (v ? String(v).slice(0, 10) : '—')

// ============ 新增 ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '',
  categoryL1: '',
  categoryL2: '',
  brand: '',
  spuCode: '',
  note: '',
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入标品名称', trigger: 'blur' },
    { max: 128, message: '名称最多 128 字', trigger: 'blur' },
  ],
  categoryL1: [{ required: true, message: '请选择一级品类', trigger: 'change' }],
  categoryL2: [{ required: true, message: '请选择二级品类', trigger: 'change' }],
}

const formL2Options = computed(() => categoryL2Options(form.categoryL1))

const onFormL1Change = () => {
  form.categoryL2 = ''
}

const resetForm = () => {
  form.name = ''
  form.categoryL1 = ''
  form.categoryL2 = ''
  form.brand = ''
  form.spuCode = ''
  form.note = ''
}

const openCreate = () => {
  resetForm()
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await opsApi.createSpu({
      name: form.name.trim(),
      categoryL1: form.categoryL1,
      categoryL2: form.categoryL2,
      brand: form.brand.trim() || undefined,
      spuCode: form.spuCode.trim() || undefined,
      note: form.note.trim() || undefined,
    })
    ElMessage.success('标品已创建（ACTIVE，全平台可见）')
    dialogVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    submitting.value = false
  }
}

// ============ 下架 ============
const onOffline = async (row: Spu) => {
  try {
    await ElMessageBox.confirm(
      `确认下架「${row.name}」？\n下架后不再提供新挂接，已挂接 SKU 不受影响（仍可售）。`,
      '下架确认',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await opsApi.offlineSpu(String(row.id))
    ElMessage.success('已下架')
    await fetchList()
  } catch {
    // 全局 toast 已提示
  }
}

// ============ 合并 ============
const mergeDialogVisible = ref(false)
const mergeSource = ref<Spu | null>(null)
const mergeKeyword = ref('')
const mergeTargetOptions = ref<Spu[]>([])
const mergeTargetId = ref('')
const mergeLoading = ref(false)

const searchMergeTargets = async () => {
  mergeLoading.value = true
  try {
    const data = await opsApi.listSpus({
      page: 1,
      size: 10,
      keyword: mergeKeyword.value || undefined,
      status: 'ACTIVE',
    })
    mergeTargetOptions.value =
      data?.records.filter((s) => String(s.id) !== String(mergeSource.value?.id)) ?? []
  } catch {
    mergeTargetOptions.value = []
  } finally {
    mergeLoading.value = false
  }
}

const openMerge = async (row: Spu) => {
  mergeSource.value = row
  mergeTargetId.value = ''
  mergeKeyword.value = ''
  mergeDialogVisible.value = true
  await searchMergeTargets()
}

const onMergeConfirm = async () => {
  const source = mergeSource.value
  if (!source) return
  if (!mergeTargetId.value) {
    ElMessage.warning('请选择合并目标标品')
    return
  }
  const target = mergeTargetOptions.value.find((s) => String(s.id) === mergeTargetId.value)
  try {
    await ElMessageBox.confirm(
      `确认将「${source.name}」合并至「${target?.name ?? ''}」？\n` +
        `引用「${source.name}」的全部 SKU（${source.referencedSkuCount} 个）将自动指向目标并刷新快照，原标品标记为已合并（历史保留）。`,
      '合并确认',
      { confirmButtonText: '合并', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await opsApi.mergeSpu(String(source.id), mergeTargetId.value)
    ElMessage.success('合并成功')
    mergeDialogVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示
  }
}

onMounted(async () => {
  await fetchCategories()
  await fetchList()
})
</script>

<template>
  <div class="ops-shell">
    <!-- 顶栏 -->
    <AppTopbar
      store-name="平台运营"
      avatar-text="O"
      @switch-role="handleSwitchRole"
      @profile-command="handleProfileMenu"
    />

    <div class="ops-body">
      <!-- 左侧菜单 -->
      <aside class="ops-side">
        <el-menu :default-active="activeMenu" class="ops-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ops-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">平台标品库</h2>
            <p class="page-head__sub">全平台统一商品档案：创建 / 下架 / 合并，SKU 可挂接统一标品</p>
          </div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新增标品</el-button>
        </header>

        <section class="card">
          <!-- 搜索 -->
          <div class="toolbar">
            <el-input
              v-model="filters.keyword"
              placeholder="名称 / 平台编码"
              clearable
              class="toolbar__kw"
              :prefix-icon="Search"
              @keyup.enter="onSearch"
              @clear="onSearch"
            />
            <el-select v-model="filters.categoryL1" placeholder="一级品类" clearable class="toolbar__sel" @change="onCategoryL1Change">
              <el-option v-for="c in categories" :key="c.l1" :label="c.l1" :value="c.l1" />
            </el-select>
            <el-select v-model="filters.categoryL2" placeholder="二级品类" clearable class="toolbar__sel">
              <el-option v-for="l2 in categoryL2Options(filters.categoryL1)" :key="l2" :label="l2" :value="l2" />
            </el-select>
            <el-select v-model="filters.status" placeholder="状态" clearable class="toolbar__sel">
              <el-option label="在用" value="ACTIVE" />
              <el-option label="已下架" value="OFFLINE" />
              <el-option label="已合并" value="MERGED" />
            </el-select>
            <el-button type="primary" plain :icon="Search" @click="onSearch">查询</el-button>
            <el-button :icon="Refresh" @click="onReset">重置</el-button>
          </div>

          <!-- 列表 -->
          <el-table v-loading="loading" :data="list" stripe empty-text="暂无标品，点击右上角「新增标品」创建">
            <el-table-column prop="spuCode" label="平台编码" width="150">
              <template #default="{ row }">
                <span class="cell-code">{{ row.spuCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="标品名称" min-width="200">
              <template #default="{ row }">
                <span class="cell-name">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="品类" min-width="140">
              <template #default="{ row }">
                <span>{{ row.categoryL1 }} / {{ row.categoryL2 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="品牌" width="120">
              <template #default="{ row }">{{ row.brand || '—' }}</template>
            </el-table-column>
            <el-table-column label="引用 SKU" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.referencedSkuCount ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusMeta[row.status]?.type ?? 'info'" size="small">
                  {{ statusMeta[row.status]?.text ?? row.status }}
                </el-tag>
                <div v-if="row.status === 'MERGED'" class="cell-muted cell-tiny">
                  引用已并入新主标品
                </div>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="110">
              <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'ACTIVE'">
                  <el-button link type="primary" @click="openMerge(row as Spu)">合并</el-button>
                  <el-button link type="danger" @click="onOffline(row as Spu)">下架</el-button>
                </template>
                <span v-else-if="row.status === 'MERGED'" class="cell-muted">已合并</span>
                <span v-else class="cell-muted">已下架</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              layout="total, prev, pager, next"
              :total="total"
              :page-size="size"
              :current-page="page"
              background
              @current-change="onPageChange"
            />
          </div>
        </section>
      </main>
    </div>

    <!-- 新增标品 -->
    <el-dialog v-model="dialogVisible" title="新增标品" width="520px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="标品名称" prop="name">
          <el-input v-model="form.name" placeholder="如：金龙鱼 5L 花生油" maxlength="128" show-word-limit />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="一级品类" prop="categoryL1">
              <el-select v-model="form.categoryL1" placeholder="选择一级品类" class="full-width" @change="onFormL1Change">
                <el-option v-for="c in categories" :key="c.l1" :label="c.l1" :value="c.l1" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="二级品类" prop="categoryL2">
              <el-select v-model="form.categoryL2" placeholder="选择二级品类" class="full-width">
                <el-option v-for="l2 in formL2Options" :key="l2" :label="l2" :value="l2" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="品牌（可选）">
          <el-input v-model="form.brand" placeholder="如：金龙鱼" maxlength="64" />
        </el-form-item>
        <el-form-item label="平台编码（可选，留空自动生成）">
          <el-input v-model="form.spuCode" placeholder="留空将自动生成 GSPU-xxx" maxlength="32" />
        </el-form-item>
        <el-form-item label="备注（可选）">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="256" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">创建</el-button>
      </template>
    </el-dialog>

    <!-- 合并（选目标 ACTIVE 标品） -->
    <el-dialog v-model="mergeDialogVisible" title="合并标品" width="520px" :close-on-click-modal="false">
      <div class="merge-hint">
        将
        <b>{{ mergeSource?.name }}</b>
        合并至以下目标标品。引用
        <b>{{ mergeSource?.referencedSkuCount ?? 0 }}</b>
        个 SKU 将自动重指并刷新快照，原标品标记「已合并」（历史保留）。
      </div>
      <el-input
        v-model="mergeKeyword"
        placeholder="搜索目标标品（名称 / 编码）"
        clearable
        :prefix-icon="Search"
        class="merge-search"
        @input="searchMergeTargets"
      />
      <el-select v-model="mergeTargetId" placeholder="选择目标标品" class="full-width" filterable>
        <el-option
          v-for="s in mergeTargetOptions"
          :key="s.id"
          :label="`${s.name}（${s.categoryL1}/${s.categoryL2}）`"
          :value="s.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="mergeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeLoading" @click="onMergeConfirm">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ops-shell {
  min-height: 100vh;
  background: var(--color-page-bg, #f5f7fa);
}
.ops-body {
  display: flex;
  min-height: calc(100vh - 56px);
}
.ops-side {
  width: 220px;
  background: var(--color-panel-bg, #fff);
  border-right: 1px solid var(--color-border, #ebeef5);
  padding-top: 8px;
  flex-shrink: 0;
}
.ops-side__menu {
  border-right: none;
}
.ops-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
}
.ops-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
.ops-main {
  flex: 1;
  padding: 24px 28px 40px;
  overflow-y: auto;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-head__title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
.page-head__sub {
  margin: 6px 0 0;
  color: var(--color-text-secondary, #909399);
  font-size: 13px;
}
.card {
  background: var(--color-panel-bg, #fff);
  border: 1px solid var(--color-border, #ebeef5);
  border-radius: 10px;
  padding: 16px 16px 8px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar__kw {
  width: 220px;
}
.toolbar__sel {
  width: 140px;
}
.full-width {
  width: 100%;
}
.cell-name {
  font-weight: 600;
}
.cell-code {
  font-family: var(--font-mono, monospace);
  color: var(--color-text-secondary, #909399);
}
.cell-muted {
  color: var(--color-text-secondary, #909399);
}
.cell-tiny {
  font-size: 12px;
  margin-top: 2px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 8px 0 12px;
}
.merge-hint {
  color: var(--color-text-secondary, #606266);
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 12px;
}
.merge-search {
  margin-bottom: 10px;
}
</style>
