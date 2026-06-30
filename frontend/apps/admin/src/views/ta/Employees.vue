<script setup lang="ts">
/**
 * TA 员工管理（PC）— phase-1 员工注册码
 *
 * 来源：
 *  - 契约：backend EmployeeInviteController（已上线）
 *      POST   /tenant/employee-invites      生码（role / maxUses / expiresInDays）
 *      GET    /tenant/employee-invites      列表（倒序）
 *      DELETE /tenant/employee-invites/{id} 作废
 *  - 视觉：沿用 Wholesalers.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格
 *
 * 范围：仅员工注册码（生码 / 列表 / 复制 / 作废）。员工凭码注册在 Register.vue。
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
  CopyDocument,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type {
  EmployeeInvite,
  EmployeeInviteRole,
  CreateEmployeeInviteRequest,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { employeeInviteApi } from '@/api/employeeInvite'
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
const activeMenu = ref('/ta/employees')

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
  if (key === '/ta/employees') {
    activeMenu.value = key
    return
  }
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/skus' ||
    key === '/ta/wholesalers'
  ) {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 列表 ============
const loading = ref(false)
const list = ref<EmployeeInvite[]>([])

const fetchList = async () => {
  loading.value = true
  try {
    list.value = await employeeInviteApi.list()
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 角色 / 状态徽章 ============
const roleLabel = (role: string): string => {
  const map: Record<string, string> = {
    WK: '库管员',
    ST: '结算员',
  }
  return map[role] ?? role ?? '—'
}

type BadgeVariant = 'success' | 'warning' | 'danger' | 'default'
const statusMeta = (status: string): { variant: BadgeVariant; text: string } => {
  const map: Record<string, { variant: BadgeVariant; text: string }> = {
    ACTIVE: { variant: 'success', text: '可用' },
    EXHAUSTED: { variant: 'warning', text: '已用完' },
    REVOKED: { variant: 'danger', text: '已作废' },
  }
  return map[status] ?? { variant: 'default', text: status || '—' }
}

const formatTime = (iso: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ============ 复制注册码 ============
const copyCode = async (code: string) => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(code)
    } else {
      // 降级：execCommand
      const ta = document.createElement('textarea')
      ta.value = code
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('注册码已复制')
  } catch {
    ElMessage.warning(`复制失败，请手动复制：${code}`)
  }
}

// ============ 作废 ============
const revoke = async (row: EmployeeInvite) => {
  try {
    await ElMessageBox.confirm(
      `确认作废注册码「${row.code}」？作废后该码将无法继续注册。`,
      '作废确认',
      {
        confirmButtonText: '作废',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  try {
    await employeeInviteApi.revoke(String(row.id))
    ElMessage.success('注册码已作废')
    await fetchList()
  } catch {
    // 全局 toast 已提示
  }
}

// ============ 生码对话框 ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  role: 'WK' as EmployeeInviteRole,
  maxUses: 1,
  expiresInDays: 7,
})

const rules: FormRules = {
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  maxUses: [
    { required: true, message: '请输入可用次数', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const n = Number(v)
        if (!Number.isInteger(n) || n < 1 || n > 999) {
          cb(new Error('可用次数为 1-999 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  expiresInDays: [
    { required: true, message: '请输入有效天数', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const n = Number(v)
        if (!Number.isInteger(n) || n < 1 || n > 365) {
          cb(new Error('有效天数为 1-365 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}

const openCreate = () => {
  form.role = 'WK'
  form.maxUses = 1
  form.expiresInDays = 7
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: CreateEmployeeInviteRequest = {
      role: form.role,
      maxUses: form.maxUses,
      expiresInDays: form.expiresInDays,
    }
    const created = await employeeInviteApi.create(payload)
    dialogVisible.value = false
    await fetchList()
    // 生码后引导复制
    try {
      await ElMessageBox.confirm(
        `注册码：${created.code}（${roleLabel(created.role)}，可用 ${created.maxUses} 次）`,
        '生成成功',
        {
          confirmButtonText: '复制注册码',
          cancelButtonText: '关闭',
          type: 'success',
        },
      )
      await copyCode(created.code)
    } catch {
      /* 关闭 */
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    submitting.value = false
  }
}

onMounted(fetchList)
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
            <h2 class="page-head__title">员工</h2>
            <p class="page-head__sub">
              生成员工注册码，库管员（WK）/ 结算员（ST）凭码注册即自动绑定本仓库
            </p>
          </div>
          <el-button type="primary" :icon="Plus" @click="openCreate">生成注册码</el-button>
        </header>

        <section class="card">
          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="invite-table"
            empty-text="暂无注册码，点击右上角「生成注册码」开始"
          >
            <el-table-column label="注册码" min-width="160">
              <template #default="{ row }">
                <span class="cell-code">{{ row.code }}</span>
                <el-button
                  link
                  type="primary"
                  :icon="CopyDocument"
                  class="copy-inline"
                  @click="copyCode(row.code)"
                />
              </template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default="{ row }">{{ roleLabel(row.role) }}</template>
            </el-table-column>
            <el-table-column label="已用 / 上限" width="120">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.usedCount }} / {{ row.maxUses }}</span>
              </template>
            </el-table-column>
            <el-table-column label="剩余" width="90">
              <template #default="{ row }">
                <span :class="row.remaining > 0 ? 'cell-name' : 'cell-muted'">
                  {{ row.remaining }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <StatusBadge
                  :variant="statusMeta(row.status).variant"
                  :text="statusMeta(row.status).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="过期时间" width="160">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.expireAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="copyCode(row.code)">复制</el-button>
                <el-button
                  link
                  type="danger"
                  :disabled="row.status === 'REVOKED'"
                  @click="revoke(row)"
                >
                  作废
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 生成注册码对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="生成员工注册码"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="form.role">
            <el-radio-button label="WK">库管员（WK）</el-radio-button>
            <el-radio-button label="ST">结算员（ST）</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="可用次数" prop="maxUses">
          <el-input-number v-model="form.maxUses" :min="1" :max="999" :step="1" />
          <span class="form-hint">同一个码可被多少人用于注册（默认 1）</span>
        </el-form-item>

        <el-form-item label="有效天数" prop="expiresInDays">
          <el-input-number v-model="form.expiresInDays" :min="1" :max="365" :step="1" />
          <span class="form-hint">超过该天数后注册码自动失效（默认 7）</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">生成</el-button>
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

/* ===== 卡片 + 表格 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.invite-table {
  width: 100%;
}
.cell-code {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
  letter-spacing: 0.5px;
}
.copy-inline {
  margin-left: var(--space-1);
  vertical-align: middle;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}

.form-hint {
  display: block;
  margin-top: 4px;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
}
</style>
