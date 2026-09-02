<script setup lang="ts">
/**
 * OPS 租户（仓库）审核（PC）— P0 遗留缺口补齐
 *
 * 背景：TA 自助注册仓库后进 PENDING，需 OPS 审核转 ACTIVE 才能营业（WA 入驻前置）。
 *
 * 来源：
 *  - 契约：POST /api/v1/admin/tenant/{id}/audit           ✅ 后端已实现（APPROVED/REJECTED，驳回 remark 必填）
 *          GET  /api/v1/admin/tenants?status=&page=&size=  ⚠️ 后端需补（前端按合理契约先行，未就绪时页面降级为错误态）
 *  - 视觉：沿用 Blacklist.vue 的 OPS shell（顶栏 + 左侧菜单）+ el-table/el-dialog 风格（MASTER §4.2/§4.4）
 *  - 交互：对齐 TA 端 WholesalerApplications.vue（状态 Tab + 通过确认 + 驳回理由必填弹窗）
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Monitor,
  CircleClose,
  Stamp,
  Bell,
  Refresh,
  ScaleToOriginal,
} from '@element-plus/icons-vue'
import { AppTopbar, StatusBadge, NavCountBadge } from '@cangchu/ui-shared'
import type { AdminTenantItem, AdminTenantStatus } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { tenantApi } from '@/api/tenant'
import { accountApi } from '@/api/account'
import { piiApi } from '@/api/pii'

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

// ============ 菜单（OPS 端） ============
const activeMenu = ref('/ops/tenant-audit')

/** 待审核数（菜单徽标 + Tab 徽标共用） */
const pendingTotal = ref(0)

const menus = computed(() => [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor, badge: 0 },
  { key: '/ops/tenant-audit', label: '租户审核', icon: Stamp, badge: pendingTotal.value },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose, badge: 0 },
  { key: '/ops/announcements', label: '公告管理', icon: Bell, badge: 0 },
  { key: '/ops/arbitrations', label: '客诉仲裁', icon: ScaleToOriginal, badge: 0 },
])

const handleMenuSelect = (key: string) => {
  if (key === '/ops/tenant-audit') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ops/dashboard' ||
    key === '/ops/blacklist' ||
    key === '/ops/announcements' ||
    key === '/ops/arbitrations'
  ) {
    router.push(key)
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 状态 Tab + 列表 ============
const activeTab = ref<AdminTenantStatus>('PENDING')

const loading = ref(false)
/** 列表接口异常（含后端端点未就绪）→ 降级为错误态 */
const loadError = ref(false)
const list = ref<AdminTenantItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await tenantApi.listTenantsByOps({
      status: activeTab.value,
      page: page.value,
      size: size.value,
    })
    list.value = data?.list ?? []
    total.value = data?.total ?? 0
    if (activeTab.value === 'PENDING') {
      pendingTotal.value = data?.total ?? 0
    }
    loadError.value = false
  } catch {
    // 全局 toast 已提示；后端 GET /admin/tenants 未就绪时也走这里 → 优雅降级为错误态
    loadError.value = true
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 单拉一次待审核数（初始进入非 PENDING Tab 时也能显示菜单徽标） */
const fetchPendingCount = async () => {
  if (activeTab.value === 'PENDING') return
  try {
    const data = await tenantApi.listTenantsByOps({ status: 'PENDING', page: 1, size: 1 })
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
    ACTIVE: { variant: 'success', text: '已通过' },
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
  if (loadError.value) return ' ' // 错误态用 alert 呈现，表格空文案留白
  const map: Record<AdminTenantStatus, string> = {
    PENDING: '暂无待审核的仓库入驻申请',
    ACTIVE: '暂无已通过的仓库',
    REJECTED: '暂无已驳回的申请',
  }
  return map[activeTab.value]
})

// ============ 通过（确认弹窗） ============
const auditingId = ref('')

/** PII-W7 查全号：列表只回打码号，OPS 联系/核实需要时经 phone-reveal 取全号（权限+审计在服务端） */
const revealContact = async (row: AdminTenantItem) => {
  try {
    const { phone } = await piiApi.revealPhone('TENANT', row.tenantId)
    await ElMessageBox.alert(phone, `完整联系方式（${row.name || '仓库'}）`, {
      confirmButtonText: '关闭',
    })
  } catch {
    /* 无权限/对象不存在 → http.ts 已 toast */
  }
}

const onApprove = async (row: AdminTenantItem) => {
  try {
    await ElMessageBox.confirm(
      `确认通过「${row.name}」的入驻申请？通过后该仓库即转为营业中（ACTIVE），可接收批发商入驻。`,
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
  auditingId.value = String(row.tenantId)
  try {
    await tenantApi.auditTenant(String(row.tenantId), { action: 'APPROVED' })
    ElMessage.success(`已通过「${row.name}」的入驻申请`)
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
const rejectTarget = ref<AdminTenantItem | null>(null)

const rejectForm = reactive({ remark: '' })

const rejectRules: FormRules = {
  remark: [
    { required: true, message: '请填写驳回理由', trigger: 'blur' },
    { min: 2, max: 512, message: '驳回理由为 2-512 字', trigger: 'blur' },
  ],
}

const openReject = (row: AdminTenantItem) => {
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
    await tenantApi.auditTenant(String(rejectTarget.value.tenantId), {
      action: 'REJECTED',
      remark: rejectForm.remark.trim(),
    })
    ElMessage.success(`已驳回「${rejectTarget.value.name}」的入驻申请`)
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
            <NavCountBadge :count="m.badge ?? 0" class="ops-side__badge" />
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ops-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">租户审核</h2>
            <p class="page-head__sub">
              审核租户管理员自助注册的仓库：通过后转为营业中（ACTIVE），驳回需填写理由
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
            <el-tab-pane label="已通过" name="ACTIVE" />
            <el-tab-pane label="已驳回" name="REJECTED" />
          </el-tabs>

          <!-- 错误态（含后端列表端点未就绪）：alert + 重试，审核端点本身已就绪 -->
          <el-alert
            v-if="loadError"
            type="warning"
            :closable="false"
            show-icon
            title="租户列表加载失败"
            class="audit-error"
          >
            <template #default>
              <p class="audit-error__text">
                可能是网络异常，或后端「平台租户列表」接口（GET /api/v1/admin/tenants）尚未上线。
                审核端点（POST /api/v1/admin/tenant/{id}/audit）已就绪，列表接口就绪后本页即可用。
              </p>
              <el-button size="small" :loading="loading" @click="fetchList">重试</el-button>
            </template>
          </el-alert>

          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="app-table"
            :empty-text="emptyText"
          >
            <el-table-column label="仓库名" min-width="160">
              <template #default="{ row }">
                <div class="cell-name">{{ row.name || '—' }}</div>
                <div v-if="row.legalName" class="cell-sub">{{ row.legalName }}</div>
              </template>
            </el-table-column>
            <el-table-column label="申请人" width="110">
              <template #default="{ row }">{{ row.applicantName || '—' }}</template>
            </el-table-column>
            <el-table-column label="联系方式" min-width="180">
              <template #default="{ row }">
                <span class="cell-code">{{ row.contactPhone || '—' }}</span>
                <el-button
                  v-if="row.contactPhone"
                  link
                  type="primary"
                  class="reveal-link"
                  @click="revealContact(row as AdminTenantItem)"
                >查看完整号</el-button>
              </template>
            </el-table-column>
            <el-table-column label="仓库地址" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.addressText || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="申请时间" width="150">
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
                  :loading="auditingId === String(row.tenantId)"
                  @click="onApprove(row as AdminTenantItem)"
                >
                  通过
                </el-button>
                <el-button link type="danger" @click="openReject(row as AdminTenantItem)">驳回</el-button>
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
        驳回「<span class="cell-name">{{ rejectTarget?.name }}</span
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
            placeholder="请填写驳回理由（必填），如：营业执照信息与仓库主体不符，请核对后重新提交"
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
.ops-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}


/* ===== body ===== */
.ops-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
.ops-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.ops-side__menu {
  border-right: none;
}
.ops-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.ops-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}
/* NavCountBadge 在菜单行内靠右（MASTER §4.11） */
.ops-side__badge {
  margin-left: auto;
}

/* ===== 主区 ===== */
.ops-main {
  flex: 1;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
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
.cell-sub {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  margin-top: 2px;
}
.cell-muted {
  color: var(--color-fg-3);
}
.cell-code {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
}

/* ===== 错误态 ===== */
.audit-error {
  margin-bottom: var(--space-4);
}
.audit-error__text {
  margin: 0 0 var(--space-3);
  line-height: var(--line-height-normal);
}

.reject-tip {
  margin: 0 0 var(--space-4);
  color: var(--color-fg-2);
  font-size: var(--font-size-body);
  line-height: var(--line-height-normal);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ops-side {
    display: none;
  }
  .page-head {
    flex-direction: column;
  }
  .ops-main {
    padding: var(--space-3);
  }
  /* 480px 固定宽弹窗在窄屏超出视口 → 收窄为视口内 */
  :deep(.el-dialog) {
    width: calc(100vw - 32px) !important;
    max-width: 480px;
  }
}
</style>
