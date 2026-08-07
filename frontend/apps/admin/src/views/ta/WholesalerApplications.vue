<script setup lang="ts">
/**
 * TA 入驻审批（PC）— P2 入驻生态 Wave4 前端第一批
 *
 * 来源：
 *  - 契约：GET  /api/v1/tenant/wholesaler-applications?status=&page=&size=（分页列表）
 *          POST /api/v1/tenant/wholesaler-applications/{id}/audit（APPROVED / REJECTED，驳回 remark 必填）
 *          Wave4b 追加（R13 退驻审批，06b §3.3）：
 *          GET  /api/v1/tenant/wholesaler-withdraw-applications?status=&page=&size=
 *          POST /api/v1/tenant/wholesaler-withdraw-applications/{id}/audit
 *  - 视觉：沿用 Employees.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格；
 *          菜单计数徽标用 NavCountBadge（MASTER §4.11，禁 el-badge 上标）。
 *
 * 范围：单据类型切换（入驻申请 / 退驻申请）+ 状态 Tab + 通过确认 + 驳回弹窗（理由必填）。
 * 退驻通过 = 立即生效副作用（SKU 下架/店铺隐藏/专属价失效/踢出登录），确认文案显式提示。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
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
  Van,
  RefreshLeft,
  Checked,
  AlarmClock,
  Remove,
} from '@element-plus/icons-vue'
import { AppTopbar, StatusBadge, NavCountBadge } from '@cangchu/ui-shared'
import type {
  WaApplicationStatus,
  WholesalerApplication,
  WaWithdrawApplication,
  WaWithdrawStatus,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { tenantApi } from '@/api/tenant'
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

// ============ 菜单 ============
const activeMenu = ref('/ta/wholesaler-applications')

/** 入驻待审核数（Tab 徽标） */
const pendingTotal = ref(0)
/** 退驻待审核数（Tab 徽标） */
const wPendingTotal = ref(0)
/** 菜单徽标 = 入驻 + 退驻待审核合计 */
const menuBadgeTotal = computed(() => pendingTotal.value + wPendingTotal.value)

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
    badge: menuBadgeTotal.value,
  },
  { key: '/ta/skus', label: '商品', icon: Goods },
  { key: '/ta/outbound', label: '出库作业', icon: Van },
  { key: '/ta/returns', label: '退货受理', icon: RefreshLeft },
  { key: '/ta/stocktake', label: '盘点', icon: Checked },
  { key: '/ta/batches', label: '批次临期', icon: AlarmClock },
  { key: '/ta/clearance', label: '清库', icon: Remove },
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '审批中心', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
])

const ROUTABLE = new Set([
  '/ta/bills',
  '/ta/dashboard',
  '/ta/returns',
  '/ta/stocktake',
  '/ta/batches',
  '/ta/clearance',
  '/ta/settings',
  '/ta/employees',
  '/ta/wholesalers',
  '/ta/skus',
  '/ta/approvals',
  '/ta/outbound',
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

// ============ 单据类型切换（入驻申请 / 退驻申请） ============
type DocKind = 'APPLY' | 'WITHDRAW'
const docKind = ref<DocKind>('APPLY')

const onDocKindChange = () => {
  if (docKind.value === 'APPLY') {
    page.value = 1
    fetchList()
  } else {
    wPage.value = 1
    fetchWithdrawList()
  }
}

// ============ 入驻申请 · 状态 Tab + 列表 ============
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
    list.value = data?.records ?? []
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

// ============ 退驻申请 · 状态 Tab + 列表（R13 · Wave2 契约） ============
const activeWTab = ref<WaWithdrawStatus>('PENDING')

const wLoading = ref(false)
const wList = ref<WaWithdrawApplication[]>([])
const wTotal = ref(0)
const wPage = ref(1)
const wSize = ref(20)

const fetchWithdrawList = async () => {
  wLoading.value = true
  try {
    const data = await tenantApi.listWaWithdrawApplications({
      status: activeWTab.value,
      page: wPage.value,
      size: wSize.value,
    })
    wList.value = data?.records ?? []
    wTotal.value = data?.total ?? 0
    if (activeWTab.value === 'PENDING') {
      wPendingTotal.value = data?.total ?? 0
    }
  } catch {
    // 后端 Wave2 端点未就绪 / 网络失败 → 保持空列表，空态兜底
  } finally {
    wLoading.value = false
  }
}

/** 单拉一次退驻待审核数（菜单/切换器徽标） */
const fetchWithdrawPendingCount = async () => {
  try {
    const data = await tenantApi.listWaWithdrawApplications({
      status: 'PENDING',
      page: 1,
      size: 1,
    })
    wPendingTotal.value = data?.total ?? 0
  } catch {
    /* 静默：徽标非关键 */
  }
}

const onWTabChange = () => {
  wPage.value = 1
  fetchWithdrawList()
}

const onWPageChange = (p: number) => {
  wPage.value = p
  fetchWithdrawList()
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

/** 退驻状态徽章（APPROVED = 已退驻，语义与入驻不同，单列一套；申请单四值） */
const wStatusMeta = (status: string): { variant: BadgeVariant; text: string } => {
  const map: Record<string, { variant: BadgeVariant; text: string }> = {
    PENDING: { variant: 'warning', text: '待审核' },
    APPROVED: { variant: 'default', text: '已退驻' },
    REJECTED: { variant: 'danger', text: '已驳回' },
    CANCELLED: { variant: 'default', text: '已撤回' },
  }
  return map[status] ?? { variant: 'default', text: status || '—' }
}

const wEmptyText = computed(() => {
  const map: Partial<Record<WaWithdrawStatus, string>> = {
    PENDING: '暂无待审核的退驻申请',
    APPROVED: '暂无已退驻的商户',
    REJECTED: '暂无已驳回的退驻申请',
  }
  return map[activeWTab.value] ?? '暂无退驻申请'
})

// ============ 通过 ============
const auditingId = ref('')

const onApprove = async (row: WholesalerApplication) => {
  try {
    await ElMessageBox.confirm(
      `确认通过「${row.name}」的入驻申请？通过后该商户即可在本仓库上架商品、接收询价。`,
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
  auditingId.value = String(row.id)
  try {
    await tenantApi.auditWaApplication(String(row.id), { action: 'APPROVED' })
    ElMessage.success(`已通过「${row.name}」的入驻申请`)
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    auditingId.value = ''
  }
}

/** 退驻通过 = 立即生效副作用，确认文案显式列出（06b §3.2 同源四要点） */
const onApproveWithdraw = async (row: WaWithdrawApplication) => {
  const name = row.wholesalerName || '该商户'
  try {
    await ElMessageBox.confirm(
      `确认通过「${name}」的退驻申请？通过的瞬间：SKU 全部下架、店铺隐藏、客户专属价失效、该商户及其员工立即退出登录。60 天内商户可申请恢复。`,
      '通过退驻确认',
      {
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  auditingId.value = String(row.id)
  try {
    await tenantApi.auditWaWithdrawApplication(String(row.id), { action: 'APPROVED' })
    ElMessage.success(`已通过「${name}」的退驻申请`)
    await fetchWithdrawList()
  } catch {
    // 全局 toast 已提示（含"前置条件已变化"类 STATE 错误）
  } finally {
    auditingId.value = ''
  }
}

// ============ 驳回弹窗（理由必填，入驻/退驻共用） ============
const rejectVisible = ref(false)
const rejectSubmitting = ref(false)
const rejectFormRef = ref<FormInstance>()
const rejectKind = ref<DocKind>('APPLY')
const rejectTarget = ref<WholesalerApplication | WaWithdrawApplication | null>(null)

const rejectTargetName = computed(() => {
  const t = rejectTarget.value
  if (!t) return '该商户'
  // 入驻申请 VO 字段为 name；退驻申请 VO 为 wholesalerName（TA 列表冗余）
  const name =
    rejectKind.value === 'APPLY'
      ? (t as WholesalerApplication).name
      : (t as WaWithdrawApplication).wholesalerName
  return name || '该商户'
})

const rejectForm = reactive({ remark: '' })

const rejectRules: FormRules = {
  remark: [
    { required: true, message: '请填写驳回理由', trigger: 'blur' },
    { min: 5, max: 200, message: '驳回理由为 5-200 字', trigger: 'blur' },
  ],
}

const openReject = (row: WholesalerApplication) => {
  rejectKind.value = 'APPLY'
  rejectTarget.value = row
  rejectForm.remark = ''
  rejectVisible.value = true
  rejectFormRef.value?.clearValidate()
}

const openRejectWithdraw = (row: WaWithdrawApplication) => {
  rejectKind.value = 'WITHDRAW'
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
    if (rejectKind.value === 'APPLY') {
      const t = rejectTarget.value as WholesalerApplication
      await tenantApi.auditWaApplication(String(t.id), {
        action: 'REJECTED',
        remark: rejectForm.remark.trim(),
      })
      ElMessage.success(`已驳回「${rejectTargetName.value}」的入驻申请`)
      rejectVisible.value = false
      await fetchList()
    } else {
      const t = rejectTarget.value as WaWithdrawApplication
      await tenantApi.auditWaWithdrawApplication(String(t.id), {
        action: 'REJECTED',
        remark: rejectForm.remark.trim(),
      })
      ElMessage.success(`已驳回「${rejectTargetName.value}」的退驻申请`)
      rejectVisible.value = false
      await fetchWithdrawList()
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    rejectSubmitting.value = false
  }
}

onMounted(async () => {
  await fetchList()
  await fetchPendingCount()
  await fetchWithdrawPendingCount()
})
</script>

<template>
  <div class="ta-shell">
    <!-- 顶栏 -->
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #store>
        <WarehouseSwitcher />
      </template>
    </AppTopbar>

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
              审批批发商的入驻 / 退驻申请：驳回需填写理由；退驻通过即刻生效
            </p>
          </div>
          <el-button
            :icon="Refresh"
            :loading="docKind === 'APPLY' ? loading : wLoading"
            @click="docKind === 'APPLY' ? fetchList() : fetchWithdrawList()"
          >
            刷新
          </el-button>
        </header>

        <section class="card">
          <!-- 单据类型切换：入驻申请 / 退驻申请 -->
          <el-radio-group v-model="docKind" class="kind-switch" @change="onDocKindChange">
            <el-radio-button value="APPLY">
              <span class="app-tabs__label">
                入驻申请
                <NavCountBadge :count="pendingTotal" />
              </span>
            </el-radio-button>
            <el-radio-button value="WITHDRAW">
              <span class="app-tabs__label">
                退驻申请
                <NavCountBadge :count="wPendingTotal" />
              </span>
            </el-radio-button>
          </el-radio-group>

          <!-- ============ 入驻申请 ============ -->
          <template v-if="docKind === 'APPLY'">
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
                <span class="cell-name">{{ row.name || '—' }}</span>
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
                <span class="cell-code cell-muted">{{ row.license || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
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
                <span class="cell-muted">{{ row.auditRemark || '—' }}</span>
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
                  :loading="auditingId === String(row.id)"
                  @click="onApprove(row as WholesalerApplication)"
                >
                  通过
                </el-button>
                <el-button link type="danger" @click="openReject(row as WholesalerApplication)">驳回</el-button>
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
          </template>

          <!-- ============ 退驻申请（R13） ============ -->
          <template v-else>
          <el-tabs v-model="activeWTab" class="app-tabs" @tab-change="onWTabChange">
            <el-tab-pane name="PENDING">
              <template #label>
                <span class="app-tabs__label">
                  待审核
                  <NavCountBadge :count="wPendingTotal" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane label="已退驻" name="APPROVED" />
            <el-tab-pane label="已驳回" name="REJECTED" />
          </el-tabs>

          <el-table
            v-loading="wLoading"
            :data="wList"
            stripe
            class="app-table"
            :empty-text="wEmptyText"
          >
            <el-table-column label="商户名" min-width="160">
              <template #default="{ row }">
                <span class="cell-name">{{ row.wholesalerName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="退驻原因" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.reason || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <StatusBadge
                  :variant="wStatusMeta(row.status).variant"
                  :text="wStatusMeta(row.status).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column
              v-if="activeWTab === 'APPROVED'"
              label="生效时间"
              width="150"
            >
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.auditedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              v-if="activeWTab === 'REJECTED'"
              label="驳回理由"
              min-width="180"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="cell-muted">{{ row.auditRemark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column
              v-if="activeWTab === 'PENDING'"
              label="操作"
              width="150"
              fixed="right"
            >
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :loading="auditingId === String(row.id)"
                  @click="onApproveWithdraw(row as WaWithdrawApplication)"
                >
                  通过
                </el-button>
                <el-button link type="danger" @click="openRejectWithdraw(row as WaWithdrawApplication)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="wTotal > wSize" class="app-pager">
            <el-pagination
              :current-page="wPage"
              :page-size="wSize"
              :total="wTotal"
              layout="total, prev, pager, next"
              background
              @current-change="onWPageChange"
            />
          </div>
          </template>
        </section>
      </main>
    </div>

    <!-- 驳回弹窗（理由必填，入驻/退驻共用） -->
    <el-dialog
      v-model="rejectVisible"
      :title="rejectKind === 'APPLY' ? '驳回入驻申请' : '驳回退驻申请'"
      width="480px"
      :close-on-click-modal="false"
    >
      <p class="reject-tip">
        驳回「<span class="cell-name">{{ rejectTargetName }}</span
        >」的{{ rejectKind === 'APPLY' ? '入驻' : '退驻' }}申请，理由将原文展示给申请人。
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
            maxlength="200"
            show-word-limit
            :placeholder="
              rejectKind === 'APPLY'
                ? '请填写驳回理由（5-200 字），如：资质材料不全，请补充营业执照后重新申请'
                : '请填写驳回理由（5-200 字），如：存在未完成的出库单，请处理后重新申请'
            "
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
.kind-switch {
  margin-bottom: var(--space-4);
}
.kind-switch :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
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
