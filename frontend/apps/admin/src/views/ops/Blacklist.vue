<script setup lang="ts">
/**
 * OPS 黑名单管理（PC）— P2 入驻生态 Wave4 前端第一批 · OPS 端第一个真实页面
 *
 * 来源：
 *  - 契约（10-onboarding-design.md §1.2/§2，后端已落地）：
 *          GET    /api/v1/ops/blacklist（列表返回裸数组，可按 status 过滤）
 *          POST   /api/v1/ops/blacklist（{targetType, targetValue, reason} 均必填；重复 50310）
 *          DELETE /api/v1/ops/blacklist/{id}（移除=置 REMOVED）
 *  - 视觉：沿用 TA 端 shell（顶栏 + 左侧菜单）+ el-table/el-dialog 风格（MASTER §4.2/§4.4）
 *
 * 黑名单为平台级（手机号 / 营业执照号双键，一次加黑一个键值）；
 * 三条入驻路径（自助 / OPS 代建 / TA 自营）命中即拦截（50205）。
 */

import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Monitor,
  CircleClose,
  Plus,
  Refresh,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type { BlacklistItem, CreateBlacklistRequest } from '@cangchu/api-types'
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

// ============ 菜单（OPS 端） ============
const activeMenu = ref('/ops/blacklist')

const menus = [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ops/blacklist') {
    activeMenu.value = key
    return
  }
  if (key === '/ops/dashboard') {
    router.push(key)
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 列表（后端返回裸数组，仅看生效中条目） ============
const loading = ref(false)
const list = ref<BlacklistItem[]>([])

const fetchList = async () => {
  loading.value = true
  try {
    const data = await opsApi.listBlacklist({ status: 'ACTIVE' })
    list.value = data ?? []
  } catch {
    // 全局 toast 已提示；保持已有数据以便重试
  } finally {
    loading.value = false
  }
}

// ============ 类型 / 格式化 ============
const typeMeta = (t: string): { text: string; variant: 'danger' | 'warning' | 'default' } => {
  const map: Record<string, { text: string; variant: 'danger' | 'warning' }> = {
    PHONE: { text: '手机号', variant: 'danger' },
    LICENSE_NO: { text: '执照号', variant: 'warning' },
  }
  return map[t] ?? { text: t || '—', variant: 'default' as const }
}

const formatTime = (iso?: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ============ 加黑弹窗（targetType + targetValue + reason 均必填，一次一个键值） ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<CreateBlacklistRequest>({
  targetType: 'PHONE',
  targetValue: '',
  reason: '',
})

/** 键值校验：必填；PHONE 校验手机号格式，LICENSE_NO 限长 */
const validateTargetValue = (_r: unknown, v: string, cb: (e?: Error) => void) => {
  const val = String(v ?? '').trim()
  if (!val) {
    cb(new Error(form.targetType === 'PHONE' ? '请输入要拉黑的手机号' : '请输入要拉黑的执照号'))
    return
  }
  if (form.targetType === 'PHONE' && !/^1[3-9]\d{9}$/.test(val)) {
    cb(new Error('请输入正确的 11 位手机号'))
    return
  }
  if (form.targetType === 'LICENSE_NO' && val.length > 64) {
    cb(new Error('执照号不超过 64 字'))
    return
  }
  cb()
}

const rules: FormRules = {
  targetValue: [{ validator: validateTargetValue, trigger: 'blur' }],
  reason: [
    { required: true, message: '请填写加黑原因', trigger: 'blur' },
    { min: 2, max: 512, message: '加黑原因为 2-512 字', trigger: 'blur' },
  ],
}

/** 切换键类型时清空键值与校验态 */
const onTypeChange = () => {
  form.targetValue = ''
  formRef.value?.clearValidate('targetValue')
}

const openCreate = () => {
  form.targetType = 'PHONE'
  form.targetValue = ''
  form.reason = ''
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: CreateBlacklistRequest = {
      targetType: form.targetType,
      targetValue: form.targetValue.trim(),
      reason: form.reason.trim(),
    }
    await opsApi.createBlacklist(payload)
    ElMessage.success('已加入黑名单，全平台入驻申请将被拦截')
    dialogVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示（重复加黑 50310）
  } finally {
    submitting.value = false
  }
}

// ============ 移除 ============
const removingId = ref('')

const onRemove = async (row: BlacklistItem) => {
  try {
    await ElMessageBox.confirm(
      `确认将${typeMeta(row.targetType).text}「${row.targetValue}」移出黑名单？移除后该键可正常提交入驻申请。`,
      '移除确认',
      {
        confirmButtonText: '移除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  removingId.value = String(row.id)
  try {
    await opsApi.removeBlacklist(String(row.id))
    ElMessage.success('已移出黑名单')
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    removingId.value = ''
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="ops-shell">
    <!-- 顶栏 -->
    <header class="ops-topbar">
      <div class="ops-topbar__left">
        <span class="ops-topbar__brand">仓储云</span>
        <span class="ops-topbar__divider">·</span>
        <span class="ops-topbar__store">平台运营</span>
      </div>

      <div class="ops-topbar__right">
        <el-button text @click="handleSwitchRole">
          <el-icon><Switch /></el-icon>
          切换角色
        </el-button>
        <el-button text :icon="Bell" class="ops-topbar__bell" />
        <el-dropdown trigger="click" @command="handleProfileMenu">
          <span class="ops-topbar__user">
            <el-avatar :size="28">O</el-avatar>
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
            <h2 class="page-head__title">黑名单</h2>
            <p class="page-head__sub">
              平台级黑名单（手机号 / 营业执照号双键）：命中的主体无法提交入驻申请，OPS 代建同样拦截
            </p>
          </div>
          <div class="page-head__actions">
            <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">加入黑名单</el-button>
          </div>
        </header>

        <section class="card">
          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="bl-table"
            empty-text="黑名单为空。点击右上角「加入黑名单」可按手机号或执照号拉黑"
          >
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <StatusBadge
                  :variant="typeMeta(row.targetType).variant"
                  :text="typeMeta(row.targetType).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="键值" min-width="180">
              <template #default="{ row }">
                <span class="cell-code">{{ row.targetValue || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="原因" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-muted">{{ row.reason || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="加入时间" width="160">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="danger"
                  :loading="removingId === String(row.id)"
                  @click="onRemove(row)"
                >
                  移除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <!-- 加黑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="加入黑名单"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="加黑后全平台生效"
        description="命中黑名单的手机号 / 执照号将无法提交入驻申请（含 OPS 代建），请谨慎操作。"
        class="bl-dialog__alert"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="键类型" prop="targetType">
          <el-radio-group v-model="form.targetType" @change="onTypeChange">
            <el-radio-button value="PHONE">手机号</el-radio-button>
            <el-radio-button value="LICENSE_NO">营业执照号</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="键值" prop="targetValue">
          <el-input
            v-model="form.targetValue"
            :placeholder="form.targetType === 'PHONE' ? '11 位手机号' : '统一社会信用代码'"
            :maxlength="form.targetType === 'PHONE' ? 11 : 64"
            clearable
          />
        </el-form-item>
        <el-form-item label="加黑原因" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="请填写加黑原因（必填），如：多次恶意刷单 / 伪造资质"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="onSubmit">确认加黑</el-button>
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

/* ===== 顶栏 ===== */
.ops-topbar {
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
.ops-topbar__left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-h3);
}
.ops-topbar__brand {
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.5px;
}
.ops-topbar__divider {
  opacity: 0.5;
}
.ops-topbar__store {
  font-weight: var(--font-weight-medium);
  opacity: 0.95;
}
.ops-topbar__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.ops-topbar__right :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
}
.ops-topbar__right :deep(.el-button.is-text:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}
.ops-topbar__bell :deep(.el-button.is-text) {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
}
.ops-topbar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  padding: 0 var(--space-2);
}
.ops-topbar__user :deep(.el-icon) {
  color: rgba(255, 255, 255, 0.7);
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

/* ===== 主区 ===== */
.ops-main {
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
.page-head__actions {
  display: flex;
  gap: var(--space-2);
  flex-shrink: 0;
}

/* ===== 卡片 + 表格 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.bl-table {
  width: 100%;
}
.cell-code {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}

.bl-dialog__alert {
  margin-bottom: var(--space-4);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ops-side {
    display: none;
  }
  .page-head {
    flex-direction: column;
  }
}
</style>
