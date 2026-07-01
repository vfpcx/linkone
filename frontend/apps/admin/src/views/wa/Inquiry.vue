<script setup lang="ts">
/**
 * WA 询价确认（PC）— phase-1 C2 批发商确认询价单
 *
 * 来源：
 *  - 契约：backend/.../document/controller/InquiryController.java + InquiryServiceImpl
 *      GET  /api/v1/tenant/inquiry               列出本人归属 wholesaler 的询价单（登录态 WA）
 *      POST /api/v1/tenant/inquiry/{id}/confirm  确认 → PENDING→CONFIRMED→COMPLETED，自动转出库扣库存
 *    错误码 50280-50287（状态/越权/不存在等）、50251 库存不足（整体回滚，单仍 PENDING）。
 *  - 视觉：沿用 Inbound.vue 的顶栏 + 左侧菜单 shell + el-table 风格。
 *
 * 范围：仅 WA 询价确认（列表 + PENDING 单确认 → 展示已转出库），不碰 SKU/库存/入库页。
 *       WA 归属 / tenantId 由后端登录态推导，前端不传任何归属参数（G-2.1）。
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Switch,
  Bell,
  Document,
  Refresh,
  Check,
} from '@element-plus/icons-vue'
import type { Inquiry, InquiryStatus } from '@cangchu/api-types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { inquiryApi } from '@/api/inquiry'
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

// ============ 菜单（WA 端 · phase-1 仅询价确认可用） ============
const activeMenu = ref('/wa/inquiry')

const menus = [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
]

const handleMenuSelect = (key: string) => {
  if (key === '/wa/inquiry') {
    activeMenu.value = key
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

// ============ 状态徽章 ============
type BadgeType = 'warning' | 'primary' | 'success' | 'info'
const STATUS_META: Record<InquiryStatus, { label: string; type: BadgeType }> = {
  PENDING: { label: '待确认', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'primary' },
  COMPLETED: { label: '已转出库', type: 'success' },
}
const statusMeta = (s: string) =>
  STATUS_META[s as InquiryStatus] ?? { label: s, type: 'info' as BadgeType }

// ============ 金额 / 时间格式化 ============
const money = (v: number | null | undefined): string => {
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  return Number.isFinite(n) ? `¥${n.toFixed(2)}` : '—'
}

const formatTime = (v: string | null): string => {
  if (!v) return '—'
  // 后端为 LocalDateTime（无时区偏移），直接格式化本地展示串，不做时区转换
  return String(v).replace('T', ' ').slice(0, 19)
}

/** 单据明细行数（用于表内小计展示） */
const itemCount = (row: Inquiry): number => row.items?.length ?? 0

// ============ 询价单列表 ============
const loading = ref(false)
const inquiries = ref<Inquiry[]>([])

const fetchList = async () => {
  loading.value = true
  try {
    inquiries.value = await inquiryApi.list()
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 确认询价 ============
const confirmingId = ref<string>('')

const onConfirm = async (row: Inquiry) => {
  try {
    await ElMessageBox.confirm(
      `确认询价单「${row.docNo}」？确认后将自动生成出库单并扣减库存，此操作不可撤销。`,
      '确认询价',
      {
        confirmButtonText: '确认并出库',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // cancel
  }

  confirmingId.value = String(row.id)
  try {
    const updated = await inquiryApi.confirm(String(row.id))
    ElMessage.success(`询价单 ${updated.docNo} 已确认并转出库`)
    await fetchList()
  } catch (e) {
    // 库存不足 / 状态冲突等由全局 toast 提示；此处对典型码补充友好文案
    if (e instanceof ApiError) {
      if (e.code === 50251) {
        ElMessage.error('库存不足，已整体回滚，询价单仍为待确认')
      } else if (e.code === 50285) {
        // 并发/重复点击导致状态已变，刷新以回显最新状态
        await fetchList()
      }
    }
  } finally {
    confirmingId.value = ''
  }
}

onMounted(fetchList)
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
            <h2 class="page-head__title">询价确认</h2>
            <p class="page-head__sub">买家提交的询价单在此确认，确认后自动生成出库单并扣减库存</p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
        </header>

        <section class="card">
          <el-table
            v-loading="loading"
            :data="inquiries"
            row-key="id"
            class="inquiry-table"
            empty-text="暂无询价单，买家提交询价后将在此显示"
          >
            <!-- 展开明细 -->
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="inquiry-detail">
                  <el-table :data="row.items" size="small" class="inquiry-detail__table">
                    <el-table-column label="SKU" min-width="180">
                      <template #default="{ row: it }">
                        <span class="cell-name">{{ it.skuId }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="数量" width="100" align="right">
                      <template #default="{ row: it }">{{ it.qty }}</template>
                    </el-table-column>
                    <el-table-column label="快照单价" width="120" align="right">
                      <template #default="{ row: it }">{{ money(it.unitPriceSnapshot) }}</template>
                    </el-table-column>
                    <el-table-column label="起批价 / 起批量" width="160" align="right">
                      <template #default="{ row: it }">
                        {{ money(it.moqPriceSnapshot) }} / {{ it.moqQtySnapshot }}
                      </template>
                    </el-table-column>
                    <el-table-column label="成交价" width="120" align="right">
                      <template #default="{ row: it }">
                        <span class="cell-name">{{ money(it.dealPrice) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="docNo" label="询价单号" min-width="180">
              <template #default="{ row }">
                <span class="cell-name">{{ row.docNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).type" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="买家电话" width="150">
              <template #default="{ row }">
                <span class="cell-muted">{{ row.rtPhone || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="明细" width="90" align="right">
              <template #default="{ row }">
                <span class="cell-muted">{{ itemCount(row) }} 项</span>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="180">
              <template #default="{ row }">
                <span class="cell-muted">{{ formatTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'"
                  type="primary"
                  size="small"
                  :icon="Check"
                  :loading="confirmingId === String(row.id)"
                  @click="onConfirm(row)"
                >
                  确认
                </el-button>
                <span v-else class="cell-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
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

/* ===== 卡片 ===== */
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
}

/* ===== 表格 ===== */
.inquiry-table {
  width: 100%;
}
.inquiry-detail {
  padding: var(--space-3) var(--space-4);
  background: var(--color-bg-2);
}
.inquiry-detail__table {
  width: 100%;
}
.cell-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-fg-1);
}
.cell-muted {
  color: var(--color-fg-3);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
}
</style>
