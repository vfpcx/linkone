<script setup lang="ts">
/**
 * 账单列表（结算员/仓库老板兼岗 · P4 W4，线框 13-p4 §8.4 + §8.11 移动标注）
 *
 * - 筛选：账期（月）/ 状态（6 态有限枚举 el-select）/ 批发商（开放实体集 → EntityPickerDialog）
 * - 汇总卡三数：应收/已收/未收（按当前筛选，后端聚合）
 * - 手动补生成指定月份账单（幂等；无规则 50380；仅已结束月份）
 * - 响应式（D-P4-9 降档口径）：<768px 表格→卡片流一单一卡，汇总条吸底，触控热区 ≥44px
 */

import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoneyDisplay, StatusBadge, EntityPickerDialog, makeClientPickerFetch } from '@cangchu/ui-shared'
import type { Bill, Wholesaler } from '@cangchu/api-types'
import type { EntityPickerColumn } from '@cangchu/ui-shared'
import { stBillApi } from '@/api/billing'
import { wholesalerApi } from '@/api/wholesaler'
import { billStatusMeta, BILL_STATUS_META, currentMonth } from '@/utils/billing'
import StShell from './StShell.vue'

const route = useRoute()
const router = useRouter()

// ============ 筛选 ============
const filters = reactive({
  month: '' as string, // '' = 全部账期
  status: (typeof route.query.status === 'string' ? route.query.status : '') as string,
  wholesalerId: '' as string,
})

const page = ref(1)
const size = ref(20)

// 状态：有限枚举（6 态）保持 el-select（UX 选择控件规范）
const statusOptions = Object.entries(BILL_STATUS_META).map(([value, meta]) => ({
  value,
  label: meta.label,
}))

// 批发商：开放实体集 → EntityPickerDialog（前端过滤分页适配器）
const wholesalers = ref<Wholesaler[]>([])
const wholesalerName = computed(() => {
  const w = wholesalers.value.find((x) => String(x.id) === filters.wholesalerId)
  return w?.name ?? ''
})
const pickerColumns: EntityPickerColumn<Wholesaler>[] = [
  { label: '商户名称', prop: 'name', minWidth: 140 },
  { label: '状态', prop: 'status', width: 100 },
]
const pickerFetch = makeClientPickerFetch<Wholesaler>(
  async () => {
    if (wholesalers.value.length === 0) {
      try {
        wholesalers.value = (await wholesalerApi.list()) ?? []
      } catch {
        /* 权限或网络失败 → 空列表可手动清除筛选 */
      }
    }
    return wholesalers.value
  },
  (row, kw) => row.name.toLowerCase().includes(kw),
)

// ============ 列表 ============
const loading = ref(false)
const records = ref<Bill[]>([])
const total = ref(0)
const summary = ref({ receivable: 0, received: 0, outstanding: 0 })

const fetchList = async () => {
  loading.value = true
  try {
    const data = await stBillApi.list({
      month: filters.month || undefined,
      status: filters.status || undefined,
      wholesalerId: filters.wholesalerId || undefined,
      page: page.value,
      size: size.value,
    })
    records.value = data?.records ?? []
    total.value = Number(data?.total ?? 0)
    summary.value = {
      receivable: Number(data?.receivable ?? 0),
      received: Number(data?.received ?? 0),
      outstanding: Number(data?.outstanding ?? 0),
    }
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

const onFilterChange = () => {
  page.value = 1
  void fetchList()
}

const onPageChange = (p: number) => {
  page.value = p
  void fetchList()
}

onMounted(fetchList)

// ============ 手动补生成（US-ST-01 兜底） ============
const generating = ref(false)
const genMonth = ref('')

const lastMonth = (): string => {
  const d = new Date()
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

const onGenerate = async () => {
  const m = genMonth.value || lastMonth()
  try {
    await ElMessageBox.confirm(
      `将补生成 ${m} 月账单（已生成过的月份不会重复出账；仅可补生成已结束月份）。\n批发商退驻当月产生的费用，将在次月 1 日出账，请留意尾款账单。`,
      '补生成账单',
      { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  generating.value = true
  try {
    const res = await stBillApi.generate(m)
    ElMessage.success(
      `已生成 ${res.generated} 张，已存在 ${res.existing} 张，跳过 ${res.skipped} 个商户`,
    )
    filters.month = m
    onFilterChange()
  } catch {
    // 50380「计费规则未设置，无法生成账单」等：全局 toast 已提示
  } finally {
    generating.value = false
  }
}

// ============ 行操作 ============
/** 主操作按钮文案随状态（线框 §8.4：核对/查看/登记回款） */
const rowAction = (b: Bill): string => {
  if (b.status === 'DRAFT') return '核对'
  if (b.status === 'PENDING_PAYMENT' || b.status === 'PARTIAL_PAID') return '登记回款'
  return '查看'
}

const openDetail = (b: Bill, pay = false) =>
  router.push({ path: `/st/bills/${b.id}`, query: pay ? { pay: '1' } : undefined })

const onRowAction = (b: Bill) =>
  openDetail(b, b.status === 'PENDING_PAYMENT' || b.status === 'PARTIAL_PAID')
</script>

<template>
  <StShell active="/st/bills">
    <header class="page-head">
      <div>
        <h2 class="page-head__title">账单</h2>
        <p class="page-head__sub">每月 1 日凌晨自动生成上月账单；退驻商户尾款账单次月出账</p>
      </div>
      <div class="page-head__actions">
        <el-date-picker
          v-model="genMonth"
          type="month"
          format="YYYY-MM"
          value-format="YYYY-MM"
          placeholder="选择补生成月份"
          class="gen-month"
          data-test="bill-generate-month"
        />
        <el-button
          type="primary"
          :loading="generating"
          data-test="bill-generate-btn"
          @click="onGenerate"
        >
          补生成账单
        </el-button>
      </div>
    </header>

    <!-- 筛选行 -->
    <section class="filter-bar" data-test="bill-filters">
      <el-date-picker
        v-model="filters.month"
        type="month"
        format="YYYY-MM"
        value-format="YYYY-MM"
        placeholder="账期（全部）"
        clearable
        class="filter-bar__month"
        data-test="bill-filter-month"
        @change="onFilterChange"
      />
      <el-select
        v-model="filters.status"
        placeholder="状态（全部）"
        clearable
        class="filter-bar__status"
        data-test="bill-filter-status"
        @change="onFilterChange"
      >
        <el-option v-for="o in statusOptions" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
      <EntityPickerDialog
        v-model="filters.wholesalerId"
        title="选择批发商"
        placeholder="批发商（全部）"
        :columns="pickerColumns"
        :fetch="pickerFetch"
        :selected-label="wholesalerName"
        class="filter-bar__ws"
        @change="onFilterChange"
      />
    </section>

    <!-- 汇总卡三数（按当前筛选） -->
    <div class="summary-cards" data-test="bill-summary-cards">
      <div class="summary-card">
        <span class="summary-card__label">应收</span>
        <MoneyDisplay :value="summary.receivable" />
      </div>
      <div class="summary-card">
        <span class="summary-card__label">已收</span>
        <MoneyDisplay :value="summary.received" />
      </div>
      <div class="summary-card">
        <span class="summary-card__label">未收</span>
        <MoneyDisplay :value="summary.outstanding" />
      </div>
    </div>

    <!-- 桌面表格 -->
    <section class="card list-card">
      <el-table
        v-loading="loading"
        :data="records"
        class="bill-table"
        data-test="bill-table"
        @row-click="(row: Bill) => openDetail(row)"
      >
        <el-table-column label="账单编号" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.billNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="批发商" prop="wholesalerName" min-width="120" />
        <el-table-column label="账期" prop="billingMonth" width="90" />
        <el-table-column label="应收" align="right" width="120">
          <template #default="{ row }"><MoneyDisplay :value="row.totalAmount" size="sm" /></template>
        </el-table-column>
        <el-table-column label="已收" align="right" width="120">
          <template #default="{ row }"><MoneyDisplay :value="row.paidAmount" size="sm" /></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusBadge :variant="billStatusMeta(row.status).variant" :text="billStatusMeta(row.status).label" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              text
              type="primary"
              size="small"
              data-test="bill-row-action"
              @click.stop="onRowAction(row as Bill)"
            >
              {{ rowAction(row as Bill) }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无账单" :image-size="72" />
        </template>
      </el-table>

      <!-- 移动卡片流（<768px 表格降级，一单一卡；主按钮 ≥44px 热区） -->
      <div v-loading="loading" class="bill-cards" data-test="bill-cards">
        <el-empty v-if="!loading && records.length === 0" description="暂无账单" :image-size="72" />
        <div
          v-for="b in records"
          :key="String(b.id)"
          class="bill-card"
          data-test="bill-card"
          @click="openDetail(b)"
        >
          <div class="bill-card__head">
            <span class="bill-card__ws">{{ b.wholesalerName }}</span>
            <StatusBadge :variant="billStatusMeta(b.status).variant" :text="billStatusMeta(b.status).label" size="sm" />
          </div>
          <div class="bill-card__no mono">{{ b.billNo }}</div>
          <div class="bill-card__amounts">
            <span>应收 <MoneyDisplay :value="b.totalAmount" size="sm" /></span>
            <span>已收 <MoneyDisplay :value="b.paidAmount" size="sm" /></span>
          </div>
          <el-button
            type="primary"
            plain
            class="bill-card__action"
            @click.stop="onRowAction(b)"
          >
            {{ rowAction(b) }} →
          </el-button>
        </div>
      </div>

      <el-pagination
        v-if="total > size"
        class="pager"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </section>

    <!-- 汇总条（移动吸底） -->
    <footer class="summary-bar" data-test="bill-summary-bar">
      合计 {{ total }} 张 / 应收 <MoneyDisplay :value="summary.receivable" size="sm" /> / 已收
      <MoneyDisplay :value="summary.received" size="sm" /> / 未收
      <MoneyDisplay :value="summary.outstanding" size="sm" />
    </footer>
  </StShell>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
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
  align-items: center;
  flex-wrap: wrap;
}
.gen-month {
  width: 160px;
}

.filter-bar {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.filter-bar__month {
  width: 150px;
}
.filter-bar__status {
  width: 150px;
}
.filter-bar__ws {
  width: 220px;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
}
.summary-card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-5);
  box-shadow: var(--shadow-base);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.summary-card__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
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
.pager {
  margin-top: var(--space-3);
  justify-content: flex-end;
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

/* 移动卡片流默认隐藏（桌面走表格） */
.bill-cards {
  display: none;
}

/* ===== D-P4-9 移动降档（<768px）===== */
@media (max-width: 768px) {
  .bill-table {
    display: none;
  }
  .bill-cards {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .bill-card {
    border: 1px solid var(--color-border-1);
    border-radius: var(--radius-md);
    padding: var(--space-3);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .bill-card__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  .bill-card__ws {
    font-weight: var(--font-weight-semibold);
    color: var(--color-fg-1);
  }
  .bill-card__no {
    color: var(--color-fg-3);
    word-break: break-all;
  }
  .bill-card__amounts {
    display: flex;
    gap: var(--space-4);
    color: var(--color-fg-2);
    font-size: var(--font-size-caption);
    flex-wrap: wrap;
  }
  .bill-card__action {
    min-height: 44px; /* 触控热区 ≥44px */
    width: 100%;
  }
  .summary-bar {
    position: sticky;
    bottom: 0;
    z-index: 5;
  }
  .filter-bar__month,
  .filter-bar__status,
  .filter-bar__ws {
    width: 100%;
  }
  .summary-cards {
    grid-template-columns: repeat(3, 1fr);
    gap: var(--space-2);
  }
  .summary-card {
    padding: var(--space-2) var(--space-3);
  }
  .page-head__actions {
    width: 100%;
  }
  .gen-month {
    flex: 1;
  }
}
</style>
