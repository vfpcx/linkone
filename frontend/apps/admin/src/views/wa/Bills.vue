<script setup lang="ts">
/**
 * 批发商 · 账单列表（P4 W4，US-WA-08）
 *
 * - 仅批发商管理员可见可操作（批发商员工整域不可见：无菜单入口，直连提示「无权访问」）
 * - 仅展示已下发过的本商户账单（待核对不可见；撤回后按不存在，由通知知会）
 * - 争议中账单保留查看与导出知情权（PRD §7.2）
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Document,
  Box,
  Van,
  RefreshLeft,
  AlarmClock,
  Shop,
  User,
  Warning as WarningIcon,
  Coin,
} from '@element-plus/icons-vue'
import { AppTopbar, MoneyDisplay, StatusBadge } from '@cangchu/ui-shared'
import type { Bill } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { accountApi } from '@/api/account'
import { waBillApi } from '@/api/billing'
import { billStatusMeta, BILL_STATUS_META } from '@/utils/billing'
import NotificationBell from '@/components/NotificationBell.vue'

const router = useRouter()
const auth = useAuthStore()

/** 员工（批发商员工）整域不可见——直连渲染「无权访问」空态 */
const isWaAdmin = computed(() => auth.roles?.some((r) => r.role === 'WA') ?? false)

// ============ 顶栏/菜单（沿 WA 端 shell 惯例） ============
const handleSwitchRole = () => auth.showSwitcher()
const handleProfileMenu = async (key: string) => {
  if (key === 'logout') {
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
    return
  }
  ElMessage.info('该页面留给后续 Agent 实现')
}

const activeMenu = ref('/wa/bills')
const menus = computed(() => [
  { key: '/wa/inquiry', label: '询价确认', icon: Document },
  // P5-D C3 客户跟进（US-WE-04 · WE/WA 可见：WA 全量，WE 本商户直连）
  { key: '/wa/customers', label: '客户跟进', icon: ChatDotRound },
  { key: '/wa/inbound', label: '入库确认', icon: Box },
  { key: '/wa/outbound', label: '出库单', icon: Van },
  { key: '/wa/returns', label: '退货', icon: RefreshLeft },
  { key: '/wa/batches', label: '批次临期', icon: AlarmClock },
  ...(isWaAdmin.value ? [{ key: '/wa/bills', label: '账单', icon: Coin }] : []),
  { key: '/wa/apply', label: '入驻申请', icon: Shop },
  { key: '/wa/staff', label: '员工管理', icon: User },
  { key: '/wa/withdraw', label: '退驻申请', icon: WarningIcon },
])
const handleMenuSelect = (key: string) => {
  if (key === '/wa/bills') return
  router.push(key)
}

// ============ 列表 ============
const filters = reactive({ month: '', status: '' })
const loading = ref(false)
const records = ref<Bill[]>([])
const summary = ref({ receivable: 0, received: 0, outstanding: 0 })

const statusOptions = Object.entries(BILL_STATUS_META)
  .filter(([v]) => v !== 'DRAFT') // 待核对对批发商不可见
  .map(([value, meta]) => ({ value, label: meta.label }))

const fetchList = async () => {
  if (!isWaAdmin.value) return
  loading.value = true
  try {
    const data = await waBillApi.list({
      month: filters.month || undefined,
      status: filters.status || undefined,
    })
    records.value = data?.records ?? []
    summary.value = {
      receivable: Number(data?.receivable ?? 0),
      received: Number(data?.received ?? 0),
      outstanding: Number(data?.outstanding ?? 0),
    }
  } catch {
    // 42004 员工拒绝等：全局 toast 已提示
  } finally {
    loading.value = false
  }
}

onMounted(fetchList)

const openDetail = (b: Bill) => router.push(`/wa/bills/${b.id}`)
</script>

<template>
  <div class="wa-shell">
    <AppTopbar @switch-role="handleSwitchRole" @profile-command="handleProfileMenu">
      <template #bell><NotificationBell /></template>
    </AppTopbar>

    <div class="wa-body">
      <aside class="wa-side">
        <el-menu :default-active="activeMenu" class="wa-side__menu" @select="handleMenuSelect">
          <el-menu-item v-for="m in menus" :key="m.key" :index="m.key">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <main class="wa-main">
        <!-- 员工直连：整域拒绝 -->
        <el-result
          v-if="!isWaAdmin"
          icon="warning"
          title="无权访问"
          sub-title="账单仅批发商管理员可见"
          data-test="wa-bills-forbidden"
        />

        <template v-else>
          <header class="page-head">
            <div>
              <h2 class="page-head__title">账单</h2>
              <p class="page-head__sub">
                收到「账单已下发」通知后请及时核对；确认后按线下约定付款，仓库结算员收款后登记回款
              </p>
            </div>
          </header>

          <section class="filter-bar">
            <el-date-picker
              v-model="filters.month"
              type="month"
              format="YYYY-MM"
              value-format="YYYY-MM"
              placeholder="账期（全部）"
              clearable
              class="filter-bar__month"
              data-test="wa-bill-filter-month"
              @change="fetchList"
            />
            <el-select
              v-model="filters.status"
              placeholder="状态（全部）"
              clearable
              class="filter-bar__status"
              data-test="wa-bill-filter-status"
              @change="fetchList"
            >
              <el-option v-for="o in statusOptions" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
          </section>

          <section class="card">
            <el-table
              v-loading="loading"
              :data="records"
              class="bill-table"
              data-test="wa-bill-table"
              @row-click="(row: Bill) => openDetail(row)"
            >
              <el-table-column label="账单编号" min-width="200">
                <template #default="{ row }"><span class="mono">{{ row.billNo }}</span></template>
              </el-table-column>
              <el-table-column label="账期" prop="billingMonth" width="100" />
              <el-table-column label="应收" align="right" width="130">
                <template #default="{ row }"><MoneyDisplay :value="row.totalAmount" size="sm" /></template>
              </el-table-column>
              <el-table-column label="已收" align="right" width="130">
                <template #default="{ row }"><MoneyDisplay :value="row.paidAmount" size="sm" /></template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <StatusBadge :variant="billStatusMeta(row.status).variant" :text="billStatusMeta(row.status).label" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button text type="primary" size="small" data-test="wa-bill-row-open" @click.stop="openDetail(row as Bill)">
                    查看
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无账单（仅显示仓库已下发的账单）" :image-size="72" />
              </template>
            </el-table>
          </section>

          <footer class="summary-bar">
            应收 <MoneyDisplay :value="summary.receivable" size="sm" /> / 已收
            <MoneyDisplay :value="summary.received" size="sm" /> / 未收
            <MoneyDisplay :value="summary.outstanding" size="sm" />
          </footer>
        </template>
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
.wa-main {
  flex: 1;
  min-width: 0;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
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
.filter-bar {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.filter-bar__month,
.filter-bar__status {
  width: 160px;
}
.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-base);
}
.bill-table {
  width: 100%;
  cursor: pointer;
}
.mono {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
}
.summary-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  color: var(--color-fg-2);
  font-size: var(--font-size-caption);
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  box-shadow: var(--shadow-base);
}

@media (max-width: 768px) {
  .wa-side {
    display: none;
  }
  .wa-main {
    padding: var(--space-3);
  }
  .filter-bar__month,
  .filter-bar__status {
    width: 100%;
  }
}
</style>
