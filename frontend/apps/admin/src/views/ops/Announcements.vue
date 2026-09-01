<script setup lang="ts">
/**
 * OPS 公告管理（PC · P5-A W4 · 18-p5-design §4.2）
 *
 * 契约（backend notify/AnnouncementController，OPS 登录态）：
 *  - GET    /ops/announcements?page=&size=&status=     分页列表
 *  - POST   /ops/announcements  创建草稿（{title, content, targetRoles[]}；title≤128、content≤512）
 *  - POST   /ops/announcements/{id}/publish    发布（DRAFT→PUBLISHED；同事务写目标角色站内信）
 *  - POST   /ops/announcements/{id}/inactivate 下架（PUBLISHED→INACTIVE）
 *  - GET    /ops/announcements/{id}            详情（列表已含全字段，详情端点预留）
 *
 * 展示规范：状态码/角色组码一律经 ui-shared announcement 映射为中文，禁止直显英文码。
 * 视觉沿用 OPS 端 shell（顶栏 + 左侧菜单）+ el-table/el-dialog。
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Monitor,
  CircleClose,
  ScaleToOriginal,
  Stamp,
  Bell,
  Plus,
  Refresh,
  Upload,
  Hide,
} from '@element-plus/icons-vue'
import { AppTopbar, StatusBadge } from '@cangchu/ui-shared'
import { announcementGroupLabel, announcementStatusLabel } from '@cangchu/ui-shared'
import type { Announcement, AnnouncementTargetRoleGroup } from '@cangchu/api-types'
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
const activeMenu = ref('/ops/announcements')

const menus = [
  { key: '/ops/dashboard', label: '运营控制台', icon: Monitor },
  { key: '/ops/tenant-audit', label: '租户审核', icon: Stamp },
  { key: '/ops/blacklist', label: '黑名单', icon: CircleClose },
  { key: '/ops/announcements', label: '公告管理', icon: Bell },
  { key: '/ops/arbitrations', label: '客诉仲裁', icon: ScaleToOriginal },
]

const handleMenuSelect = (key: string) => {
  if (key === '/ops/announcements') {
    activeMenu.value = key
    return
  }
  if (key === '/ops/dashboard' || key === '/ops/tenant-audit' || key === '/ops/blacklist' || key === '/ops/arbitrations') {
    router.push(key)
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 列表 ============
const loading = ref(false)
const list = ref<Announcement[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
/** 状态筛选：空=全部 */
const statusFilter = ref<'DRAFT' | 'PUBLISHED' | 'INACTIVE' | ''>('')

const fetchList = async () => {
  loading.value = true
  try {
    const data = await opsApi.listAnnouncements({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
    })
    list.value = data?.records ?? []
    total.value = data?.total ?? 0
    if (data?.page) page.value = data.page
    if (data?.size) size.value = data.size
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onPageChange = (p: number) => {
  page.value = p
  fetchList()
}

const onSizeChange = (s: number) => {
  size.value = s
  page.value = 1
  fetchList()
}

const onStatusFilterChange = () => {
  page.value = 1
  fetchList()
}

// ============ 展示映射 ============
const statusMeta = (
  s: string,
): { text: string; variant: 'default' | 'success' | 'warning' | 'danger' | 'archived' } => {
  const map: Record<string, { text: string; variant: 'default' | 'success' | 'warning' | 'danger' | 'archived' }> = {
    DRAFT: { text: '草稿', variant: 'default' },
    PUBLISHED: { text: '已发布', variant: 'success' },
    INACTIVE: { text: '已下架', variant: 'archived' },
  }
  return map[s] ?? { text: announcementStatusLabel(s), variant: 'default' }
}

const roleGroupText = (roles?: string[]): string => {
  if (!roles || roles.length === 0) return '—'
  return roles.map((r) => announcementGroupLabel(r)).join('、')
}

const formatTime = (iso?: string | null): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).replace('T', ' ').slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ============ 创建公告弹窗 ============
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  title: '',
  content: '',
  targetRoles: [] as AnnouncementTargetRoleGroup[],
})

/** 目标角色组选项（KEY → 中文，经 ui-shared 映射，禁止直显英文码） */
const roleGroupOptions: { value: AnnouncementTargetRoleGroup; label: string }[] = [
  { value: 'ALL', label: announcementGroupLabel('ALL') },
  { value: 'OPS', label: announcementGroupLabel('OPS') },
  { value: 'TA', label: announcementGroupLabel('TA') },
  { value: 'WK_ST', label: announcementGroupLabel('WK_ST') },
  { value: 'WA_WE', label: announcementGroupLabel('WA_WE') },
]

const rules: FormRules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 128, message: '公告标题最多 128 字', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入公告正文', trigger: 'blur' },
    { max: 512, message: '公告正文最多 512 字', trigger: 'blur' },
  ],
  targetRoles: [
    {
      validator: (_r, v: string[], cb) => {
        if (!v || v.length === 0) cb(new Error('请至少选择一个目标角色'))
        else cb()
      },
      trigger: 'change',
    },
  ],
}

const openCreate = () => {
  form.title = ''
  form.content = ''
  form.targetRoles = []
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const onCreate = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await opsApi.createAnnouncement({
      title: form.title.trim(),
      content: form.content.trim(),
      targetRoles: form.targetRoles,
    })
    ElMessage.success('公告草稿已创建，可在列表发布')
    dialogVisible.value = false
    await fetchList()
  } catch {
    // 全局 toast 已提示
  } finally {
    submitting.value = false
  }
}

// ============ 发布 / 下架 ============
const actingId = ref('')

const onPublish = async (row: Announcement) => {
  try {
    await ElMessageBox.confirm(
      `发布后，${roleGroupText(row.targetRoles)}登录后将收到该公告的站内信。确认发布？`,
      '发布公告',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  actingId.value = String(row.id)
  try {
    await opsApi.publishAnnouncement(String(row.id))
    ElMessage.success('公告已发布')
    await fetchList()
  } catch {
    // 50702 等：全局 toast 已提示
  } finally {
    actingId.value = ''
  }
}

const onInactivate = async (row: Announcement) => {
  try {
    await ElMessageBox.confirm(
      `确认下架「${row.title}」？已发送的站内信将保留，但新用户登录不再展示。`,
      '下架公告',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  actingId.value = String(row.id)
  try {
    await opsApi.inactivateAnnouncement(String(row.id))
    ElMessage.success('公告已下架')
    await fetchList()
  } catch {
    // 50702 等：全局 toast 已提示
  } finally {
    actingId.value = ''
  }
}

onMounted(fetchList)
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
          </el-menu-item>
        </el-menu>
      </aside>

      <!-- 主区 -->
      <main class="ops-main">
        <header class="page-head">
          <div>
            <h2 class="page-head__title">公告管理</h2>
            <p class="page-head__sub">
              面向各角色发布的平台公告：创建草稿 → 发布（目标角色登录即弹）→ 可下架
            </p>
          </div>
          <div class="page-head__actions">
            <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate" data-test="create-announcement">
              新建公告
            </el-button>
          </div>
        </header>

        <section class="card">
          <!-- 状态筛选 -->
          <div class="ann-toolbar">
            <el-radio-group v-model="statusFilter" @change="onStatusFilterChange">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="DRAFT">草稿</el-radio-button>
              <el-radio-button value="PUBLISHED">已发布</el-radio-button>
              <el-radio-button value="INACTIVE">已下架</el-radio-button>
            </el-radio-group>
          </div>

          <el-table
            v-loading="loading"
            :data="list"
            stripe
            class="ann-table"
            :empty-text="'暂无公告。点击右上角「新建公告」创建第一条'"
          >
            <el-table-column label="标题" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-title">{{ row.title || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="目标角色" min-width="180">
              <template #default="{ row }">
                <span class="cell-muted">{{ roleGroupText(row.targetRoles) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <StatusBadge
                  :variant="statusMeta(row.status).variant"
                  :text="statusMeta(row.status).text"
                  :dot="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="发布时间" width="160">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.publishedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="160">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'DRAFT'">
                  <el-button
                    link
                    type="primary"
                    :icon="Upload"
                    :loading="actingId === String(row.id)"
                    @click="onPublish(row as Announcement)"
                  >
                    发布
                  </el-button>
                </template>
                <template v-else-if="row.status === 'PUBLISHED'">
                  <el-button
                    link
                    type="warning"
                    :icon="Hide"
                    :loading="actingId === String(row.id)"
                    @click="onInactivate(row as Announcement)"
                  >
                    下架
                  </el-button>
                </template>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <div class="ann-pagination">
            <el-pagination
              :current-page="page"
              :page-size="size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="onPageChange"
              @size-change="onSizeChange"
            />
          </div>
        </section>
      </main>
    </div>

    <!-- 新建公告弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建公告"
      width="560px"
      :close-on-click-modal="false"
      data-test="announcement-dialog"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onCreate"
      >
        <el-form-item label="公告标题" prop="title">
          <el-input
            v-model="form.title"
            placeholder="如：平台春节放假通知"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="公告正文" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="512"
            show-word-limit
            placeholder="填写公告正文（≤512 字）"
          />
        </el-form-item>
        <el-form-item label="目标角色" prop="targetRoles">
          <el-checkbox-group v-model="form.targetRoles">
            <el-checkbox v-for="g in roleGroupOptions" :key="g.value" :value="g.value">
              {{ g.label }}
            </el-checkbox>
          </el-checkbox-group>
          <p class="role-hint">发布后目标角色登录将在首页看到该公告弹窗，并在消息中心「公告」分组可见</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" data-test="announcement-submit" @click="onCreate">
          创建草稿
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

.ops-body {
  flex: 1;
  display: flex;
  min-height: calc(100vh - 56px);
}

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

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}
.ann-toolbar {
  margin-bottom: var(--space-4);
}
.ann-table {
  width: 100%;
}
.ann-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-4);
}
.cell-title {
  color: var(--color-fg-1);
  font-weight: var(--font-weight-medium);
}
.cell-muted {
  color: var(--color-fg-3);
}
.role-hint {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

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
  :deep(.el-dialog) {
    width: calc(100vw - 32px) !important;
    max-width: 560px;
  }
}
</style>
