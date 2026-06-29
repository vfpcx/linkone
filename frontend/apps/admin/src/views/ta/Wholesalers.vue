<script setup lang="ts">
/**
 * TA 入驻商户管理（PC）— phase-1 D1a 卖家侧
 *
 * 来源：
 *  - 契约：backend/.../tenant/controller/WholesalerController.java
 *      GET  /tenant/wholesalers       列表
 *      POST /tenant/wholesalers       创建（name 必填，license/intro/waPhone 可选）
 *      PUT  /tenant/wholesalers/{id}  改资料（license / intro）
 *  - 视觉：沿用 Dashboard.vue / Settings.vue 的顶栏 + 左侧菜单 shell + el-table/el-dialog 风格
 *
 * 范围：仅 TA 商户管理（列表 + 新建 + 编辑资料），不碰 SKU/入库/询价/RT。
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
  Plus,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type {
  Wholesaler,
  CreateWholesalerRequest,
  UpdateWholesalerRequest,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { wholesalerApi } from '@/api/wholesaler'
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
const activeMenu = ref('/ta/wholesalers')

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
  { key: '/ta/operations', label: '运营总览', icon: TrendCharts },
  { key: '/ta/approvals', label: '单据审批', icon: Document },
  { key: '/ta/bills', label: '账单总览', icon: Coin },
  { key: '/ta/messages', label: '站内信', icon: ChatLineSquare },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ta/wholesalers') {
    activeMenu.value = key
    return
  }
  if (key === '/ta/dashboard' || key === '/ta/settings') {
    router.push(key)
    return
  }
  ElMessage.info(`「${menus.find((m) => m.key === key)?.label}」页面留给后续 Agent 实现`)
}

// ============ 列表 ============
const loading = ref(false)
const list = ref<Wholesaler[]>([])

const fetchList = async () => {
  loading.value = true
  try {
    list.value = await wholesalerApi.list()
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 状态徽章 ============
type BadgeVariant = 'success' | 'warning' | 'danger' | 'default'
const statusMeta = (status: string): { variant: BadgeVariant; text: string } => {
  const map: Record<string, { variant: BadgeVariant; text: string }> = {
    ACTIVE: { variant: 'success', text: '生效中' },
    DISABLED: { variant: 'danger', text: '已停用' },
    PENDING: { variant: 'warning', text: '待生效' },
  }
  return map[status] ?? { variant: 'default', text: status || '—' }
}

const sourceLabel = (source: string): string => {
  const map: Record<string, string> = {
    SELF_OPERATED: '自营',
    APPLIED: '入驻申请',
  }
  return map[source] ?? source ?? '—'
}

const formatTime = (iso: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ============ 对话框（新建 / 编辑） ============
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string>('')

const form = reactive({
  name: '',
  intro: '',
  license: '',
  waPhone: '',
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入商户名称', trigger: 'blur' },
    { max: 50, message: '商户名称最多 50 字', trigger: 'blur' },
  ],
  waPhone: [
    {
      validator: (_r, v, cb) => {
        if (v && !/^1\d{10}$/.test(String(v).trim())) {
          cb(new Error('请输入有效的 11 位手机号'))
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
  form.intro = ''
  form.license = ''
  form.waPhone = ''
  editingId.value = ''
}

const openCreate = () => {
  resetForm()
  dialogMode.value = 'create'
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const openEdit = (row: Wholesaler) => {
  resetForm()
  dialogMode.value = 'edit'
  editingId.value = String(row.id)
  form.name = row.name
  form.intro = row.intro ?? ''
  form.license = row.license ?? ''
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      const payload: CreateWholesalerRequest = { name: form.name.trim() }
      if (form.intro.trim()) payload.intro = form.intro.trim()
      if (form.license.trim()) payload.license = form.license.trim()
      if (form.waPhone.trim()) payload.waPhone = form.waPhone.trim()
      await wholesalerApi.create(payload)
      ElMessage.success('商户创建成功')
    } else {
      const payload: UpdateWholesalerRequest = {
        intro: form.intro.trim() || undefined,
        license: form.license.trim() || undefined,
      }
      await wholesalerApi.update(editingId.value, payload)
      ElMessage.success('商户资料已更新')
    }
    dialogVisible.value = false
    await fetchList()
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
            <h2 class="page-head__title">入驻商户</h2>
            <p class="page-head__sub">本店自营 / 入驻的批发商商户，可在此创建与维护资料</p>
          </div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建商户</el-button>
        </header>

        <section class="card">
          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="wholesaler-table"
            empty-text="暂无商户，点击右上角「新建商户」开始"
          >
            <el-table-column prop="name" label="商户名称" min-width="160">
              <template #default="{ row }">
                <span class="cell-name">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="110">
              <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
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
            <el-table-column prop="license" label="营业资质" min-width="140">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.license || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="intro" label="简介" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.intro || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="WA 账号" width="100">
              <template #default="{ row }">
                <StatusBadge
                  v-if="row.waUserId"
                  variant="success"
                  text="已开通"
                  :dot="true"
                />
                <span v-else class="cell-muted">未开通</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="160">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 新建 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建商户' : '编辑商户资料'"
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
        <el-form-item label="商户名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="如：XX 海鲜批发"
            maxlength="50"
            show-word-limit
            :disabled="dialogMode === 'edit'"
          />
          <span v-if="dialogMode === 'edit'" class="form-hint">名称暂不支持修改</span>
        </el-form-item>

        <el-form-item label="营业资质（可选）" prop="license">
          <el-input v-model="form.license" placeholder="统一社会信用代码等" maxlength="64" />
        </el-form-item>

        <el-form-item label="简介（可选）" prop="intro">
          <el-input
            v-model="form.intro"
            type="textarea"
            :rows="3"
            placeholder="经营品类、特色等"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item v-if="dialogMode === 'create'" label="负责人手机号（可选）" prop="waPhone">
          <el-input v-model="form.waPhone" placeholder="填写则为该商户开通 WA 账号" maxlength="11" />
          <span class="form-hint">传入手机号将为商户负责人创建/绑定 WA 账号</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
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

/* ===== 顶栏（复用 Dashboard 风格） ===== */
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
.wholesaler-table {
  width: 100%;
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
