<script setup lang="ts">
/**
 * WA 员工管理（PC 复用 TA Employees shell）— P2 入驻生态 Wave4b 前端第二批
 *
 * 来源：
 *  - 线框：shared/product/06b-onboarding-wireframes.md §6（员工 Tab + 注册码 Tab）
 *  - 契约：Wave3 · Team Lead 定稿 6 端点（invites 3 + employees 3，见 task_plan 契约节）
 *  - 视觉：仿 ta/Employees.vue（顶栏 + 左侧菜单 + el-table/el-dialog）
 *
 * 关键交互（已定稿）：
 *  - 授权位仅两枚（O-4）：PRICE_EDIT 改价 / INQUIRY_CONFIRM 询价确认；switch 即时生效无保存按钮
 *  - [禁用]（R17）确认弹窗：立即踢出登录 + 草稿单据作废；30 天内可恢复，逾期"已永久移除"
 *  - 生码：角色固定 WE；初始授权默认只勾"询价确认"（最小授权原则）
 *  - WA 本身被下架/退驻 → 本页只读化 + 顶部灰条说明
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Document,
  Shop,
  User,
  Warning,
  Plus,
  CopyDocument,
  Refresh,
} from '@element-plus/icons-vue'
import { StatusBadge, NavCountBadge } from '@cangchu/ui-shared'
import type {
  WaEmployee,
  WaEmployeeInvite,
  WePermission,
  CreateWaEmployeeInviteRequest,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { waEmployeeApi } from '@/api/wholesaler'
import { accountApi } from '@/api/account'

const router = useRouter()
const auth = useAuthStore()

// ============ 顶栏 ============
const storeNameDisplay = computed(() => auth.currentStoreName || '我的商户')

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

// ============ 菜单（WA 端） ============
const activeMenu = ref('/wa/staff')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: Warning },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/staff') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 只读化（WA 被下架 / 已退驻，06b §6.4） ============
const readonlyReason = ref('')
const isReadonly = computed(() => !!readonlyReason.value)

const markReadonlyIfNeeded = (e: unknown) => {
  if (e instanceof ApiError) {
    if (e.code === 50202) {
      readonlyReason.value = '本商户已退驻，员工管理只读；恢复入驻后可继续操作'
    } else if (e.code === 50103) {
      readonlyReason.value = '本商户已被强制下架，员工管理只读'
    }
  }
}

// ============ Tab ============
const activeTab = ref<'employees' | 'invites'>('employees')

// ============ 员工列表 ============
const empLoading = ref(false)
const employees = ref<WaEmployee[]>([])

const fetchEmployees = async () => {
  empLoading.value = true
  try {
    employees.value = (await waEmployeeApi.listEmployees()) ?? []
  } catch (e) {
    markReadonlyIfNeeded(e)
    // 后端 Wave3 端点未就绪 / 网络失败 → 空态兜底
  } finally {
    empLoading.value = false
  }
}

const PERMS: Array<{ key: WePermission; label: string }> = [
  { key: 'PRICE_EDIT', label: '改价' },
  { key: 'INQUIRY_CONFIRM', label: '询价确认' },
]

const permLabel = (p: WePermission): string =>
  PERMS.find((x) => x.key === p)?.label ?? p

const hasPerm = (row: WaEmployee, p: WePermission): boolean =>
  (row.permissions ?? []).includes(p)

/** 已禁用行灰化 */
const employeeRowClass = ({ row }: { row: WaEmployee }): string =>
  row.status === 'DISABLED' ? 'row-disabled' : ''

/** 正在提交授权变更的员工（switch loading 态） */
const permSubmittingId = ref('')

/** 授权 switch 即时生效（整组覆盖式提交；失败回滚重拉） */
const onTogglePerm = async (row: WaEmployee, p: WePermission, next: boolean) => {
  if (isReadonly.value || row.status !== 'ACTIVE') return
  const prev = [...(row.permissions ?? [])]
  const nextPerms = next
    ? Array.from(new Set([...prev, p]))
    : prev.filter((x) => x !== p)
  // 乐观更新，失败回滚
  row.permissions = nextPerms
  permSubmittingId.value = String(row.userId)
  try {
    await waEmployeeApi.updatePermissions(String(row.userId), { permissions: nextPerms })
    ElMessage.success(
      next ? `已开通「${permLabel(p)}」授权，即时生效` : `已取消「${permLabel(p)}」授权，即时生效`,
    )
  } catch (e) {
    row.permissions = prev
    markReadonlyIfNeeded(e)
  } finally {
    permSubmittingId.value = ''
  }
}

// ============ 禁用 / 恢复（R17） ============
const DAY_MS = 24 * 60 * 60 * 1000

const restoreDaysLeft = (row: WaEmployee): number => {
  let deadline: Date | null = null
  if (row.restoreDeadline) {
    const d = new Date(row.restoreDeadline)
    if (!Number.isNaN(d.getTime())) deadline = d
  } else if (row.disabledAt) {
    const d = new Date(row.disabledAt)
    if (!Number.isNaN(d.getTime())) deadline = new Date(d.getTime() + 30 * DAY_MS)
  }
  if (!deadline) return 30 // 后端未下发时间时按可恢复处理，交后端兜底
  return Math.ceil((deadline.getTime() - Date.now()) / DAY_MS)
}

const opSubmittingId = ref('')

const onDisable = async (row: WaEmployee) => {
  try {
    await ElMessageBox.confirm(
      `禁用后立即生效：该员工将被立即退出登录，其名下草稿单据将作废。30 天内可点「恢复」撤销禁用。`,
      `禁用员工 · ${row.realName}`,
      {
        confirmButtonText: '确认禁用',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  opSubmittingId.value = String(row.userId)
  try {
    await waEmployeeApi.disableEmployee(String(row.userId))
    ElMessage.success(`已禁用「${row.realName}」`)
    await fetchEmployees()
  } catch (e) {
    markReadonlyIfNeeded(e)
  } finally {
    opSubmittingId.value = ''
  }
}

const onRestore = async (row: WaEmployee) => {
  try {
    await ElMessageBox.confirm(
      '恢复后员工可重新登录，授权保持禁用前设置。确认恢复？',
      `恢复员工 · ${row.realName}`,
      {
        confirmButtonText: '确认恢复',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  opSubmittingId.value = String(row.userId)
  try {
    await waEmployeeApi.restoreEmployee(String(row.userId))
    ElMessage.success(`已恢复「${row.realName}」`)
    await fetchEmployees()
  } catch (e) {
    markReadonlyIfNeeded(e)
  } finally {
    opSubmittingId.value = ''
  }
}

// ============ 注册码列表 ============
const invLoading = ref(false)
const invites = ref<WaEmployeeInvite[]>([])

const fetchInvites = async () => {
  invLoading.value = true
  try {
    invites.value = (await waEmployeeApi.listInvites()) ?? []
  } catch (e) {
    markReadonlyIfNeeded(e)
  } finally {
    invLoading.value = false
  }
}

type BadgeVariant = 'success' | 'warning' | 'danger' | 'default'
const inviteStatusMeta = (status: string): { variant: BadgeVariant; text: string } => {
  const map: Record<string, { variant: BadgeVariant; text: string }> = {
    ACTIVE: { variant: 'success', text: '有效' },
    EXHAUSTED: { variant: 'warning', text: '已用完' },
    REVOKED: { variant: 'default', text: '已作废' },
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

const formatDate = (iso?: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// ============ 复制 ============
const copyText = async (text: string, tip = '已复制') => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success(tip)
  } catch {
    ElMessage.warning(`复制失败，请手动复制：${text}`)
  }
}

/** 注册链接：员工打开即落 WE 注册页并预填码 */
const registerLink = (code: string): string =>
  `${window.location.origin}/register?role=we&code=${encodeURIComponent(code)}`

// ============ 作废注册码 ============
const revokeInvite = async (row: WaEmployeeInvite) => {
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
    await waEmployeeApi.revokeInvite(String(row.id))
    ElMessage.success('注册码已作废')
    await fetchInvites()
  } catch (e) {
    markReadonlyIfNeeded(e)
  }
}

// ============ 生码对话框 ============
const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref<FormInstance>()

const createForm = reactive({
  maxUses: 5,
  expireDays: 7,
  // 初始授权默认只勾"询价确认"（最小授权原则，06b §6.2）
  permissions: ['INQUIRY_CONFIRM'] as WePermission[],
})

const createRules: FormRules = {
  maxUses: [
    { required: true, message: '请输入使用次数上限', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const n = Number(v)
        if (!Number.isInteger(n) || n < 1 || n > 20) {
          cb(new Error('使用次数上限为 1-20 的整数'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
}

const openCreate = () => {
  createForm.maxUses = 5
  createForm.expireDays = 7
  createForm.permissions = ['INQUIRY_CONFIRM']
  createVisible.value = true
  createFormRef.value?.clearValidate()
}

const onCreateSubmit = async () => {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  createSubmitting.value = true
  try {
    const payload: CreateWaEmployeeInviteRequest = {
      maxUses: createForm.maxUses,
      expireDays: createForm.expireDays,
      permissions: [...createForm.permissions],
    }
    const created = await waEmployeeApi.createInvite(payload)
    createVisible.value = false
    await fetchInvites()
    // 生码成功 → 大号展示 + 引导复制（员工注册后自动加入本商户）
    try {
      await ElMessageBox.confirm(
        `注册码：${created.code}\n初始授权：${
          (created.permissions ?? payload.permissions ?? [])
            .map((p) => permLabel(p))
            .join('、') || '无（仅基础单据录入）'
        }\n员工注册后自动加入本商户。`,
        '生成成功',
        {
          confirmButtonText: '复制注册码',
          cancelButtonText: '关闭',
          type: 'success',
        },
      )
      await copyText(created.code, '注册码已复制')
    } catch {
      /* 关闭 */
    }
  } catch (e) {
    markReadonlyIfNeeded(e)
  } finally {
    createSubmitting.value = false
  }
}

onMounted(async () => {
  await Promise.all([fetchEmployees(), fetchInvites()])
})
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏 -->
    <header class="wa-topbar">
      <div class="wa-topbar__left">
        <span class="wa-topbar__brand">仓储云</span>
        <span class="wa-topbar__divider">·</span>
        <span class="wa-topbar__store">{{ storeNameDisplay }}</span>
      </div>

      <div class="wa-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>
        <el-button text :icon="Bell" class="wa-topbar__bell" />
        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="wa-topbar__user">
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

    <div class="wa-body">
      <!-- 左侧菜单 -->
      <aside class="wa-side">
        <el-menu :default-active="activeMenu" class="wa-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="wa-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">员工管理</h2>
            <p class="page-head__sub">
              生成注册码邀请员工（WE），管理「改价 / 询价确认」两项授权；授权变更即时生效
            </p>
          </div>
          <div class="page-head__actions">
            <el-button
              :icon="Refresh"
              :loading="empLoading || invLoading"
              @click="activeTab === 'employees' ? fetchEmployees() : fetchInvites()"
            >
              刷新
            </el-button>
            <el-button
              v-if="activeTab === 'invites'"
              type="primary"
              :icon="Plus"
              :disabled="isReadonly"
              @click="openCreate"
            >
              生成注册码
            </el-button>
          </div>
        </header>

        <!-- 只读灰条（WA 被下架 / 已退驻） -->
        <el-alert
          v-if="isReadonly"
          type="info"
          :closable="false"
          show-icon
          :title="readonlyReason"
        />

        <section class="card">
          <el-tabs v-model="activeTab" class="app-tabs">
            <el-tab-pane name="employees">
              <template #label>
                <span class="app-tabs__label">
                  员工
                  <NavCountBadge :count="employees.length" />
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="invites">
              <template #label>
                <span class="app-tabs__label">
                  注册码
                  <NavCountBadge :count="invites.length" />
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>

          <!-- ============ 员工 Tab ============ -->
          <el-table
            v-if="activeTab === 'employees'"
            v-loading="empLoading"
            :data="employees"
            stripe
            class="app-table"
            empty-text="暂无员工，先到「注册码」Tab 生成注册码邀请员工"
            :row-class-name="employeeRowClass"
          >
            <el-table-column label="姓名" min-width="120">
              <template #default="{ row }">
                <span class="cell-name">{{ row.realName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="手机号" width="140">
              <template #default="{ row }">
                <span class="cell-code">{{ row.phone || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="改价" width="90" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="hasPerm(row, 'PRICE_EDIT')"
                  :disabled="isReadonly || row.status !== 'ACTIVE'"
                  :loading="permSubmittingId === String(row.userId)"
                  @change="(v: string | number | boolean) => onTogglePerm(row, 'PRICE_EDIT', !!v)"
                />
              </template>
            </el-table-column>
            <el-table-column label="询价确认" width="100" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="hasPerm(row, 'INQUIRY_CONFIRM')"
                  :disabled="isReadonly || row.status !== 'ACTIVE'"
                  :loading="permSubmittingId === String(row.userId)"
                  @change="
                    (v: string | number | boolean) => onTogglePerm(row, 'INQUIRY_CONFIRM', !!v)
                  "
                />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <StatusBadge
                  :variant="row.status === 'ACTIVE' ? 'success' : 'default'"
                  :text="row.status === 'ACTIVE' ? '在职' : '已禁用'"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="禁用信息" min-width="170">
              <template #default="{ row }">
                <template v-if="row.status === 'DISABLED'">
                  <span class="cell-muted">禁用于 {{ formatDate(row.disabledAt) }}</span>
                  <span
                    v-if="restoreDaysLeft(row) > 0"
                    class="cell-countdown"
                  >
                    ⏳ {{ restoreDaysLeft(row) }} 天内可恢复
                  </span>
                  <span v-else class="cell-removed">已永久移除</span>
                </template>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  link
                  type="danger"
                  :disabled="isReadonly"
                  :loading="opSubmittingId === String(row.userId)"
                  @click="onDisable(row)"
                >
                  禁用
                </el-button>
                <!-- 30 天倒计时结束后 [恢复] 消失（已永久移除） -->
                <el-button
                  v-else-if="restoreDaysLeft(row) > 0"
                  link
                  type="primary"
                  :disabled="isReadonly"
                  :loading="opSubmittingId === String(row.userId)"
                  @click="onRestore(row)"
                >
                  恢复
                </el-button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- ============ 注册码 Tab ============ -->
          <el-table
            v-else
            v-loading="invLoading"
            :data="invites"
            stripe
            class="app-table"
            empty-text="暂无注册码，点击右上角「生成注册码」开始"
          >
            <el-table-column label="注册码" min-width="170">
              <template #default="{ row }">
                <span class="cell-code">{{ row.code }}</span>
                <el-button
                  link
                  type="primary"
                  :icon="CopyDocument"
                  class="copy-inline"
                  @click="copyText(row.code, '注册码已复制')"
                />
              </template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default>批发商员工</template>
            </el-table-column>
            <el-table-column label="初始授权" min-width="150">
              <template #default="{ row }">
                <span v-if="(row.permissions ?? []).length" class="cell-muted">
                  {{ (row.permissions as WePermission[]).map((p) => permLabel(p)).join('、') }}
                </span>
                <span v-else class="cell-muted">无</span>
              </template>
            </el-table-column>
            <el-table-column label="已用 / 上限" width="110">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.usedCount }} / {{ row.maxUses }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusBadge
                  :variant="inviteStatusMeta(row.status).variant"
                  :text="inviteStatusMeta(row.status).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="有效至" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.expireAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="copyText(row.code, '注册码已复制')">
                  复制
                </el-button>
                <el-button
                  link
                  type="primary"
                  @click="copyText(registerLink(row.code), '注册链接已复制')"
                >
                  复制链接
                </el-button>
                <el-button
                  link
                  type="danger"
                  :disabled="isReadonly || row.status === 'REVOKED'"
                  @click="revokeInvite(row)"
                >
                  作废
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 生成注册码对话框（06b §6.2：角色固定 WE + 初始授权勾选） -->
    <el-dialog
      v-model="createVisible"
      title="生成员工注册码"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        @submit.prevent="onCreateSubmit"
      >
        <el-form-item label="角色">
          <el-input model-value="批发商员工（WE）" disabled />
          <span class="form-hint">固定不可选，员工注册后自动加入本商户</span>
        </el-form-item>

        <el-form-item label="使用次数上限" prop="maxUses">
          <el-input-number v-model="createForm.maxUses" :min="1" :max="20" :step="1" />
          <span class="form-hint">同一个码可被多少人用于注册（1~20，默认 5）</span>
        </el-form-item>

        <el-form-item label="有效期">
          <el-radio-group v-model="createForm.expireDays">
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="3">3 天</el-radio-button>
            <el-radio-button :value="1">24 小时</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="初始授权（注册后可在员工列表调整）">
          <el-checkbox-group v-model="createForm.permissions">
            <el-checkbox value="PRICE_EDIT">改价（PRICE_EDIT）</el-checkbox>
            <el-checkbox value="INQUIRY_CONFIRM">询价确认（INQUIRY_CONFIRM）</el-checkbox>
          </el-checkbox-group>
          <span class="form-hint">
            默认只勾「询价确认」；可全不勾（员工注册后仅基础单据录入）
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="onCreateSubmit">
          生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wa-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏（同 Apply.vue） ===== */
.wa-topbar {
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
.wa-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.wa-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.wa-topbar__divider {
  opacity: 0.5;
}
.wa-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}
.wa-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.wa-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.wa-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.wa-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}
.wa-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.wa-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
}

/* ===== body / 菜单 ===== */
.wa-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}
.wa-side {
  width: 220px;
  background: var(--color-bg-1);
  border-right: 1px solid var(--color-border-1);
  flex-shrink: 0;
}
.wa-side__menu {
  border-right: none;
}
.wa-side__menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: var(--font-size-body);
}
.wa-side__menu :deep(.el-menu-item.is-active) {
  background: var(--color-info-bg);
  color: var(--color-brand-accent);
  border-right: 3px solid var(--color-brand-accent);
}

/* ===== 主区 ===== */
.wa-main {
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
  gap: var(--space-3);
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
.page-head__actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
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
.app-table :deep(.row-disabled) {
  color: var(--color-fg-4);
  background: var(--color-bg-2);
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
  letter-spacing: 0.5px;
}
.cell-countdown {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--color-warning, #ff7d00);
}
.cell-removed {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}
.copy-inline {
  margin-left: var(--space-1);
  vertical-align: middle;
}

.form-hint {
  display: block;
  width: 100%;
  margin-top: 4px;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0; /* 表格内部滚动，不撑宽页面 */
  }
  /* 窄屏顶栏：品牌/店名单行省略，防纵向折行 */
  .wa-topbar {
    padding: 0 var(--space-4);
  }
  .wa-topbar__left {
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
  }
  .wa-topbar__store {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .wa-topbar__right {
    flex-shrink: 0;
  }
  .page-head {
    flex-direction: column;
  }
}
</style>
