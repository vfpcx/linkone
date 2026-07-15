<script setup lang="ts">
/**
 * TA 入驻审批（PC）— P2 入驻生态 Wave4 前端第一批
 *
 * 来源：
 *  - 契约：GET  /api/v1/tenant/wholesaler-applications?status=&page=&size=（分页列表）
 *          POST /api/v1/tenant/wholesaler-applications/{id}/audit（APPROVED / REJECTED，驳回 remark 必填）
 *  - 视觉：沿用 Employees.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格；
 *          菜单计数徽标用 NavCountBadge（MASTER §4.11，禁 el-badge 上标）。
 *
 * 范围：状态 Tab（待审核/已通过/已驳回）+ 通过确认 + 驳回弹窗（理由必填）。
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
  Stamp,
  Refresh,
} from '@element-plus/icons-vue'
import { StatusBadge, NavCountBadge } from '@cangchu/ui-shared'
import type { WaApplicationStatus, WholesalerApplication } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { tenantApi } from '@/api/tenant'
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
const activeMenu = ref('/ta/wholesaler-applications')

/** 待审核数（菜单徽标 + Tab 徽标共用） */
const pendingTotal = ref(0)

interface MenuItem {
  key: string
  label: string
  icon: typeof Shop
  badge?: number
}

const menus = computed<MenuItem[]>(() => [
  { key: '/ta/dashboard', label: '工作台', icon: TrendCharts },
  { key: '/ta/settings', label: '店铺设置', icon: Setting },
  { key: '/ta/employees', label: '员工', icon: User },
  { key: '/ta/wholesalers', label: '入驻商户', icon: Shop },
  {
    key: '/ta/wholesaler-applications',
    label: '入驻审批',
    icon: Stamp,
    badge: pendingTotal.value,
  },
  { key: '/ta/skus', label: '商品', icon: Goods },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '单据审批', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
])

const ROUTABLE = new Set([
  '/ta/dashboard',
  '/ta/settings',
  '/ta/employees',
  '/ta/wholesalers',
  '/ta/skus',
])

const handleMenuSelect = (key: string) => {
  if (key === '/ta/wholesaler-applications') {
    activeMenu.value = key
    return
  }
  if (ROUTABLE.has(key)) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.value.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 状态 Tab + 列表 ============
const activeTab = ref<WaApplicationStatus>('PENDING')

const loading = ref(false)
const list = ref<WholesalerApplication[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await tenantApi.listWaApplications({
      status: activeTab.value,
      page: page.value,
      size: size.value,
    })
    list.value = data?.list ?? []
    total.value = data?.total ?? 0
    if (activeTab.value === 'PENDING') {
      pendingTotal.value = data?.total ?? 0
    }
  } catch {
    // 全局 toast 已提示；保持已有数据不清空以便重试
  } finally {
    loading.value = false
  }
}

/** 单拉一次待审核数（初始进入非 PENDING Tab 时也能显示菜单徽标） */
const fetchPendingCount = async () => {
  if (activeTab.value === 'PENDING') return
  try {
    const data = await tenantApi.listWaApplications({ status: 'PENDING', page: 1, size: 1 })
    pendingTotal.value = data?.total ?? 0
  } catch {
    /* 静默：徽标非关键 */
  }
}

const onTabChange = () => {
  page.value = 1
  fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  fetchList()
}

// ============ 状态徽章 / 格式化 ============
type BadgeVariant = 'success' | 'warning' | 'danger' | 'default'
const statusMeta = (status: string): { variant: BadgeVariant; text: string } => {
  const map: Record<string, { variant: BadgeVariant; text: string }> = {
    PENDING: { variant: 'warning', text: '待审核' },
    APPROVED: { variant: 'success', text: '已通过' },
    REJECTED: { variant: 'danger', text: '已驳回' },
  }
  return map[status] ?? { variant: 'default', text: status || '—' }
}

const formatTime = (iso?: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const emptyText = computed(() => {
  const map: Record<WaApplicationStatus, string> = {
    PENDING: '暂无待审核的入驻申请',
    APPROVED: '暂无已通过的入驻申请',
    REJECTED: '暂无已驳回的入驻申请',
  }
  return map[activeTab.value]
})

// ============ 通过 ============
const auditingId = ref('')

const onApprove = async (row: WholesalerApplication) => {
  try {
    await ElMessageBox.confirm(
      `确认通过「${row.wholesalerName}」的入驻申请？通过后该商户即可在本仓库上架商品、接收询价。`,
      '通过确认',
      {
        confirmButtonText: '通过',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  auditingId.value = String(row.applicationId)
  try {
    await tenantApi.auditWaApplication(String(row.applicationId), { action: 'APPROVED' })
    ElMessage.success(`已通过「${row.wholesalerName}」的入驻申请`)
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    auditingId.value = ''
  }
}

// ============ 驳回弹窗（理由必填） ============
const rejectVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectFormRef = ref<FormInstance>()
const rejectTarget = ref<WholesalerApplication | null>(null)

const rejectForm = reactive({ remark: '' })

const rejectRules: FormRules = {
  remark: [
    { required: true, message: '请填写驳回理由', trigger: 'blur' },
    { min: 2, max: 512, message: '驳回理由为 2-512 字', trigger: 'blur' },
  ],
}

const openReject = (row: WholesalerApplication) => {
  rejectTarget.value = row
  rejectForm.remark = ''
  rejectVisible.value = true
  rejectFormRef.value?.clearValidate()
}

const onRejectSubmit = async () => {
  if (!rejectFormRef.value || !rejectTarget.value) return
  const valid = await rejectFormRef.value.validate().catch(() => false)
  if (!valid) return

  rejectSubmitting.value = true
  try {
    await tenantApi.auditWaApplication(String(rejectTarget.value.applicationId), {
      action: 'REJECTED',
      remark: rejectForm.remark.trim(),
    })
    ElMessage.success(`已驳回「${rejectTarget.value.wholesalerName}」的入驻申请`)
    rejectVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    rejectSubmitting.value = false
  }
}

onMounted(async () => {
  await fetchList()
  await fetchPendingCount()
})
</script>

<template>
  <div class="ta-shell">
    <!-- 顶栏 -->
    <header class="ta-topbar">
      <div class="ta-topbar__left">
        <span class="ta-topbar__brand">仓储云</span>
        <span class="ta-topbar__divider">·</span>
        <WarehouseSwitcher />
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
            <NavCountBadge :count="m.badge ?? 0" class="ta-side__badge" />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ta-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">入驻审批</h2>
            <p class="page-head__sub">
              审批批发商的入驻申请：通过后商户即可上架经营，驳回需填写理由
            </p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <section class="card">
          <el-tabs v-model="activeTab" class="app-tabs" @tab-change="onTabChange">
            <el-tab-pane name="PENDING">
              <template #label>
                <span class="app-tabs__label">
                  待审核
                  <NavCountBadge :count="pendingTotal" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已通过" name="APPROVED" />
            <el-tab-pane label="已驳回" name="REJECTED" />
          </el-tabs>

          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="app-table"
            :empty-text="emptyText"
          >
            <el-table-column label="商户名" min-width="160">
              <template #default="{ row }">
                <span class="cell-name">{{ row.wholesalerName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="联系人" width="110">
              <template #default="{ row }">{{ row.contactName || '—' }}</template>
            </el-table-column>
            <el-table-column label="联系电话" width="140">
              <template #default="{ row }">
                <span class="cell-code">{{ row.contactPhone || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="营业执照号" min-width="170">
              <template #default="{ row }">
                <span class="cell-code cell-muted">{{ row.licenseNo || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.appliedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <StatusBadge
                  :variant="statusMeta(row.status).variant"
                  :text="statusMeta(row.status).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column
              v-if="activeTab === 'REJECTED'"
              label="驳回理由"
              min-width="180"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="cell-muted">{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column
              v-if="activeTab === 'PENDING'"
              label="操作"
              width="150"
              fixed="right"
            >
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :loading="auditingId === String(row.applicationId)"
                  @click="onApprove(row)"
                >
                  通过
                </el-button>
                <el-button link type="danger" @click="openReject(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="total > size" class="app-pager">
            <el-pagination
              :current-page="page"
              :page-size="size"
              :total="total"
              layout="total, prev, pager, next"
              background
              @current-change="onPageChange"
            />
          </div>
        </section>
      </main>
    </div>

    <!-- 驳回弹窗（理由必填） -->
    <el-dialog
      v-model="rejectVisible"
      title="驳回入驻申请"
      width="480px"
      :close-on-click-modal="false"
    >
      <p class="reject-tip">
        驳回「<span class="cell-name">{{ rejectTarget?.wholesalerName }}</span
        >」的入驻申请，理由将通过短信告知申请人。
      </p>
      <el-form
        ref="rejectFormRef"
        :model="rejectForm"
        :rules="rejectRules"
        label-position="top"
        @submit.prevent="onRejectSubmit"
      >
        <el-form-item label="驳回理由" prop="remark">
          <el-input
            v-model="rejectForm.remark"
            type="textarea"
            :rows="4"
            maxlength="512"
            show-word-limit
            placeholder="请填写驳回理由（必填），如：资质材料不全，请补充营业执照后重新申请"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectSubmitting" @click="onRejectSubmit">
          确认驳回
        </el-button>
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
/* NavCountBadge 在菜单行内靠右（MASTER §4.11） */
.ta-side__badge {
  margin-left: auto;
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

/* ===== 卡片 + Tab + 表格 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.app-tabs {
  margin-bottom: var(--space-2);
}
.app-tabs__label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
.app-table {
  width: 100%;
}
.app-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.cell-code {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
}

.reject-tip {
  margin: 0 0 var(--space-4);
  color: var(--color-fg-2);
  font-size: var(--font-size-body);
  line-height: var(--line-height-normal);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
}
</style>
