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
 * 范围：TA 商户管理（列表 + 新建 + 编辑资料）。
 * Wave4b 追加（R14 强制下架，06b §4）：
 *  - POST /tenant/wholesalers/{id}/force-offline  body:{reason}（5~200 字）
 *  - 弹窗双条件解锁：原因 ≥5 字 且 输入商户名完全一致（大小写/空格敏感）
 *  - TA 单方即时生效；已下架行 ⚫ 标签、无恢复按钮（已下架→正常不可达）
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
  Plus,
  Stamp,
} from '@element-plus/icons-vue'
import { AppTopbar, StatusBadge } from '@cangchu/ui-shared'
import type {
  Wholesaler,
  CreateWholesalerRequest,
  UpdateWholesalerRequest,
} from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import WarehouseSwitcher from '@/components/WarehouseSwitcher.vue'
import { wholesalerApi } from '@/api/wholesaler'
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
  { key: '/ta/wholesaler-applications', label: '入驻审批', icon: Stamp },
  { key: '/ta/skus', label: '商品', icon: Goods },
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
  if (
    key === '/ta/dashboard' ||
    key === '/ta/settings' ||
    key === '/ta/skus' ||
    key === '/ta/employees' ||
    key === '/ta/wholesaler-applications'
  ) {
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
    // P2 入驻生态状态机（04 §1.8）：已下架不可原地恢复 / 已退驻 60 天恢复期 / 归档
    OFFLINE: { variant: 'default', text: '已下架' },
    FORCE_OFFLINE: { variant: 'default', text: '已下架' },
    WITHDRAWN: { variant: 'default', text: '已退驻' },
    ARCHIVED: { variant: 'default', text: '已归档' },
  }
  return map[status] ?? { variant: 'default', text: status || '—' }
}

/** DEF-5：来源列中文映射（与状态列中文 tag 口径一致），未知枚举兜底直出 */
const sourceLabel = (source: string): string => {
  const map: Record<string, string> = {
    SELF_APPLY: '自助申请',
    OPS_CREATED: 'OPS 代建',
    TA_SELF_OPERATED: '自营',
    SELF_OPERATED: '自营',
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

// ============ 强制下架（R14 · 06b §4） ============
const offlineVisible = ref(false)
const offlineSubmitting = ref(false)
const offlineTarget = ref<Wholesaler | null>(null)

const offlineForm = reactive({
  reason: '',
  confirmName: '',
})

const openForceOffline = (row: Wholesaler) => {
  offlineTarget.value = row
  offlineForm.reason = ''
  offlineForm.confirmName = ''
  offlineVisible.value = true
}

/** 双条件解锁：原因 ≥5 字 且 商户名完全一致（大小写/空格敏感，实时比对） */
const reasonOk = computed(() => offlineForm.reason.trim().length >= 5)
const nameMatched = computed(
  () => !!offlineTarget.value && offlineForm.confirmName === offlineTarget.value.name,
)
const nameMismatch = computed(() => offlineForm.confirmName.length > 0 && !nameMatched.value)
const canForceOffline = computed(() => reasonOk.value && nameMatched.value)

const onForceOffline = async () => {
  if (!offlineTarget.value || !canForceOffline.value) return
  offlineSubmitting.value = true
  try {
    await tenantApi.forceOfflineWa(String(offlineTarget.value.id), {
      reason: offlineForm.reason.trim(),
    })
    ElMessage.success('已强制下架')
    offlineVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示（含 50xxx"商户状态已变化"并发场景，提示后可手动刷新）
  } finally {
    offlineSubmitting.value = false
  }
}

onMounted(fetchList)
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
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row as Wholesaler)">编辑</el-button>
                <!-- 仅"正常"状态可强制下架；已退驻/已下架不出现入口（状态机不可达） -->
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  link
                  type="danger"
                  @click="openForceOffline(row as Wholesaler)"
                >
                  强制下架
                </el-button>
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

    <!-- 强制下架确认弹窗（R14 · 06b §4，双条件解锁） -->
    <el-dialog
      v-model="offlineVisible"
      width="520px"
      :close-on-click-modal="false"
    >
      <template #header>
        <span class="offline-dialog__title">
          🔴 强制下架商户 · {{ offlineTarget?.name }}
        </span>
      </template>

      <p class="offline-dialog__lead">此操作立即生效且不可原地恢复，请确认：</p>
      <ul class="offline-dialog__points">
        <li class="is-deny">✕ 店铺页立即隐藏该商户及全部商品</li>
        <li class="is-deny">✕ 新询价、新出库申请立即拒绝</li>
        <li class="is-allow">✓ 已确认意向单、已生成出库单允许完成（老单据放行）</li>
        <li class="is-warn">⚠️ 未结账单将标记"争议中"，转平台仲裁</li>
        <li class="is-warn">⚠️ 该商户如需回归，必须重新走入驻申请</li>
      </ul>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="下架原因" required>
          <el-input
            v-model="offlineForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="5~200 字，留痕并通知商户"
          />
          <span v-if="offlineForm.reason.length > 0 && !reasonOk" class="offline-hint is-error">
            下架原因至少 5 字
          </span>
        </el-form-item>

        <el-form-item :label="`请输入商户名称「${offlineTarget?.name ?? ''}」以确认`" required>
          <el-input
            v-model="offlineForm.confirmName"
            :placeholder="offlineTarget?.name"
            maxlength="50"
          />
          <span v-if="nameMismatch" class="offline-hint">名称不一致</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="offlineVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="!canForceOffline"
          :loading="offlineSubmitting"
          @click="onForceOffline"
        >
          确认强制下架
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

/* ===== 强制下架弹窗 ===== */
.offline-dialog__title {
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-danger, #f53f3f);
}
.offline-dialog__lead {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-1);
  font-weight: var(--font-weight-medium);
}
.offline-dialog__points {
  list-style: none;
  margin: 0 0 var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg-2);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  font-size: var(--font-size-body);
}
.offline-dialog__points .is-deny {
  color: var(--color-danger, #f53f3f);
}
.offline-dialog__points .is-allow {
  color: var(--color-success, #00b42a);
}
.offline-dialog__points .is-warn {
  color: var(--color-warning, #ff7d00);
}
.offline-hint {
  display: block;
  width: 100%;
  margin-top: 4px;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
}
.offline-hint.is-error {
  color: var(--color-danger, #f53f3f);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .ta-side {
    display: none;
  }
}
</style>
