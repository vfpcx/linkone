<script setup lang="ts">
/**
 * WA/WE 客户跟进（C3 · US-WE-04 · architecture/24-p5-c-c3 §4）
 *
 * 来源：
 *  - 契约：backend/.../document/controller/CustomerController.java + CustomerFollowupServiceImpl
 *      GET    /api/v1/tenant/customers                    客户列表（分页；仅打码号）
 *      GET    /api/v1/tenant/customers/{key}/detail      客户详情（统计 + 备注 + 全部提醒）
 *      PUT    /api/v1/tenant/customers/{key}/remark      备注覆盖（空串=清除，无提醒清档）
 *      POST   /api/v1/tenant/customers/{key}/reminders   新建提醒（remindAt 须晚于 now → 50841）
 *      DELETE /api/v1/tenant/customers/{key}/reminders/{rid}  删除提醒
 *    错误码 50840（客户不存在/越权假装不存在）、50841（提醒时间不在未来）、50842（提醒不存在）。
 *  - 查全号：复用 GET /pii/phone-reveal?biz=INQUIRY&id={lastInquiryId}（Inquiry.vue 先例）。
 *
 * 范围：客户 = 当前租户 + 登录人归属商户（WA 全部 + WE 授权位并集）的询价买家，
 *       按（wholesaler × rt_phone_hmac）归并（K-1）；列表无 keyword 搜索（K-6）；
 *       行内打码号仅展示，明文手机号永不落前端缓存/日志。
 * 视觉：沿用 wa 系顶栏 + 左侧菜单 shell + el-table 风格。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Document,
  Refresh,
  Shop,
  User,
  Warning as WarningIcon,
  Box,
  Van,
  RefreshLeft,
  AlarmClock,
  Coin,
  Plus,
} from '@element-plus/icons-vue'
import { AppTopbar } from '@cangchu/ui-shared'
import type { WaCustomer, WaCustomerDetail, WaFollowupReminder } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { customerApi } from '@/api/customers'
import { piiApi } from '@/api/pii'
import { accountApi } from '@/api/account'
import NotificationBell from '@/components/NotificationBell.vue'

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

// ============ 菜单（WA/WE 端；C3 入口紧跟询价确认） ============
const activeMenu = ref('/wa/customers')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  // P5-D C3 客户跟进（US-WE-04 · WE/WA 可见：WA 全量，WE 本商户直连）
  { key: '/wa/customers', label: '客户跟进', icon: ChatDotRound },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/outbound', label: '出库单', icon: Van },
  { key: '/wa/returns', label: '退货', icon: RefreshLeft },
  { key: '/wa/batches', label: '批次临期', icon: AlarmClock },
  // P4：账单仅批发商管理员可见（员工整域无入口，05 §5.4）
  ...(auth.roles?.some((r) => r.role === 'WA')
    ? [{ key: '/wa/bills', label: '账单', icon: Coin }]
    : []),
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/customers') {
    activeMenu.value = key
    return
  }
  router.push(key)
}

// ============ 时间格式化 ============
const formatTime = (v: string | null | undefined): string => {
  if (!v) return '—'
  // 后端 LocalDateTime（ISO 'T'）→ 本地展示串
  return String(v).replace('T', ' ').slice(0, 19)
}

/** 是否已到点（未触发且 remindAt ≤ now）——仅本地视觉标红，权威 due 计数在后端 */
const isDue = (r: WaFollowupReminder): boolean =>
  !r.remindedAt && Date.parse(r.remindAt) <= Date.now()

const dueCount = (d: WaCustomerDetail | null): number =>
  d?.reminders.filter((r) => isDue(r)).length ?? 0

// ============ 客户列表 ============
const loading = ref(false)
const customers = ref<WaCustomer[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)

const fetchList = async () => {
  loading.value = true
  try {
    const data = await customerApi.list({ page: page.value, size: size.value })
    customers.value = data.records ?? []
    total.value = Number(data.total) || 0
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

const onSizeChange = (s: number) => {
  size.value = s
  page.value = 1
  void fetchList()
}

// ============ 客户详情抽屉 ============
const drawerVisible = ref(false)
const detailLoading = ref(false)
const current = ref<WaCustomerDetail | null>(null)
/** 抽屉持有上下文：key + wholesalerId（同一手机号跨商户各自成行，行定位须带商户） */
const drawerCtx = ref<{ customerKey: string; wholesalerId: string } | null>(null)

/** 点行 / 「跟进」→ 打开详情抽屉（PII：仅打码号，查全号走独立 reveal） */
const openDetail = async (row: WaCustomer) => {
  drawerCtx.value = { customerKey: row.customerKey, wholesalerId: String(row.wholesalerId) }
  drawerVisible.value = true
  await reloadDetail()
}

const reloadDetail = async () => {
  const ctx = drawerCtx.value
  if (!ctx) return
  detailLoading.value = true
  try {
    const d = await customerApi.detail(ctx.customerKey, ctx.wholesalerId)
    current.value = d
    remarkDraft.value = d.remark ?? ''
  } catch {
    // 越权/删除清档后可能 50840 → 关闭抽屉并刷新列表
    drawerVisible.value = false
    await fetchList()
  } finally {
    detailLoading.value = false
  }
}

/** 查全号（复用 Inquiry.vue 先例）：只回打码号，WA/WE 线下联系买家时经 phone-reveal 取号（锚点 = 最新询价单） */
const revealPhoneOf = async (row: { lastInquiryId?: string; maskedPhone?: string }) => {
  if (!row.lastInquiryId) return
  try {
    const { phone } = await piiApi.revealPhone('INQUIRY', row.lastInquiryId)
    await ElMessageBox.alert(phone, `完整买家电话（客户 ${row.maskedPhone || ''}）`, {
      confirmButtonText: '关闭',
    })
  } catch {
    /* 无权限/对象不存在 → http.ts 已 toast */
  }
}

/** 抽屉内查全号（当前客户上下文） */
const revealPhone = async () => {
  const d = current.value
  if (!d) return
  await revealPhoneOf(d)
}

// ============ 备注编辑（覆盖式，空串=清除） ============
const remarkDraft = ref('')
const remarkSaving = ref(false)

const saveRemark = async () => {
  const ctx = drawerCtx.value
  if (!ctx) return
  remarkSaving.value = true
  try {
    // 覆盖式：原样提交；空白串视为清除（K-3）
    await customerApi.saveRemark(ctx.customerKey, {
      wholesalerId: ctx.wholesalerId,
      remark: remarkDraft.value.trim(),
    })
    ElMessage.success(remarkDraft.value.trim() ? '备注已保存' : '备注已清除')
    await reloadDetail()
    await fetchList()
  } catch {
    /* 50840 等全局 toast 已提示 */
  } finally {
    remarkSaving.value = false
  }
}

// ============ 跟进提醒管理 ============
const remContent = ref('')
const remAt = ref('')
const reminderSaving = ref(false)

/** datetime 选择禁用今天之前（本地校验；后端仍以 now 为准 50841） */
const disablePast = (d: Date): boolean => d.getTime() < Date.now() - 60_000

/** 默认时间 = 一小时后的整点附近，减少「选了今天过去时刻」的返工 */
const defaultTime = (): Date => new Date(Date.now() + 60 * 60 * 1000)

const resetReminderForm = () => {
  remContent.value = ''
  remAt.value = ''
}

const addReminder = async () => {
  const ctx = drawerCtx.value
  if (!ctx) return
  const content = remContent.value.trim()
  if (!content) {
    ElMessage.warning('请填写提醒内容')
    return
  }
  if (!remAt.value) {
    ElMessage.warning('请选择提醒时间')
    return
  }
  if (Date.parse(remAt.value) <= Date.now()) {
    ElMessage.warning('提醒时间须晚于当前时刻')
    return
  }
  reminderSaving.value = true
  try {
    await customerApi.addReminder(ctx.customerKey, {
      wholesalerId: ctx.wholesalerId,
      content,
      remindAt: remAt.value,
    })
    ElMessage.success('跟进提醒已设置，到点将通过站内信提醒')
    resetReminderForm()
    await reloadDetail()
    await fetchList()
  } catch {
    /* 50841（时间不在未来）等全局 toast 已提示 */
  } finally {
    reminderSaving.value = false
  }
}

const deleteReminder = async (r: WaFollowupReminder) => {
  const ctx = drawerCtx.value
  if (!ctx) return
  try {
    await ElMessageBox.confirm(`确认删除提醒「${r.content}」？`, '删除提醒', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 取消
  }
  try {
    await customerApi.deleteReminder(ctx.customerKey, ctx.wholesalerId, String(r.id))
    ElMessage.success('提醒已删除')
    await reloadDetail()
    await fetchList()
  } catch {
    /* 50840/50842 全局 toast 已提示 */
  }
}

/** 抽屉关闭后清空详情上下文 */
const onDrawerClosed = () => {
  current.value = null
  drawerCtx.value = null
}

onMounted(fetchList)
</script>

<template>
  <div class="wa-shell">
    <!-- 顶栏（公共组件 + 站内信铃铛） -->
    <AppTopbar
      :store-name="storeNameDisplay"
      @switch-role="handleSwitchRole"
      @profile-command="handleProfileMenu"
    >
      <template #bell>
        <NotificationBell />
      </template>
    </AppTopbar>

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
            <h2 class="page-head__title">客户跟进</h2>
            <p class="page-head__sub">本商户询价买家档案：维护跟进备注、设置到点站内信提醒，买卖双方沟通更及时</p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <section class="card">
          <el-table
            v-loading="loading"
            :data="customers"
            class="customer-table"
            empty-text="暂无询价客户，买家提交询价后将在此显示"
            @row-click="openDetail"
          >
            <el-table-column label="商户" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-name">{{ row.wholesalerName || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="买家电话" min-width="190">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.maskedPhone || '—' }}</span>
                <el-button
                  v-if="row.lastInquiryId"
                  link
                  type="primary"
                  class="reveal-link"
                  @click.stop="revealPhoneOf(row as WaCustomer)"
                >查看完整号</el-button>
              </template>
            </el-table-column>
            <el-table-column label="询价" width="90" align="right">
              <template #default="{ row }">{{ row.inquiryCount }} 次</template>
            </el-table-column>
            <el-table-column label="最近询价" width="170">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.lastInquiryAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近成交" width="170">
              <template #default="{ row }">
                <el-tag v-if="row.lastConfirmedAt" type="success" effect="light" size="small">
                  {{ formatTime(row.lastConfirmedAt) }}
                </el-tag>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="跟进备注" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.remark" class="cell-name">{{ row.remark }}</span>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="下次提醒" width="170">
              <template #default="{ row }">
                <span v-if="row.nextReminderAt" class="cell-muted">
                  {{ formatTime(row.nextReminderAt) }}
                </span>
                <el-tag
                  v-if="Number(row.dueReminderCount) > 0"
                  type="danger"
                  size="small"
                  effect="light"
                  class="due-tag"
                  data-test="customer-due-count"
                >
                  已到点 {{ row.dueReminderCount }}
                </el-tag>
                <span v-if="!row.nextReminderAt && !(Number(row.dueReminderCount) > 0)" class="cell-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click.stop="openDetail(row as WaCustomer)"
                >跟进</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="table-pager"
            layout="total, sizes, prev, pager, next"
            :total="total"
            :current-page="page"
            :page-size="size"
            :page-sizes="[20, 50, 100]"
            @current-change="onPageChange"
            @size-change="onSizeChange"
          />
        </section>
      </main>
    </div>

    <!-- 客户详情抽屉：统计 + 备注编辑 + 跟进提醒管理 -->
    <el-drawer
      v-model="drawerVisible"
      :title="current ? `客户跟进 · ${current.maskedPhone || ''}` : '客户跟进'"
      size="520px"
      append-to-body
      class="customer-drawer"
      @closed="onDrawerClosed"
    >
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="current">
          <!-- 客户概览 -->
          <section class="drawer-section">
            <h3 class="drawer-section__title">客户概览</h3>
            <el-descriptions :column="2" size="small" class="desc">
              <el-descriptions-item label="归属商户">{{ current.wholesalerName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="买家电话">
                <span class="cell-name">{{ current.maskedPhone || '—' }}</span>
                <el-button
                  v-if="current.lastInquiryId"
                  link
                  type="primary"
                  class="reveal-link"
                  data-test="customer-reveal"
                  @click="revealPhone"
                >查看完整号</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="询价次数">{{ current.inquiryCount }} 次</el-descriptions-item>
              <el-descriptions-item label="最近询价">{{ formatTime(current.lastInquiryAt) }}</el-descriptions-item>
              <el-descriptions-item label="最近成交">
                <el-tag v-if="current.lastConfirmedAt" type="success" effect="light" size="small">
                  {{ formatTime(current.lastConfirmedAt) }}
                </el-tag>
                <span v-else class="cell-muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="待处理提醒">
                <el-tag v-if="dueCount(current) > 0" type="danger" effect="light" size="small" data-test="drawer-due-count">
                  已到点 {{ dueCount(current) }} 条
                </el-tag>
                <span v-else class="cell-muted">无</span>
              </el-descriptions-item>
            </el-descriptions>
          </section>

          <!-- 跟进备注 -->
          <section class="drawer-section">
            <h3 class="drawer-section__title">跟进备注</h3>
            <el-input
              v-model="remarkDraft"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="记录客户跟进要点（如偏好、合作意向），保存即覆盖原备注；清空保存 = 清除备注"
              data-test="customer-remark-input"
            />
            <div class="section-actions">
              <span class="hint">
                <template v-if="current.remarkUpdatedAt">最近更新 {{ formatTime(current.remarkUpdatedAt) }}</template>
                <template v-else>尚未填写备注</template>
              </span>
              <el-button
                type="primary"
                size="small"
                :loading="remarkSaving"
                data-test="customer-remark-save"
                @click="saveRemark"
              >保存备注</el-button>
            </div>
          </section>

          <!-- 跟进提醒 -->
          <section class="drawer-section">
            <h3 class="drawer-section__title">跟进提醒</h3>
            <div class="reminder-add">
              <el-input
                v-model="remContent"
                maxlength="200"
                placeholder="提醒内容（到点站内信）"
                class="reminder-add__content"
                data-test="customer-reminder-content"
              />
              <el-date-picker
                v-model="remAt"
                type="datetime"
                placeholder="提醒时间"
                format="YYYY-MM-DD HH:mm"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :disabled-date="disablePast"
                :default-time="defaultTime"
                class="reminder-add__time"
                data-test="customer-reminder-time"
              />
              <el-button
                type="primary"
                size="default"
                :icon="Plus"
                :loading="reminderSaving"
                data-test="customer-reminder-add"
                @click="addReminder"
              >设置</el-button>
            </div>

            <div v-if="current.reminders.length" class="reminder-list" data-test="customer-reminder-list">
              <div
                v-for="r in current.reminders"
                :key="String(r.id)"
                class="reminder-item"
                :class="{ 'is-due': isDue(r) }"
              >
                <div class="reminder-item__head">
                  <el-tag
                    :type="r.remindedAt ? 'success' : isDue(r) ? 'danger' : 'info'"
                    size="small"
                    effect="light"
                  >
                    {{ r.remindedAt ? '已提醒' : isDue(r) ? '已到点' : '待提醒' }}
                  </el-tag>
                  <span class="reminder-item__time">{{ formatTime(r.remindAt) }}</span>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    class="reminder-item__del"
                    data-test="customer-reminder-delete"
                    @click="deleteReminder(r)"
                  >删除</el-button>
                </div>
                <div class="reminder-item__content">{{ r.content }}</div>
                <div v-if="r.remindedAt" class="reminder-item__note">
                  已于 {{ formatTime(r.remindedAt) }} 发送站内信
                </div>
              </div>
            </div>
            <el-empty
              v-else
              description="暂无跟进提醒"
              :image-size="48"
              class="reminder-empty"
            />
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.wa-shell {
  min-height: 100vh;
  background: var(--color-bg-2);
  display: flex;
  flex-direction: column;
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

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

.customer-table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}
.reveal-link {
  margin-left: var(--space-2);
}
.due-tag {
  margin-left: var(--space-2);
}
.table-pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

/* ===== 抽屉 ===== */
.drawer-body {
  min-height: 200px;
}
.drawer-section {
  padding: var(--space-3) 0;
}
.drawer-section + .drawer-section {
  border-top: 1px dashed var(--color-border-1);
}
.drawer-section__title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-h3);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.desc {
  line-height: 1.6;
}
.desc :deep(.el-descriptions__label) {
  color: var(--color-fg-3);
}

.section-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-3);
}
.hint {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

/* 新建提醒 */
.reminder-add {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.reminder-add__content {
  flex: 1;
  min-width: 0;
}
.reminder-add__time {
  width: 190px;
}

/* 提醒列表 */
.reminder-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.reminder-item {
  border: 1px solid var(--color-border-1);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--color-bg-2);
}
.reminder-item.is-due {
  border-color: var(--color-danger);
  background: var(--color-danger-bg);
}
.reminder-item__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.reminder-item__time {
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  flex: 1;
}
.reminder-item__content {
  margin-top: var(--space-2);
  color: var(--color-fg-1);
  line-height: 1.5;
  word-break: break-all;
}
.reminder-item__note {
  margin-top: var(--space-1);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.reminder-empty {
  padding: 0;
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
  .reminder-add {
    flex-wrap: wrap;
  }
  .reminder-add__time {
    flex: 1;
    width: auto;
  }
}
</style>
