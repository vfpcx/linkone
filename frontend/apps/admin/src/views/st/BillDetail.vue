<script setup lang="ts">
/**
 * 账单详情（结算员/仓库老板兼岗 · P4 W4，线框 13-p4 §8.5-§8.7 + §8.11 移动标注）
 *
 * - 头部三卡恒等式：应收 = 仓储费小计 + 调整合计；未收 = 应收 − 已收（后端权威，前端展示）
 * - 明细两视角：按货品（含 R20 分段行/调整/冲销/盘点影响）/ 按日（每日快照下钻，当段单价现算）
 * - 操作集（14 §3.3，全部经状态机 CAS）：调整/行冲销（仅待核对）、下发、撤回（R11）、
 *   回款登记（凭证 ≤5）、回款冲销（R12 理由必填+二次确认）
 * - 争议中（R14）：顶部横幅 + 全部写操作隐藏，仅可查看（PRD §7.2）
 * - 响应式：<768px 明细表→卡片、操作区吸底、弹窗全屏化、凭证上传调起系统相机/相册
 */

import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { MoneyDisplay, StatusBadge } from '@cangchu/ui-shared'
import type { BillDetail, BillItem, DailyBreakdownRow, PaymentRecord } from '@cangchu/api-types'
import { stBillApi } from '@/api/billing'
import AttachmentUpload from '@/components/AttachmentUpload.vue'
import {
  billStatusMeta,
  billItemTypeLabel,
  payMethodLabel,
  disputeStatusMeta,
  fmtDateTime,
  DAILY_BILLING_HELP,
  DISPUTED_BANNER,
} from '@/utils/billing'
import StShell from './StShell.vue'

const route = useRoute()
const router = useRouter()
const billId = String(route.params.id ?? '')

// ============ 移动视口（弹窗全屏化 · §8.11-2/3） ============
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)
const onMq = (e: MediaQueryListEvent) => (isMobile.value = e.matches)
mq.addEventListener('change', onMq)
onBeforeUnmount(() => mq.removeEventListener('change', onMq))

// ============ 详情数据 ============
const loading = ref(false)
const detail = ref<BillDetail | null>(null)

const bill = computed(() => detail.value?.bill ?? null)
const frozen = computed(() => bill.value?.status === 'DISPUTED')
const isDraft = computed(() => bill.value?.status === 'DRAFT')
const canPay = computed(
  () => bill.value?.status === 'PENDING_PAYMENT' || bill.value?.status === 'PARTIAL_PAID',
)

const fetchDetail = async () => {
  loading.value = true
  try {
    detail.value = await stBillApi.detail(billId)
  } catch {
    // 50370「账单不存在」等：全局 toast 已提示
  } finally {
    loading.value = false
  }
}

// ============ 明细两视角 ============
const view = ref<'sku' | 'daily'>('sku')
const dailyRows = ref<DailyBreakdownRow[] | null>(null)
const dailyLoading = ref(false)

const onViewChange = async (v: string | number | boolean | undefined) => {
  if (v !== 'daily' || dailyRows.value !== null) return
  dailyLoading.value = true
  try {
    dailyRows.value = (await stBillApi.dailyBreakdown(billId)) ?? []
  } catch {
    dailyRows.value = []
  } finally {
    dailyLoading.value = false
  }
}

/** 明细行「货品/说明」列（同名货品统一名称+说明，L-5 口径） */
const itemLabel = (it: BillItem): string => {
  if (it.itemType === 'STORAGE') return it.skuName ?? '—'
  return it.description ?? it.skuName ?? '—'
}

/** 计费段展示（STORAGE 分段行） */
const itemPeriod = (it: BillItem): string =>
  it.periodStart && it.periodEnd ? `${it.periodStart.slice(5)}~${it.periodEnd.slice(5)}` : '—'

/** R10：仅仓储费/调整行、未被冲销、且账单待核对时可冲销 */
const canReverseItem = (it: BillItem): boolean =>
  isDraft.value && (it.itemType === 'STORAGE' || it.itemType === 'ADJUSTMENT') && !it.reversed

// ============ 调整（US-ST-02，仅待核对） ============
const adjustVisible = ref(false)
const adjustSaving = ref(false)
const adjustForm = reactive({ type: 'RELIEF' as 'DISCOUNT' | 'RELIEF', amount: undefined as number | undefined, remark: '' })

const openAdjust = () => {
  adjustForm.type = 'RELIEF'
  adjustForm.amount = undefined
  adjustForm.remark = ''
  adjustVisible.value = true
}

const onAdjust = async () => {
  if (!adjustForm.amount || adjustForm.amount <= 0) {
    ElMessage.warning('调整金额需大于 0')
    return
  }
  if (!adjustForm.remark.trim()) {
    ElMessage.warning('请填写调整原因')
    return
  }
  adjustSaving.value = true
  try {
    detail.value = await stBillApi.adjust(billId, {
      type: adjustForm.type,
      amount: adjustForm.amount,
      remark: adjustForm.remark.trim(),
    })
    adjustVisible.value = false
    ElMessage.success('调整已登记（入账为负值，应收已联动）')
  } catch {
    // 50371/40204「调整金额超出账单小计」等：全局 toast 已提示
  } finally {
    adjustSaving.value = false
  }
}

// ============ 行冲销（R10） ============
const reversingItemId = ref('')

const onReverseItem = async (it: BillItem) => {
  try {
    await ElMessageBox.confirm(
      `冲销后将新增一条金额为 −${Math.abs(Number(it.amount))} 的反向条目，原条目保留并标记已冲销（同一条目只能冲销一次）。确认冲销？`,
      '冲销条目',
      { confirmButtonText: '确认冲销', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  reversingItemId.value = String(it.id)
  try {
    detail.value = await stBillApi.reverseItem(billId, { itemId: it.id })
    ElMessage.success('条目已冲销')
  } catch {
    // 50383「该条目不可冲销或已被冲销」等：全局 toast 已提示
  } finally {
    reversingItemId.value = ''
  }
}

// ============ 下发 / 撤回（US-ST-03 / R11） ============
const dispatching = ref(false)
const withdrawing = ref(false)

const onDispatch = async () => {
  try {
    await ElMessageBox.confirm(
      '下发后批发商即可查看本账单并确认对账/发起申诉；下发后如需改账须先撤回。确认下发？',
      '下发给批发商',
      { confirmButtonText: '确认下发', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  dispatching.value = true
  try {
    await stBillApi.dispatch(billId)
    // 下发成功提示语向结算员透出自动确认规则（PRD §3.2 逐字）
    ElMessage.success('已下发给批发商。对方确认后进入待回款；满 1 天未确认将自动进入待回款。')
    await fetchDetail()
  } catch {
    /* 全局 toast 已提示 */
  } finally {
    dispatching.value = false
  }
}

const onWithdraw = async () => {
  try {
    await ElMessageBox.confirm(
      '撤回后账单回到待核对，批发商端原账单标记「已撤回」并收到通知。确认撤回？',
      '撤回下发',
      { confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  withdrawing.value = true
  try {
    await stBillApi.withdraw(billId)
    ElMessage.success('已撤回，账单回到待核对')
    await fetchDetail()
  } catch {
    // 50372「账单已有回款或已被确认，无法撤回」：全局 toast 已提示
  } finally {
    withdrawing.value = false
  }
}

// ============ 回款登记（US-ST-04 · 线框 §8.6） ============
const payVisible = ref(false)
const paySaving = ref(false)
const payForm = reactive({
  amount: undefined as number | undefined,
  payDate: '',
  payMethod: 'BANK_TRANSFER' as 'BANK_TRANSFER' | 'CASH' | 'WX' | 'ALIPAY' | 'OTHER',
  evidences: [] as string[],
  remark: '',
})

const todayStr = (): string => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const openPay = () => {
  if (!canPay.value) return
  payForm.amount = Number(bill.value?.outstandingAmount ?? 0) || undefined
  payForm.payDate = todayStr()
  payForm.payMethod = 'BANK_TRANSFER'
  payForm.evidences = []
  payForm.remark = ''
  payVisible.value = true
}

const onRegisterPayment = async () => {
  const outstanding = Number(bill.value?.outstandingAmount ?? 0)
  if (!payForm.amount || payForm.amount <= 0) {
    ElMessage.warning('本次收款金额需大于 0')
    return
  }
  if (payForm.amount > outstanding) {
    ElMessage.warning(`不可超过剩余应收 ¥${outstanding.toFixed(2)}`)
    return
  }
  if (!payForm.payDate) {
    ElMessage.warning('请选择收款日期')
    return
  }
  paySaving.value = true
  try {
    detail.value = await stBillApi.registerPayment(billId, {
      amount: payForm.amount,
      payAt: `${payForm.payDate}T00:00:00`,
      payMethod: payForm.payMethod,
      evidences: payForm.evidences.length ? payForm.evidences : undefined,
      remark: payForm.remark.trim() || undefined,
    })
    payVisible.value = false
    ElMessage.success(
      detail.value?.bill.status === 'PAID' ? '回款已登记，账单已结清' : '回款已登记（批发商将收到通知）',
    )
  } catch {
    // 50373 超收 / 50374 已结清：全局 toast 已提示
  } finally {
    paySaving.value = false
  }
}

// ============ 回款冲销（R12 · 线框 §8.7） ============
const reverseVisible = ref(false)
const reverseSaving = ref(false)
const reverseTarget = ref<PaymentRecord | null>(null)
const reverseReason = ref('')

const openReversePayment = (p: PaymentRecord) => {
  reverseTarget.value = p
  reverseReason.value = ''
  reverseVisible.value = true
}

const onReversePayment = async () => {
  if (!reverseTarget.value) return
  if (!reverseReason.value.trim()) {
    ElMessage.warning('请填写冲销理由')
    return
  }
  reverseSaving.value = true
  try {
    detail.value = await stBillApi.reversePayment(String(reverseTarget.value.id), {
      reason: reverseReason.value.trim(),
      confirmed: true,
    })
    reverseVisible.value = false
    ElMessage.success('回款记录已冲销，账单状态已回退（批发商将收到通知）')
  } catch {
    // 50375「该回款记录已冲销或不存在」：全局 toast 已提示
  } finally {
    reverseSaving.value = false
  }
}

onMounted(async () => {
  await fetchDetail()
  // 列表「登记回款」直达（?pay=1）
  if (route.query.pay === '1' && canPay.value) openPay()
})
</script>

<template>
  <StShell active="/st/bills">
    <div v-loading="loading" class="detail" data-test="bill-detail">
      <!-- 头部 -->
      <header class="detail-head">
        <div class="detail-head__main">
          <el-button text data-test="bill-back" @click="router.push('/st/bills')">← 返回</el-button>
          <span class="detail-head__no mono">{{ bill?.billNo ?? '…' }}</span>
          <span class="detail-head__ws">{{ bill?.wholesalerName }}</span>
          <StatusBadge
            v-if="bill"
            :variant="billStatusMeta(bill.status).variant"
            :text="billStatusMeta(bill.status).label"
            data-test="bill-status"
          />
        </div>
        <p v-if="bill" class="detail-head__period">
          账期：{{ bill.periodStart }} ~ {{ bill.periodEnd }}
        </p>
      </header>

      <!-- 争议中冻结横幅（PRD §7.2 逐字） -->
      <el-alert
        v-if="frozen"
        type="error"
        :closable="false"
        show-icon
        data-test="bill-disputed-banner"
        :title="DISPUTED_BANNER"
      />

      <!-- 三金额卡（恒等式实时联动） -->
      <div v-if="bill" class="amount-cards" data-test="bill-amount-cards">
        <div class="amount-card">
          <span class="amount-card__label">应收</span>
          <MoneyDisplay :value="bill.totalAmount" size="lg" />
        </div>
        <div class="amount-card">
          <span class="amount-card__label">已收</span>
          <MoneyDisplay :value="bill.paidAmount" size="lg" />
        </div>
        <div class="amount-card">
          <span class="amount-card__label">未收</span>
          <MoneyDisplay :value="bill.outstandingAmount" size="lg" />
        </div>
      </div>

      <!-- 明细（按货品 / 按日） -->
      <section class="card">
        <div class="card-head">
          <h3 class="card__title">明细</h3>
          <div class="card-head__right">
            <el-tooltip placement="top" :content="DAILY_BILLING_HELP" popper-class="billing-help-pop">
              <el-icon class="help-icon" data-test="daily-help"><QuestionFilled /></el-icon>
            </el-tooltip>
            <el-radio-group v-model="view" size="small" data-test="bill-view-switch" @change="onViewChange">
              <el-radio-button value="sku">按货品</el-radio-button>
              <el-radio-button value="daily">按日</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <!-- 按货品 -->
        <template v-if="view === 'sku'">
          <el-table :data="detail?.items ?? []" class="item-table" data-test="bill-items">
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ billItemTypeLabel(row.itemType) }}</template>
            </el-table-column>
            <el-table-column label="货品/说明" min-width="180">
              <template #default="{ row }">
                <span :class="{ 'is-reversed': row.reversed }">{{ itemLabel(row as BillItem) }}</span>
                <span v-if="row.reversed" class="reversed-tag">已冲销</span>
                <div v-if="row.itemType === 'STORAGE' && row.description" class="item-note">
                  ※ {{ row.description }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="计费段" width="120">
              <template #default="{ row }">
                <span :class="{ 'is-reversed': row.reversed }">{{ itemPeriod(row as BillItem) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="件·天" align="right" width="90">
              <template #default="{ row }">
                <span :class="{ 'is-reversed': row.reversed }">{{ row.qtyDays ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="托盘·天" align="right" width="90">
              <template #default="{ row }">
                <span :class="{ 'is-reversed': row.reversed }">{{ row.palletDays ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="金额" align="right" width="120">
              <template #default="{ row }">
                <span :class="{ 'is-reversed': row.reversed }">
                  <MoneyDisplay :value="row.amount" size="sm" :show-sign="row.itemType === 'ADJUSTMENT' && Number(row.amount) > 0" />
                </span>
              </template>
            </el-table-column>
            <el-table-column v-if="isDraft && !frozen" label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canReverseItem(row as BillItem)"
                  text
                  type="danger"
                  size="small"
                  :loading="reversingItemId === String(row.id)"
                  data-test="bill-item-reverse"
                  @click="onReverseItem(row as BillItem)"
                >
                  冲销
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无明细" :image-size="64" />
            </template>
          </el-table>
        </template>

        <!-- 按日下钻（每日快照 × 当段单价现算） -->
        <template v-else>
          <el-table v-loading="dailyLoading" :data="dailyRows ?? []" class="item-table" data-test="bill-daily">
            <el-table-column label="日期" prop="date" width="120" />
            <el-table-column label="当日计费件数" align="right" prop="qty" min-width="110" />
            <el-table-column label="当日占用托盘" align="right" prop="palletQty" min-width="110" />
            <el-table-column label="当日金额" align="right" min-width="110">
              <template #default="{ row }"><MoneyDisplay :value="row.amount" size="sm" /></template>
            </el-table-column>
            <template #empty>
              <el-empty description="账期内暂无每日快照" :image-size="64" />
            </template>
          </el-table>
          <p class="hint">※ {{ DAILY_BILLING_HELP }}</p>
        </template>
      </section>

      <!-- 回款记录 -->
      <section class="card">
        <div class="card-head">
          <h3 class="card__title">回款记录</h3>
          <el-tooltip
            :disabled="canPay"
            :content="isDraft ? '待核对账单不可登记回款，请先下发' : frozen ? '账单争议中，操作受限' : '当前状态不可登记回款'"
            placement="top"
          >
            <span>
              <el-button
                v-if="!frozen"
                type="primary"
                size="small"
                :disabled="!canPay"
                data-test="bill-pay-open"
                @click="openPay"
              >
                登记回款
              </el-button>
            </span>
          </el-tooltip>
        </div>
        <el-table v-if="(detail?.payments?.length ?? 0) > 0" :data="detail?.payments" class="item-table" data-test="bill-payments">
          <el-table-column label="金额" align="right" width="120">
            <template #default="{ row }">
              <span :class="{ 'is-reversed': row.status === 'REVERSED' }">
                <MoneyDisplay :value="row.amount" size="sm" />
              </span>
            </template>
          </el-table-column>
          <el-table-column label="收款日期" width="150">
            <template #default="{ row }">{{ fmtDateTime(row.payAt).slice(0, 10) }}</template>
          </el-table-column>
          <el-table-column label="方式" width="100">
            <template #default="{ row }">{{ payMethodLabel(row.payMethod) }}</template>
          </el-table-column>
          <el-table-column label="凭证" width="140">
            <template #default="{ row }">
              <template v-if="row.evidences?.length">
                <el-image
                  v-for="(u, i) in row.evidences"
                  :key="u"
                  :src="u"
                  :preview-src-list="row.evidences"
                  :initial-index="i"
                  preview-teleported
                  fit="cover"
                  class="evidence-thumb"
                />
              </template>
              <span v-else class="hint-inline">无</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <template v-if="row.status === 'REVERSED'">
                <StatusBadge variant="archived" text="已冲销" size="sm" />
                <div v-if="row.reverseReason" class="item-note">原因：{{ row.reverseReason }}</div>
              </template>
              <StatusBadge v-else variant="success" text="有效" size="sm" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="100">
            <template #default="{ row }">{{ row.remark ?? '—' }}</template>
          </el-table-column>
          <el-table-column v-if="!frozen" label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'EFFECTIVE'"
                text
                type="danger"
                size="small"
                data-test="bill-payment-reverse"
                @click="openReversePayment(row as PaymentRecord)"
              >
                冲销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无回款记录" :image-size="64" />
      </section>

      <!-- 申诉记录（留痕双方可见；处理入口在「申诉处理」页） -->
      <section class="card">
        <div class="card-head">
          <h3 class="card__title">申诉记录</h3>
          <el-button
            v-if="(detail?.disputes?.length ?? 0) > 0"
            text
            type="primary"
            size="small"
            @click="router.push('/st/disputes')"
          >
            前往申诉处理 →
          </el-button>
        </div>
        <template v-if="(detail?.disputes?.length ?? 0) > 0">
          <div v-for="d in detail?.disputes" :key="String(d.id)" class="dispute-item" data-test="bill-dispute-item">
            <div class="dispute-item__head">
              <StatusBadge :variant="disputeStatusMeta(d.status).variant" :text="disputeStatusMeta(d.status).label" size="sm" />
              <span class="dispute-item__time">{{ fmtDateTime(d.createdAt) }}</span>
            </div>
            <p class="dispute-item__reason">申诉理由：{{ d.reason }}</p>
            <p v-if="d.resolution" class="dispute-item__resolution">处理说明：{{ d.resolution }}</p>
          </div>
        </template>
        <el-empty v-else description="暂无申诉" :image-size="64" />
      </section>

      <!-- 操作区（移动吸底 · §8.11-2） -->
      <footer v-if="bill && !frozen" class="action-bar" data-test="bill-actions">
        <el-tooltip content="导出功能将随后续版本交付" placement="top">
          <span><el-button disabled>导出 PDF</el-button></span>
        </el-tooltip>
        <el-tooltip content="导出功能将随后续版本交付" placement="top">
          <span><el-button disabled>导出 Excel</el-button></span>
        </el-tooltip>
        <el-button
          v-if="isDraft"
          type="primary"
          size="small"
          data-test="bill-adjust-open"
          @click="openAdjust"
        >
          新增调整
        </el-button>
        <el-button
          v-if="isDraft"
          type="primary"
          :loading="dispatching"
          data-test="bill-dispatch"
          @click="onDispatch"
        >
          下发给批发商
        </el-button>
        <el-button
          v-if="bill.status === 'DISPATCHED'"
          type="warning"
          :loading="withdrawing"
          data-test="bill-withdraw"
          @click="onWithdraw"
        >
          撤回下发
        </el-button>
        <el-button
          v-if="canPay"
          type="primary"
          data-test="bill-pay-open-footer"
          @click="openPay"
        >
          登记回款
        </el-button>
      </footer>
    </div>

    <!-- 调整弹窗（US-ST-02） -->
    <el-dialog
      v-model="adjustVisible"
      title="新增调整"
      :width="isMobile ? '100%' : '440px'"
      :fullscreen="isMobile"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="调整类型" required>
          <el-radio-group v-model="adjustForm.type" data-test="adjust-type">
            <el-radio value="DISCOUNT">折扣</el-radio>
            <el-radio value="RELIEF">减免</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调整金额（元，入账为负值）" required>
          <el-input-number
            v-model="adjustForm.amount"
            :min="0.01"
            :precision="2"
            :step="10"
            class="full-w"
            data-test="adjust-amount"
          />
        </el-form-item>
        <el-form-item label="调整原因（必填）" required>
          <el-input
            v-model="adjustForm.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="如：长期合作让利"
            data-test="adjust-remark"
          />
        </el-form-item>
        <p class="hint">※ 调整后应收不得为负；已下发账单须先撤回再调整</p>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" data-test="adjust-submit" @click="onAdjust">
          确认调整
        </el-button>
      </template>
    </el-dialog>

    <!-- 回款登记弹窗（US-ST-04 · 线框 §8.6；移动全屏 + 系统相机/相册） -->
    <el-dialog
      v-model="payVisible"
      :title="`登记回款 · ${bill?.billNo ?? ''}`"
      :width="isMobile ? '100%' : '480px'"
      :fullscreen="isMobile"
      append-to-body
      class="pay-dialog"
      data-test="pay-dialog"
    >
      <p class="pay-summary">
        批发商：{{ bill?.wholesalerName }}<br />
        应收 <MoneyDisplay :value="bill?.totalAmount" size="sm" /> / 已收
        <MoneyDisplay :value="bill?.paidAmount" size="sm" /> / 剩余
        <MoneyDisplay :value="bill?.outstandingAmount" size="sm" />
      </p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="本次收款金额（元）" required>
          <el-input-number
            v-model="payForm.amount"
            :min="0.01"
            :max="Number(bill?.outstandingAmount ?? 0) || undefined"
            :precision="2"
            :step="100"
            class="full-w"
            data-test="pay-amount"
          />
          <p class="hint">※ 不可超过剩余应收 ¥{{ Number(bill?.outstandingAmount ?? 0).toFixed(2) }}</p>
        </el-form-item>
        <el-form-item label="收款日期" required>
          <el-date-picker
            v-model="payForm.payDate"
            type="date"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
            class="full-w"
            data-test="pay-date"
          />
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-radio-group v-model="payForm.payMethod" data-test="pay-method">
            <el-radio value="BANK_TRANSFER">银行转账</el-radio>
            <el-radio value="CASH">现金</el-radio>
            <el-radio value="WX">微信</el-radio>
            <el-radio value="ALIPAY">支付宝</el-radio>
            <el-radio value="OTHER">其他</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="转账凭证（选填，≤5 张）">
          <AttachmentUpload v-model="payForm.evidences" :max="5" />
        </el-form-item>
        <el-form-item label="备注（选填）">
          <el-input
            v-model="payForm.remark"
            maxlength="200"
            placeholder="如：对公转账尾号 8801"
            data-test="pay-remark"
          />
        </el-form-item>
        <p class="hint">※ 收满剩余应收后账单自动转「已结清」</p>
      </el-form>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="paySaving" data-test="pay-submit" @click="onRegisterPayment">
          确认登记
        </el-button>
      </template>
    </el-dialog>

    <!-- 回款冲销弹窗（R12 · 线框 §8.7） -->
    <el-dialog
      v-model="reverseVisible"
      title="冲销回款记录"
      :width="isMobile ? '100%' : '440px'"
      :fullscreen="isMobile"
      append-to-body
      data-test="payment-reverse-dialog"
    >
      <p class="pay-summary">
        回款：<MoneyDisplay :value="reverseTarget?.amount" size="sm" /> ·
        {{ fmtDateTime(reverseTarget?.payAt).slice(0, 10) }} ·
        {{ payMethodLabel(reverseTarget?.payMethod) }}<br />
        账单：{{ bill?.billNo }}
      </p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="冲销理由（必填）" required>
          <el-input
            v-model="reverseReason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="如：误登记 / 客户退款"
            data-test="payment-reverse-reason"
          />
        </el-form-item>
      </el-form>
      <p class="hint">
        冲销后：<br />✓ 该回款记录标记「已冲销」并保留<br />✓ 账单已收金额回减，状态自动回退<br />✓ 批发商将收到通知
      </p>
      <template #footer>
        <el-button @click="reverseVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="reverseSaving"
          data-test="payment-reverse-submit"
          @click="onReversePayment"
        >
          确认冲销
        </el-button>
      </template>
    </el-dialog>
  </StShell>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.detail-head__main {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.detail-head__no {
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
}
.detail-head__ws {
  color: var(--color-fg-2);
}
.detail-head__period {
  margin: var(--space-2) 0 0;
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.mono {
  font-family: var(--font-family-mono);
}

.amount-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
}
.amount-card {
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-5);
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
  padding: var(--space-4) var(--space-5);
  box-shadow: var(--shadow-base);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
  gap: var(--space-2);
}
.card-head__right {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.card__title {
  font-size: var(--font-size-h2);
  font-weight: var(--font-weight-semibold);
  color: var(--color-fg-1);
  margin: 0;
}
.help-icon {
  color: var(--color-fg-3);
  cursor: help;
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
.item-note {
  color: var(--color-fg-3);
  font-size: var(--font-size-caption);
}
.evidence-thumb {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  margin-right: 4px;
  vertical-align: middle;
}
.hint {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-fg-4);
  line-height: 1.7;
}
.hint-inline {
  color: var(--color-fg-4);
  font-size: var(--font-size-caption);
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
  font-size: var(--font-size-body);
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--color-bg-1);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-base);
  flex-wrap: wrap;
}
.full-w {
  width: 100%;
}
.full-w :deep(.el-input-number),
.full-w :deep(.el-date-editor) {
  width: 100%;
}
.pay-summary {
  margin: 0 0 var(--space-3);
  color: var(--color-fg-2);
  line-height: 1.8;
}

/* ===== D-P4-9 移动降档（<768px）===== */
@media (max-width: 768px) {
  .amount-cards {
    grid-template-columns: repeat(3, 1fr);
    gap: var(--space-2);
  }
  .amount-card {
    padding: var(--space-2) var(--space-3);
  }
  .card {
    padding: var(--space-3);
  }
  /* 明细表在窄屏内部横向滚动由 el-table 自身处理；操作区吸底 */
  .action-bar {
    position: sticky;
    bottom: 0;
    z-index: 5;
  }
  .action-bar :deep(.el-button) {
    min-height: 44px; /* 触控热区 ≥44px */
    flex: 1;
  }
}
</style>
