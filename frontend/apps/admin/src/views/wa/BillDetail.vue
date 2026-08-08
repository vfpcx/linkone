<script setup lang="ts">
/**
 * 批发商 · 账单详情（P4 W4，US-WA-08 · 线框 13-p4 §8.8/§8.9）
 *
 * - 只读知情权：明细（按货品）、回款记录、申诉记录；争议中仍可查看（PRD §7.2）
 * - 确认对账 → 待回款（提示线下付款口径；下发满 1 天未确认自动进入待回款）
 * - 发起申诉：下发后 7 天窗（50378）；行级可选争议条目 + 附图 ≤5；
 *   同账单同时只能有一张待处理申诉（50382，按钮置灰）；申诉不冻结账单
 * - 出库客诉边界（D43）：申诉入口带说明，出库货损/数量走出库单客诉
 * 偏差注：按日下钻为结算员端能力（后端未提供批发商按日端点），本页仅按货品明细。
 */

import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoneyDisplay, StatusBadge } from '@cangchu/ui-shared'
import type { BillDetail, BillItem } from '@cangchu/api-types'
import { useAuthStore } from '@/stores/auth'
import { waBillApi } from '@/api/billing'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import {
  billStatusMeta,
  billItemTypeLabel,
  payMethodLabel,
  disputeStatusMeta,
  fmtDateTime,
  DISPUTED_BANNER,
} from '@/utils/billing'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const billId = String(route.params.id ?? '')

const isWaAdmin = computed(() => auth.roles?.some((r) => r.role === 'WA') ?? false)

// 移动全屏弹窗
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)
const onMq = (e: MediaQueryListEvent) => (isMobile.value = e.matches)
mq.addEventListener('change', onMq)
onBeforeUnmount(() => mq.removeEventListener('change', onMq))

// ============ 详情 ============
const loading = ref(false)
const detail = ref<BillDetail | null>(null)
const bill = computed(() => detail.value?.bill ?? null)
const frozen = computed(() => bill.value?.status === 'DISPUTED')

const fetchDetail = async () => {
  loading.value = true
  try {
    detail.value = await waBillApi.detail(billId)
  } catch {
    // 50370 按不存在（含被撤回）：全局 toast 已提示
  } finally {
    loading.value = false
  }
}
onMounted(fetchDetail)

const itemLabel = (it: BillItem): string =>
  it.itemType === 'STORAGE' ? (it.skuName ?? '—') : (it.description ?? it.skuName ?? '—')
const itemPeriod = (it: BillItem): string =>
  it.periodStart && it.periodEnd ? `${it.periodStart.slice(5)}~${it.periodEnd.slice(5)}` : '—'

// ============ 确认对账 ============
const confirming = ref(false)
const canConfirm = computed(() => bill.value?.status === 'DISPATCHED')

const onConfirm = async () => {
  try {
    await ElMessageBox.confirm(
      '确认后请按线下约定方式付款，仓库结算员收款后将登记回款。',
      '确认对账',
      { confirmButtonText: '确认对账', cancelButtonText: '再看看', type: 'warning' },
    )
  } catch {
    return
  }
  confirming.value = true
  try {
    await waBillApi.confirm(billId)
    ElMessage.success('已确认对账，账单进入待回款')
    await fetchDetail()
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    confirming.value = false
  }
}

// ============ 发起申诉（7 天窗 / 单张待处理） ============
const hasPendingDispute = computed(
  () => detail.value?.disputes?.some((d) => d.status === 'PENDING') ?? false,
)
/** 前端预判 7 天窗（权威在后端 50378，此处仅控制按钮态） */
const inDisputeWindow = computed(() => {
  const at = bill.value?.dispatchAt
  if (!at) return false
  const t = new Date(String(at).replace(' ', 'T')).getTime()
  return Number.isFinite(t) && Date.now() - t <= 7 * 24 * 60 * 60 * 1000
})
const canDispute = computed(
  () => !frozen.value && !hasPendingDispute.value && !!bill.value?.dispatchAt && inDisputeWindow.value,
)
const disputeDisabledTip = computed(() => {
  if (frozen.value) return '账单争议中，操作受限'
  if (hasPendingDispute.value) return '该账单已有待处理申诉'
  if (!inDisputeWindow.value) return '申诉期已过（账单下发后 7 天内可提）'
  return ''
})

const disputeVisible = ref(false)
const disputeSaving = ref(false)
const disputeForm = reactive({
  reason: '',
  itemIds: [] as string[],
  attachments: [] as string[],
})

/** 行级可选条目=账单实际费用行（仓储费/调整；冲销与盘点影响不可选） */
const disputableItems = computed(
  () =>
    detail.value?.items?.filter(
      (it) => it.itemType === 'STORAGE' || it.itemType === 'ADJUSTMENT',
    ) ?? [],
)

const openDispute = () => {
  if (!canDispute.value) return
  disputeForm.reason = ''
  disputeForm.itemIds = []
  disputeForm.attachments = []
  disputeVisible.value = true
}

const onSubmitDispute = async () => {
  if (!disputeForm.reason.trim()) {
    ElMessage.warning('请填写申诉理由')
    return
  }
  disputeSaving.value = true
  try {
    await waBillApi.dispute(billId, {
      reason: disputeForm.reason.trim(),
      disputedItemIds: disputeForm.itemIds.length ? disputeForm.itemIds : undefined,
      attachments: disputeForm.attachments.length ? disputeForm.attachments : undefined,
    })
    disputeVisible.value = false
    ElMessage.success('申诉已提交，结算员处理结果将通知您')
    await fetchDetail()
  } catch {
    // 50378 超窗 / 50382 已有待处理 / 50377 条目无效：全局 toast 已提示
  } finally {
    disputeSaving.value = false
  }
}
</script>

<template>
  <div class="wa-detail-page" v-loading="loading">
    <el-result
      v-if="!isWaAdmin"
      icon="warning"
      title="无权访问"
      sub-title="账单仅批发商管理员可见"
    />

    <template v-else>
      <header class="detail-head">
        <el-button text data-test="wa-bill-back" @click="router.push('/wa/bills')">← 账单</el-button>
        <div class="detail-head__body">
          <span class="detail-head__store">{{ auth.currentStoreName || '仓库' }}</span>
          <span class="mono">{{ bill?.billNo ?? '…' }}</span>
          <StatusBadge
            v-if="bill"
            :variant="billStatusMeta(bill.status).variant"
            :text="billStatusMeta(bill.status).label"
            data-test="wa-bill-status"
          />
        </div>
        <p v-if="bill" class="detail-head__period">账期 {{ bill.periodStart }} ~ {{ bill.periodEnd }}</p>
      </header>

      <!-- 争议中（下架后仍可查看，知情权） -->
      <el-alert
        v-if="frozen"
        type="error"
        :closable="false"
        show-icon
        data-test="wa-bill-disputed-banner"
        :title="DISPUTED_BANNER"
      />

      <div v-if="bill" class="amount-cards">
        <div class="amount-card">
          <span class="amount-card__label">应收</span>
          <MoneyDisplay :value="bill.totalAmount" size="lg" />
        </div>
        <div class="amount-card">
          <span class="amount-card__label">已收</span>
          <MoneyDisplay :value="bill.paidAmount" size="lg" />
        </div>
      </div>

      <!-- 明细（按货品） -->
      <section class="card">
        <h3 class="card__title">明细</h3>
        <el-table :data="detail?.items ?? []" class="item-table" data-test="wa-bill-items">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ billItemTypeLabel(row.itemType) }}</template>
          </el-table-column>
          <el-table-column label="货品/说明" min-width="160">
            <template #default="{ row }">
              <span :class="{ 'is-reversed': row.reversed }">{{ itemLabel(row as BillItem) }}</span>
              <span v-if="row.reversed" class="reversed-tag">已冲销</span>
            </template>
          </el-table-column>
          <el-table-column label="计费段" width="120">
            <template #default="{ row }">{{ itemPeriod(row as BillItem) }}</template>
          </el-table-column>
          <el-table-column label="件·天" align="right" width="90">
            <template #default="{ row }">{{ row.qtyDays ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" align="right" width="110">
            <template #default="{ row }">
              <span :class="{ 'is-reversed': row.reversed }">
                <MoneyDisplay :value="row.amount" size="sm" />
              </span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无明细" :image-size="64" />
          </template>
        </el-table>
      </section>

      <!-- 回款记录（只读） -->
      <section v-if="(detail?.payments?.length ?? 0) > 0" class="card">
        <h3 class="card__title">回款记录</h3>
        <el-table :data="detail?.payments" class="item-table" data-test="wa-bill-payments">
          <el-table-column label="金额" align="right" width="120">
            <template #default="{ row }">
              <span :class="{ 'is-reversed': row.status === 'REVERSED' }">
                <MoneyDisplay :value="row.amount" size="sm" />
              </span>
            </template>
          </el-table-column>
          <el-table-column label="收款日期" width="130">
            <template #default="{ row }">{{ fmtDateTime(row.payAt).slice(0, 10) }}</template>
          </el-table-column>
          <el-table-column label="方式" width="100">
            <template #default="{ row }">{{ payMethodLabel(row.payMethod) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="90">
            <template #default="{ row }">
              {{ row.status === 'REVERSED' ? '已冲销' : '有效' }}
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 申诉记录（留痕双方可见） -->
      <section class="card">
        <h3 class="card__title">申诉记录</h3>
        <template v-if="(detail?.disputes?.length ?? 0) > 0">
          <div v-for="d in detail?.disputes" :key="String(d.id)" class="dispute-item" data-test="wa-dispute-item">
            <div class="dispute-item__head">
              <StatusBadge :variant="disputeStatusMeta(d.status).variant" :text="disputeStatusMeta(d.status).label" size="sm" />
              <span class="dispute-item__time">{{ fmtDateTime(d.createdAt) }}</span>
            </div>
            <p class="dispute-item__reason">申诉理由：{{ d.reason }}</p>
            <p v-if="d.resolution" class="dispute-item__resolution" data-test="wa-dispute-resolution">
              处理说明：{{ d.resolution }}
            </p>
          </div>
        </template>
        <el-empty v-else description="暂无申诉" :image-size="64" />
      </section>

      <!-- 操作区（争议中仅可查看） -->
      <footer v-if="bill" class="action-bar">
        <el-tooltip content="导出功能将随后续版本交付" placement="top">
          <span><el-button disabled>导出账单</el-button></span>
        </el-tooltip>
        <el-tooltip :disabled="canDispute" :content="disputeDisabledTip" placement="top">
          <span>
            <el-button
              v-if="!frozen"
              :disabled="!canDispute"
              data-test="wa-dispute-open"
              @click="openDispute"
            >
              发起申诉
            </el-button>
          </span>
        </el-tooltip>
        <el-button
          v-if="canConfirm && !frozen"
          type="primary"
          :loading="confirming"
          data-test="wa-bill-confirm"
          @click="onConfirm"
        >
          确认对账
        </el-button>
      </footer>
      <p v-if="canConfirm" class="hint">※ 下发满 1 天未确认将自动进入待回款</p>

      <!-- 申诉表单（线框 §8.9） -->
      <el-dialog
        v-model="disputeVisible"
        title="发起账单申诉"
        :width="isMobile ? '100%' : '480px'"
        :fullscreen="isMobile"
        append-to-body
        data-test="wa-dispute-dialog"
      >
        <p class="dispute-note">
          ※ 申诉受理期：账单下发后 7 天内<br />
          ※ 对出库货损/数量的投诉请走出库单客诉，此处仅受理账单金额与计费争议
        </p>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="争议条目（选填，可多选）">
            <el-checkbox-group v-model="disputeForm.itemIds" class="dispute-items" data-test="wa-dispute-items">
              <el-checkbox
                v-for="it in disputableItems"
                :key="String(it.id)"
                :value="String(it.id)"
                class="dispute-items__item"
              >
                {{ billItemTypeLabel(it.itemType) }}·{{ itemLabel(it) }}（¥{{ Number(it.amount).toFixed(2) }}）
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="申诉理由（必填）" required>
            <el-input
              v-model="disputeForm.reason"
              type="textarea"
              :rows="3"
              maxlength="300"
              show-word-limit
              placeholder="请说明争议的金额或计费问题"
              data-test="wa-dispute-reason"
            />
          </el-form-item>
          <el-form-item label="附图（选填，≤5 张）">
            <AttachmentUpload v-model="disputeForm.attachments" :max="5" />
          </el-form-item>
        </el-form>
        <p class="dispute-note">※ 申诉不影响账单确认与付款；结算员处理结果将通知您</p>
        <template #footer>
          <el-button @click="disputeVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="disputeSaving"
            data-test="wa-dispute-submit"
            @click="onSubmitDispute"
          >
            提交申诉
          </el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<style scoped>
.wa-detail-page {
  min-height: 100vh;
  background: var(--color-bg-2);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}
.detail-head__body {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-top: var(--space-2);
}
.detail-head__store {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.detail-head__period {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.mono {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
  word-break: break-all;
}

.amount-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-3);
}
.amount-card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-base);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.amount-card__label {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}

.card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-base);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0 0 var(--space-3);
}
.item-table {
  width: 100%;
}
.is-reversed {
  text-decoration: line-through;
  color: var(--color-fg-4);
}
.reversed-tag {
  margin-left: var(--space-1);
  color: var(--color-danger);
  font-size: 11px;
}

.dispute-item {
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border-1);
}
.dispute-item:last-child {
  border-bottom: none;
}
.dispute-item__head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.dispute-item__time {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.dispute-item__reason,
.dispute-item__resolution {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-2);
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.hint {
  margin: 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
  text-align: right;
}
.dispute-note {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
  line-height: 1.7;
}
.dispute-items {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-1);
}
.dispute-items__item {
  margin-right: 0;
  white-space: normal;
}

@media (max-width: 768px) {
  .action-bar :deep(.el-button) {
    min-height: 44px;
    flex: 1;
  }
}
</style>
