<script setup lang="ts">
/**
 * 结算工作台（P4 W4 · 替换占位页，线框 13-p4 §8.3）
 *
 * - 本月账单概览三卡：应收/已收/未收（GET /tenant/st/bills?month=本月 汇总卡）
 * - 待处理：待核对账单 N / 待处理申诉 N / 争议中账单 N（快捷入口）
 * - 未设置计费规则 → 横幅「尚未设置计费规则，无法生成账单，请联系仓库老板前往设置」
 * 权限：requireStOrTa（仓库老板兼岗并集）；结算员不可见任何库存入口（05 §5.4）。
 */

import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { MoneyDisplay } from '@cangchu/ui-shared'
import { billingRuleApi, stBillApi } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'
import { currentMonth } from '@/utils/billing'
import StShell from './StShell.vue'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const month = currentMonth()

const summary = ref({ receivable: 0, received: 0, outstanding: 0 })
const draftCount = ref(0)
const disputedCount = ref(0)
const pendingDisputes = ref(0)
const noRule = ref(false)

const fetchAll = async () => {
  loading.value = true
  try {
    const [monthList, draftList, disputedList, disputes, rules] = await Promise.all([
      stBillApi.list({ month, page: 1, size: 1 }),
      stBillApi.list({ status: 'DRAFT', page: 1, size: 1 }),
      stBillApi.list({ status: 'DISPUTED', page: 1, size: 1 }),
      stBillApi.listDisputes('PENDING').catch(() => []),
      billingRuleApi.getRules().catch(() => null),
    ])
    summary.value = {
      receivable: Number(monthList?.receivable ?? 0),
      received: Number(monthList?.received ?? 0),
      outstanding: Number(monthList?.outstanding ?? 0),
    }
    draftCount.value = Number(draftList?.total ?? 0)
    disputedCount.value = Number(disputedList?.total ?? 0)
    pendingDisputes.value = disputes?.length ?? 0
    noRule.value = !!rules && !rules.current
  } catch {
    // 全局 toast 已提示
  } finally {
    loading.value = false
  }
}

onMounted(fetchAll)

const gotoBills = (status?: string) =>
  router.push(status ? { path: '/st/bills', query: { status } } : '/st/bills')
</script>

<template>
  <StShell active="/st/dashboard">
    <header class="page-head">
      <div>
        <h2 class="page-head__title">结算工作台</h2>
        <p class="page-head__sub">{{ month }} · {{ auth.currentStoreName || '本仓库' }}</p>
      </div>
    </header>

    <!-- 未设置计费规则横幅（PRD §2.2 逐字） -->
    <el-alert
      v-if="noRule"
      type="warning"
      :closable="false"
      show-icon
      data-test="st-no-rule-banner"
      title="尚未设置计费规则，无法生成账单，请联系仓库老板前往设置"
    />

    <!-- 本月三卡 -->
    <div v-loading="loading" class="stat-cards" data-test="st-month-cards">
      <div class="stat-card">
        <span class="stat-card__label">本月应收</span>
        <MoneyDisplay :value="summary.receivable" size="lg" />
      </div>
      <div class="stat-card">
        <span class="stat-card__label">已收</span>
        <MoneyDisplay :value="summary.received" size="lg" />
      </div>
      <div class="stat-card">
        <span class="stat-card__label">未收</span>
        <MoneyDisplay :value="summary.outstanding" size="lg" />
      </div>
    </div>

    <!-- 待处理 -->
    <section class="card">
      <h3 class="card__title">待处理</h3>
      <ul class="todo-list">
        <li class="todo-list__item">
          <span>待核对账单 {{ draftCount }} 张</span>
          <el-button text type="primary" data-test="st-goto-draft" @click="gotoBills('DRAFT')">
            去核对
          </el-button>
        </li>
        <li class="todo-list__item">
          <span>待处理申诉 {{ pendingDisputes }} 条</span>
          <el-button text type="primary" data-test="st-goto-disputes" @click="router.push('/st/disputes')">
            去处理
          </el-button>
        </li>
        <li class="todo-list__item">
          <span>争议中账单 {{ disputedCount }} 张</span>
          <el-button text type="primary" @click="gotoBills('DISPUTED')">查看</el-button>
        </li>
      </ul>
    </section>
  </StShell>
</template>

<style scoped>
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

.stat-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
}
.stat-card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: var(--shadow-base);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.stat-card__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-base);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0 0 var(--space-4);
}
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.todo-list__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border-1);
  color: var(--color-fg-2);
}
.todo-list__item:last-child {
  border-bottom: none;
}

@media (max-width: 768px) {
  .stat-cards {
    grid-template-columns: 1fr;
  }
}
</style>
