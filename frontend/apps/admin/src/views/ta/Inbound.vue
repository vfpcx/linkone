<script setup lang="ts">
/**
 * WK 入库登记（PC）— phase-1 C1 仓管员登记入库
 *
 * 来源：
 *  - 契约：backend/.../document/controller/InboundController.java
 *      POST /api/v1/tenant/inbound   {wholesalerId, skuId, qty, palletQty?}  登记入库（单事务：建单 + 增库存）
 *      GET  /api/v1/tenant/inbound?wholesalerId=   列出本租户入库单
 *    选商户/SKU 复用：wholesalerApi.list / skuApi.list?wholesalerId=
 *  - 视觉：沿用 Skus.vue 的顶栏 + 左侧菜单 shell + el-table/el-form 风格
 *
 * 范围：仅 WK 入库登记（选商户 → 选 SKU → 数量/托盘数 → 登记 → 刷新记录），
 *       不碰出库/询价/审批。归属/tenantId 由后端登录态推导，前端只传 4 字段。
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
  Box,
} from '@element-plus/icons-vue'
import type { Wholesaler, Sku, InboundRequest, InboundRegisterRequest } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { wholesalerApi } from '@/api/wholesaler'
import { skuApi } from '@/api/sku'
import { inboundApi } from '@/api/inbound'
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
const activeMenu = ref('/ta/inbound')

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
  { key: '/ta/inbound', label: '入库', icon: Box },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '单据审批', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/inbound') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/wholesalers' ||
    key === '/ta/employees' ||
    key === '/ta/skus'
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

/** 商户 id → 名称，供入库记录表回显（VO 只回 wholesalerId） */
const wholesalerNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const w of wholesalers.value) map[String(w.id)] = w.name
  return map
})

const fetchWholesalers = async () => {
  wholesalerLoading.value = true
  try {
    wholesalers.value = await wholesalerApi.list()
    if (!selectedWholesalerId.value && wholesalers.value.length > 0) {
      selectedWholesalerId.value = String(wholesalers.value[0].id)
      await onWholesalerChange()
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    wholesalerLoading.value = false
  }
}

const onWholesalerChange = async () => {
  // 换商户：重置已选 SKU，重新拉该商户 SKU 与入库记录
  form.skuId = ''
  await Promise.all([fetchSkus(), fetchRecords()])
}

// ============ SKU 选择器（选定商户后拉其在售/全部 SKU） ============
const skuLoading = ref(false)
const skus = ref<Sku[]>([])

/** SKU id → 名称，供入库记录表回显（VO 只回 skuId） */
const skuNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of skus.value) map[String(s.id)] = s.name
  return map
})

const fetchSkus = async () => {
  if (!selectedWholesalerId.value) {
    skus.value = []
    return
  }
  skuLoading.value = true
  try {
    skus.value = await skuApi.list(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  } finally {
    skuLoading.value = false
  }
}

// ============ 入库记录表 ============
const recordsLoading = ref(false)
const records = ref<InboundRequest[]>([])

const fetchRecords = async () => {
  if (!selectedWholesalerId.value) {
    records.value = []
    return
  }
  recordsLoading.value = true
  try {
    records.value = await inboundApi.list(selectedWholesalerId.value)
  } catch {
    // 全局 toast 已提示
  } finally {
    recordsLoading.value = false
  }
}

const formatTime = (v: string | null): string => {
  if (!v) return '—'
  // 后端 createdAt 为 LocalDateTime（无时区偏移），直接格式化本地展示串，不做时区转换
  return String(v).replace('T', ' ').slice(0, 19)
}

// ============ 登记入库表单 ============
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  skuId: '' as string,
  qty: undefined as number | undefined,
  palletQty: undefined as number | undefined,
})

const rules: FormRules = {
  skuId: [{ required: true, message: '请选择商品 SKU', trigger: 'change' }],
  qty: [
    { required: true, message: '请输入入库数量', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || (v as unknown) === '') {
          cb(new Error('请输入入库数量'))
        } else if (!Number.isInteger(Number(v))) {
          cb(new Error('入库数量必须为整数'))
        } else if (Number(v) <= 0) {
          cb(new Error('入库数量必须大于 0'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  palletQty: [
    {
      validator: (_r, v, cb) => {
        if (v === undefined || v === null || (v as unknown) === '') {
          cb()
        } else if (!Number.isInteger(Number(v)) || Number(v) < 0) {
          cb(new Error('托盘数须为不小于 0 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}

const resetForm = () => {
  form.qty = undefined
  form.palletQty = undefined
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
    const payload: InboundRegisterRequest = {
      wholesalerId: selectedWholesalerId.value,
      skuId: form.skuId,
      qty: Number(form.qty),
    }
    if (form.palletQty !== undefined && form.palletQty !== null) {
      payload.palletQty = Number(form.palletQty)
    }

    const created = await inboundApi.register(payload)
    const stockTip =
      created.currentStock !== null && created.currentStock !== undefined
        ? `，当前库存 ${created.currentStock}`
        : ''
    ElMessage.success(`入库登记成功（单号 ${created.docNo}）${stockTip}`)
    resetForm()
    await fetchRecords()
  } catch {
    // 全局 toast 已提示（40x 校验 / 50270-50274 状态类）
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
            <h2 class="page-head__title">入库登记</h2>
            <p class="page-head__sub">仓管员为商户 SKU 登记入库数量，登记后库存实时增加</p>
          </div>
        </header>

        <!-- 商户选择器 -->
        <section class="card">
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

          <!-- 登记入库表单 -->
          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            class="inbound-form"
            @submit.prevent="onSubmit"
          >
            <div class="inbound-form__row">
              <el-form-item label="商品 SKU" prop="skuId" class="inbound-form__item">
                <el-select
                  v-model="form.skuId"
                  placeholder="请选择商品"
                  :loading="skuLoading"
                  :disabled="!selectedWholesalerId"
                  filterable
                  class="full-width"
                >
                  <el-option
                    v-for="s in skus"
                    :key="String(s.id)"
                    :label="s.spec ? `${s.name}（${s.spec}）` : s.name"
                    :value="String(s.id)"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="入库数量" prop="qty" class="inbound-form__item">
                <el-input-number
                  v-model="form.qty"
                  :min="1"
                  :precision="0"
                  :step="1"
                  :controls="false"
                  placeholder="必填，大于 0 的整数"
                  class="full-width"
                />
              </el-form-item>

              <el-form-item label="托盘数（可选）" prop="palletQty" class="inbound-form__item">
                <el-input-number
                  v-model="form.palletQty"
                  :min="0"
                  :precision="0"
                  :step="1"
                  :controls="false"
                  placeholder="本次托盘数，默认 0"
                  class="full-width"
                />
              </el-form-item>

              <el-form-item label=" " class="inbound-form__item inbound-form__submit">
                <el-button
                  type="primary"
                  :icon="Box"
                  :loading="submitting"
                  :disabled="!selectedWholesalerId"
                  @click="onSubmit"
                >
                  登记入库
                </el-button>
              </el-form-item>
            </div>
          </el-form>
        </section>

        <!-- 入库记录表 -->
        <section class="card">
          <div class="card__head">
            <h3 class="card__title">入库记录</h3>
            <el-button text :loading="recordsLoading" @click="fetchRecords">刷新</el-button>
          </div>
          <el-table
            v-loading="recordsLoading"
            :data="records"
            stripe
            class="inbound-table"
            empty-text="该商户暂无入库记录，登记后将在此显示"
          >
            <el-table-column prop="docNo" label="入库单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="商户" min-width="140">
              <template #default="{ row }">
                <span class="cell-muted">
                  {{ wholesalerNameMap[String(row.wholesalerId)] || row.wholesalerId }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="商品 SKU" min-width="160">
              <template #default="{ row }">
                <span>{{ skuNameMap[String(row.skuId)] || row.skuId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-name">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="托盘数" width="100" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.palletQty ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="登记时间" width="180">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>
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

/* ===== 卡片 + 工具栏 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}
.card__title {
  margin: 0;
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-bold);
  color: var(--color-fg-1);
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

/* ===== 登记表单 ===== */
.inbound-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: flex-end;
}
.inbound-form__item {
  flex: 1 1 200px;
  margin-bottom: 0;
}
.inbound-form__submit {
  flex: 0 0 auto;
}

/* ===== 记录表 ===== */
.inbound-table {
  width: 100%;
}
.cell-name {
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
  .inbound-form__item {
    flex: 1 1 100%;
  }
}
</style>
