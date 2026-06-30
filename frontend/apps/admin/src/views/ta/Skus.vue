<script setup lang="ts">
/**
 * TA 商品 SKU 管理（PC）— phase-1 D1a 卖家侧上架
 *
 * 来源：
 *  - 契约：backend/.../product/controller/SkuController.java
 *      GET  /tenant/skus?wholesalerId=          商户 SKU 列表（含下架）
 *      POST /tenant/skus?wholesalerId=          上架 SKU（name + unitPrice 必填）
 *      PUT  /tenant/skus/{id}/listing?on=       上下架
 *  - 视觉：沿用 Wholesalers.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格
 *
 * 范围：仅 SKU 管理（选商户 → 列表 → 上架 → 上下架），不碰入库/询价/RT。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Shop,
  User,
  Document,
  Coin,
  ChatLineSquare,
  Setting,
  TrendCharts,
  Goods,
  Plus,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type { Wholesaler, Sku, CreateSkuRequest } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(() => auth.currentStoreName || '我的店铺')

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

// ============ 菜单 ============
const activeMenu = ref('/ta/skus')

interface MenuItem {
  key: string
  label: string
  icon: typeof Shop
}

const menus: MenuItem[] = [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  { key: '/ta/skus', label: '商品', icon: Goods },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '单据审批', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/skus') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees'
  ) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 商户选择器 ============
const wholesalerLoading = ref(false)
const wholesalers = ref<Wholesaler[]>([])
const selectedWholesalerId = ref<string>('')

const fetchWholesalers = async () => {
  wholesalerLoading.value = true
  try {
    wholesalers.value = await wholesalerApi.list()
    // 默认选中第一个商户
    if (!selectedWholesalerId.value && wholesalers.value.length > 0) {
      selectedWholesalerId.value = String(wholesalers.value[0].id)
      await fetchSkus()
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    wholesalerLoading.value = false
  }
}

const onWholesalerChange = () => {
  void fetchSkus()
}

// ============ SKU 列表 ============
const loading = ref(false)
const list = ref<Sku[]>([])

const fetchSkus = async () => {
  if (!selectedWholesalerId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    list.value = await skuApi.list(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const formatPrice = (v: number | null): string => {
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return `¥${n.toFixed(2)}`
}

// ============ 上下架切换 ============
const togglingId = ref<string>('')

const onToggleListing = async (row: Sku) => {
  const next = !row.listed
  togglingId.value = String(row.id)
  try {
    const updated = await skuApi.toggleListing(String(row.id), next)
    row.listed = updated.listed
    ElMessage.success(next ? '已上架' : '已下架')
  } catch {
    // 全局 toast 已提示；状态不回写（保持原值）
  } finally {
    togglingId.value = ''
  }
}

// ============ 上架对话框 ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '',
  spec: '',
  unitPrice: undefined as number | undefined,
  moqPrice: undefined as number | undefined,
  moqQty: undefined as number | undefined,
  mainImage: '',
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入商品名称', trigger: 'blur' },
    { max: 128, message: '商品名称最多 128 字', trigger: 'blur' },
  ],
  unitPrice: [
    { required: true, message: '请输入单价', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || v === '') {
          cb(new Error('请输入单价'))
        } else if (Number(v) <= 0) {
          cb(new Error('单价必须大于 0'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}

const resetForm = () => {
  form.name = ''
  form.spec = ''
  form.unitPrice = undefined
  form.moqPrice = undefined
  form.moqQty = undefined
  form.mainImage = ''
}

const openCreate = () => {
  resetForm()
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  if (!selectedWholesalerId.value) {
    ElMessage.warning('请先选择商户')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: CreateSkuRequest = {
      name: form.name.trim(),
      unitPrice: Number(form.unitPrice),
    }
    if (form.spec.trim()) payload.spec = form.spec.trim()
    if (form.moqPrice !== undefined && form.moqPrice !== null)
      payload.moqPrice = Number(form.moqPrice)
    if (form.moqQty !== undefined && form.moqQty !== null)
      payload.moqQty = Number(form.moqQty)
    if (form.mainImage.trim()) payload.mainImage = form.mainImage.trim()

    await skuApi.create(selectedWholesalerId.value, payload)
    ElMessage.success('SKU 上架成功')
    dialogVisible.value = false
    await fetchSkus()
  } catch {
    // 全局 toast 已提示
  } finally {
    submitting.value = false
  }
}

onMounted(fetchWholesalers)
</script>

<template>
  <div class="ta-shell">
    <!-- 顶栏 -->
    <header class="ta-topbar">
      <div class="ta-topbar__left">
        <span class="ta-topbar__brand">仓储云</span>
        <span class="ta-topbar__divider">·</span>
        <span class="ta-topbar__store">{{ storeNameDisplay }}</span>
      </div>

      <div class="ta-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>
        <el-button text :icon="Bell" class="ta-topbar__bell" />
        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="ta-topbar__user">
            <el-avatar :size="28">U</el-avatar>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人资料</el-dropdown-item>
              <el-dropdown-item command="security">安全设置</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="ta-body">
      <!-- 左侧菜单 -->
      <aside class="ta-side">
        <el-menu :default-active="activeMenu" class="ta-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">商品管理</h2>
            <p class="page-head__sub">为商户上架 SKU、维护价格，并控制上下架状态</p>
          </div>
          <el-button
            type="primary"
            :icon="Plus"
            :disabled="!selectedWholesalerId"
            @click="openCreate"
          >
            上架 SKU
          </el-button>
        </header>

        <section class="card">
          <!-- 商户选择器 -->
          <div class="toolbar">
            <span class="toolbar__label">商户</span>
            <el-select
              v-model="selectedWholesalerId"
              placeholder="请选择商户"
              :loading="wholesalerLoading"
              class="toolbar__select"
              filterable
              @change="onWholesalerChange"
            >
              <el-option
                v-for="w in wholesalers"
                :key="String(w.id)"
                :label="w.name"
                :value="String(w.id)"
              />
            </el-select>
            <span v-if="!wholesalerLoading && wholesalers.length === 0" class="toolbar__empty">
              当前店铺暂无商户，请先在「入驻商户」创建
            </span>
          </div>

          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="sku-table"
            empty-text="该商户暂无 SKU，点击右上角「上架 SKU」开始"
          >
            <el-table-column prop="name" label="商品名称" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="spec" label="规格" min-width="120">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.spec || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="120" align="right">
              <template #default="{ row }">
                <span class="cell-price">{{ formatPrice(row.unitPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="起批价" width="120" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatPrice(row.moqPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="起批量" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.moqQty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <StatusBadge
                  :variant="row.listed ? 'success' : 'default'"
                  :text="row.listed ? '已上架' : '已下架'"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="上架" width="120" fixed="right">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.listed"
                  :loading="togglingId === String(row.id)"
                  :disabled="togglingId === String(row.id)"
                  @click="onToggleListing(row)"
                />
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 上架 SKU 对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="上架 SKU"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="商品名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="如：东海带鱼（鲜）"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="规格（可选）" prop="spec">
          <el-input v-model="form.spec" placeholder="如：5kg/箱" maxlength="64" />
        </el-form-item>

        <el-form-item label="单价（元）" prop="unitPrice">
          <el-input-number
            v-model="form.unitPrice"
            :min="0.01"
            :precision="2"
            :step="1"
            :controls="false"
            placeholder="必填，大于 0"
            class="full-width"
          />
        </el-form-item>

        <el-form-item label="起批价（元，可选）" prop="moqPrice">
          <el-input-number
            v-model="form.moqPrice"
            :min="0"
            :precision="2"
            :step="1"
            :controls="false"
            placeholder="达起批量时的单价"
            class="full-width"
          />
        </el-form-item>

        <el-form-item label="起批量（可选）" prop="moqQty">
          <el-input-number
            v-model="form.moqQty"
            :min="1"
            :precision="0"
            :step="1"
            :controls="false"
            placeholder="享受起批价的最小数量"
            class="full-width"
          />
        </el-form-item>

        <el-form-item label="主图 URL（可选）" prop="mainImage">
          <el-input v-model="form.mainImage" placeholder="https://..." maxlength="255" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">上架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ta-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏 ===== */
.ta-topbar {
  height: 56px;
  background: var(--color-brand-primary);
  color: var(--color-brand-primary-on);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  position: sticky;
  top: 0;
  z-index: var(--z-fixed);
  box-shadow: var(--shadow-base);
}
.ta-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.ta-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.ta-topbar__divider {
  opacity: 0.5;
}
.ta-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}
.ta-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.ta-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.ta-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.ta-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}
.ta-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.ta-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
}

/* ===== body ===== */
.ta-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.ta-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.ta-side__menu {
  border-right: none;
}
.ta-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ta-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.ta-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.page-head__title {
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
  margin: 0;
}
.page-head__sub {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

/* ===== 卡片 + 工具栏 + 表格 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}
.toolbar__label {
  font-size: var(--font-size-body);
  color: var(--color-fg-2);
  font-weight: var(--font-weight-medium);
}
.toolbar__select {
  width: 260px;
}
.toolbar__empty {
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
}
.sku-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-price {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.full-width {
  width: 100%;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
  .toolbar__select {
    width: 100%;
  }
}
</style>
