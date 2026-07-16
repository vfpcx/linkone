<script setup lang="ts">
/**
 * WA 入驻申请（PC）— P2 入驻生态 Wave4 前端第一批
 *
 * 来源：
 *  - 契约：POST /api/v1/wholesaler/applications（body: targetTenantId/name/contact/phone/license?）
 *    错误码：50201 审核中（重复提交）/ 50204 重复入驻 / 50205 黑名单拦截
 *  - 视觉：沿用 Inquiry.vue 的顶栏 + 左侧菜单 shell + 卡片表单（MASTER §4.3）
 *
 * 状态展示：
 *  - 优先 GET /wholesaler/applications（listMine，契约微调位）拉最新申请（含驳回理由）；
 *  - 后端未提供该端点 / 请求失败时，回退 localStorage 缓存的本地提交记录（优雅降级）；
 *  - PENDING 待审核 / APPROVED 已通过 / REJECTED 已驳回（含理由，可重新提交）。
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
  Warning as WarningIcon,
  Promotion,
} from '@element-plus/icons-vue'
import { StatusBadge } from '@cangchu/ui-shared'
import type {
  SubmitWaApplicationRequest,
  WaApplicationStatus,
  WholesalerApplication,
} from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { waApplicationApi } from '@/api/wholesaler'
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
const activeMenu = ref('/wa/apply')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/apply') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 申请状态（本地缓存 + listMine 双轨） ============
/** 本地缓存的最近一次提交（按 userId 隔离，防串号） */
interface LocalApplyRecord {
  applicationId: string
  status: WaApplicationStatus
  form: SubmitWaApplicationRequest
  submittedAt: string
  remark?: string
}

const cacheKey = computed(() => `cangchu-wa-apply:${auth.userId ?? 'anon'}`)

const readLocal = (): LocalApplyRecord | null => {
  try {
    const raw = localStorage.getItem(cacheKey.value)
    return raw ? (JSON.parse(raw) as LocalApplyRecord) : null
  } catch {
    return null
  }
}
const writeLocal = (rec: LocalApplyRecord) => {
  try {
    localStorage.setItem(cacheKey.value, JSON.stringify(rec))
  } catch {
    /* 存储不可用时忽略 */
  }
}

const loading = ref(false)
/** 当前展示的申请（null = 未申请过，显示表单） */
const application = ref<LocalApplyRecord | null>(null)
/** 已驳回后点了「重新提交」→ 强制显示表单 */
const resubmitting = ref(false)

const showForm = computed(
  () => !application.value || (application.value.status === 'REJECTED' && resubmitting.value),
)

type BadgeVariant = 'success' | 'warning' | 'danger' | 'default'
const statusMeta = (status: WaApplicationStatus): { variant: BadgeVariant; text: string } => {
  const map: Record<WaApplicationStatus, { variant: BadgeVariant; text: string }> = {
    PENDING: { variant: 'warning', text: '待审核' },
    APPROVED: { variant: 'success', text: '已通过' },
    REJECTED: { variant: 'danger', text: '已驳回' },
  }
  return map[status] ?? { variant: 'default', text: status }
}

const formatTime = (iso: string): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 服务端申请 → 本地展示结构 */
const fromServer = (a: WholesalerApplication): LocalApplyRecord => ({
  applicationId: String(a.applicationId),
  status: a.status,
  form: {
    targetTenantId: String(a.tenantId ?? ''),
    name: a.wholesalerName,
    contact: a.contactName,
    phone: a.contactPhone,
    license: a.licenseNo,
  },
  submittedAt: a.appliedAt,
  remark: a.remark,
})

const fetchStatus = async () => {
  loading.value = true
  try {
    const list = await waApplicationApi.listMine()
    if (Array.isArray(list) && list.length > 0) {
      // 取最新一条（后端倒序则取首条；兜底按 appliedAt 排）
      const latest = [...list].sort((a, b) =>
        String(b.appliedAt ?? '').localeCompare(String(a.appliedAt ?? '')),
      )[0]
      application.value = fromServer(latest)
      writeLocal(application.value)
      return
    }
    // 服务端明确无申请记录 → 清本地态，显示表单
    application.value = null
  } catch {
    // 端点未落地 / 网络失败 → 回退本地缓存（全局 toast 已按错误码提示）
    application.value = readLocal()
  } finally {
    loading.value = false
  }
}

// ============ 申请表单 ============
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<SubmitWaApplicationRequest>({
  targetTenantId: '',
  name: '',
  contact: '',
  phone: '',
  license: '',
})

const rules: FormRules = {
  targetTenantId: [
    { required: true, message: '请输入目标仓库 ID', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        const s = String(v ?? '').trim()
        if (!/^\d{1,20}$/.test(s)) {
          cb(new Error('仓库 ID 为纯数字（可向仓库老板索取）'))
        } else {
          cb()
        }
      },
      trigger: 'blur',
    },
  ],
  name: [
    { required: true, message: '请输入商户名', trigger: 'blur' },
    { max: 128, message: '商户名不超过 128 字', trigger: 'blur' },
  ],
  contact: [
    { required: true, message: '请输入联系人', trigger: 'blur' },
    { max: 64, message: '联系人不超过 64 字', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的 11 位手机号',
      trigger: 'blur',
    },
  ],
  license: [{ max: 64, message: '执照号不超过 64 字', trigger: 'blur' }],
}

const startResubmit = () => {
  const prev = application.value
  if (prev) {
    form.targetTenantId = prev.form.targetTenantId
    form.name = prev.form.name
    form.contact = prev.form.contact
    form.phone = prev.form.phone
    form.license = prev.form.license ?? ''
  }
  resubmitting.value = true
  formRef.value?.clearValidate()
}

const onSubmit = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: SubmitWaApplicationRequest = {
      targetTenantId: String(form.targetTenantId).trim(),
      name: form.name.trim(),
      contact: form.contact.trim(),
      phone: form.phone.trim(),
      license: form.license?.trim() || undefined,
    }
    const res = await waApplicationApi.submit(payload)
    const rec: LocalApplyRecord = {
      applicationId: String(res?.applicationId ?? ''),
      status: res?.status ?? 'PENDING',
      form: payload,
      submittedAt: new Date().toISOString(),
    }
    application.value = rec
    writeLocal(rec)
    resubmitting.value = false
    ElMessage.success('入驻申请已提交，等待仓库老板审核')
  } catch (e) {
    // 全局 toast 已按错误码提示；此处补充页面级状态回显
    if (e instanceof ApiError) {
      if (e.code === 50201) {
        // 已有申请在审 → 直接切到待审核态
        const rec: LocalApplyRecord = application.value ?? {
          applicationId: '',
          status: 'PENDING',
          form: { ...form },
          submittedAt: new Date().toISOString(),
        }
        rec.status = 'PENDING'
        application.value = rec
        writeLocal(rec)
        resubmitting.value = false
      } else if (e.code === 50204) {
        ElMessage.info('您已入驻该仓库，无需重复申请')
      } else if (e.code === 50205) {
        ElMessage.error('该手机号/执照号已被平台列入黑名单，无法提交入驻申请')
      }
    }
  } finally {
    submitting.value = false
  }
}

onMounted(fetchStatus)
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
            <h2 class="page-head__title">入驻申请</h2>
            <p class="page-head__sub">
              向目标仓库提交入驻申请，仓库老板审核通过后即可上架商品、接收询价
            </p>
          </div>
        </header>

        <div v-loading="loading" class="apply-content">
          <!-- 状态卡（已有申请记录时） -->
          <section v-if="application" class="card status-card">
            <div class="status-card__head">
              <span class="status-card__label">当前申请状态</span>
              <StatusBadge
                :variant="statusMeta(application.status).variant"
                :text="statusMeta(application.status).text"
                :dot="true"
              />
            </div>

            <el-alert
              v-if="application.status === 'PENDING'"
              type="warning"
              :closable="false"
              show-icon
              title="申请已提交，等待仓库老板审核"
              description="审核结果将以短信通知；审核期间无法重复提交申请。"
            />
            <el-alert
              v-else-if="application.status === 'APPROVED'"
              type="success"
              :closable="false"
              show-icon
              title="恭喜，入驻申请已通过"
              description="现在可以前往「询价确认」开始接单，或联系仓库老板上架商品。"
            />
            <el-alert
              v-else
              type="error"
              :closable="false"
              show-icon
              title="申请已被驳回"
              :description="`驳回理由：${application.remark || '仓库老板未填写（可短信/电话联系仓库确认）'}`"
            />

            <el-descriptions :column="2" class="status-card__desc" border>
              <el-descriptions-item label="商户名">
                {{ application.form.name || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="目标仓库 ID">
                <span class="cell-code">{{ application.form.targetTenantId || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="联系人">
                {{ application.form.contact || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="联系电话">
                <span class="cell-code">{{ application.form.phone || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="营业执照号">
                <span class="cell-code">{{ application.form.license || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="提交时间">
                {{ formatTime(application.submittedAt) }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="status-card__actions">
              <el-button
                v-if="application.status === 'REJECTED' && !resubmitting"
                type="primary"
                @click="startResubmit"
              >
                重新提交申请
              </el-button>
              <el-button
                v-if="application.status === 'APPROVED'"
                type="primary"
                @click="router.push('/wa/inquiry')"
              >
                前往询价确认
              </el-button>
            </div>
          </section>

          <!-- 申请表单（未申请 / 驳回后重新提交） -->
          <section v-if="showForm" class="card form-card">
            <h3 class="form-card__title">
              {{ resubmitting ? '重新提交入驻申请' : '填写入驻申请' }}
            </h3>
            <el-form
              ref="formRef"
              :model="form"
              :rules="rules"
              label-position="top"
              class="apply-form"
              @submit.prevent="onSubmit"
            >
              <el-form-item label="目标仓库 ID" prop="targetTenantId">
                <el-input
                  v-model="form.targetTenantId"
                  placeholder="请输入目标仓库的 ID（纯数字）"
                  maxlength="20"
                  clearable
                />
                <span class="form-hint">
                  仓库 ID 可向仓库老板索取，或从仓库分享的进店码信息中获得
                </span>
              </el-form-item>

              <el-form-item label="商户名" prop="name">
                <el-input
                  v-model="form.name"
                  placeholder="您的批发商户名称，如：XX 副食批发"
                  maxlength="128"
                  clearable
                />
              </el-form-item>

              <div class="apply-form__row">
                <el-form-item label="联系人" prop="contact" class="apply-form__col">
                  <el-input
                    v-model="form.contact"
                    placeholder="联系人姓名"
                    maxlength="64"
                    clearable
                  />
                </el-form-item>
                <el-form-item label="联系电话" prop="phone" class="apply-form__col">
                  <el-input
                    v-model="form.phone"
                    placeholder="11 位手机号"
                    maxlength="11"
                    clearable
                  />
                </el-form-item>
              </div>

              <el-form-item label="营业执照号（选填）" prop="license">
                <el-input
                  v-model="form.license"
                  placeholder="统一社会信用代码，如 91xxxxxxxxxxxxxxxx"
                  maxlength="64"
                  clearable
                />
                <span class="form-hint">填写执照号有助于加快审核</span>
              </el-form-item>

              <div class="apply-form__actions">
                <el-button v-if="resubmitting" @click="resubmitting = false">取消</el-button>
                <el-button
                  type="primary"
                  :icon="Promotion"
                  :loading="submitting"
                  @click="onSubmit"
                >
                  提交申请
                </el-button>
              </div>
            </el-form>
          </section>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.wa-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏 ===== */
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

/* ===== body ===== */
.wa-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ===== 左侧菜单 ===== */
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

/* ===== 内容 ===== */
.apply-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 720px;
}

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}

/* 状态卡 */
.status-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.status-card__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.status-card__label {
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.status-card__desc {
  margin-top: var(--space-1);
}
.status-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
.status-card__actions:empty {
  display: none;
}

/* 表单卡 */
.form-card__title {
  margin: 0 0 var(--space-4);
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.apply-form__row {
  display: flex;
  gap: var(--space-4);
}
.apply-form__col {
  flex: 1;
  min-width: 0;
}
.apply-form__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.form-hint {
  display: block;
  width: 100%;
  margin-top: 4px;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
  line-height: var(--line-height-normal);
}
.cell-code {
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-variant-numeric: tabular-nums;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-4);
    min-width: 0;
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
  .apply-form__row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
