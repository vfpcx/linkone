/**
 * P4 计费结算接口封装（W4 前端）
 *
 * 权威来源（契约=backend controller 实测，main=befdf85）：
 *  - BillingRuleController：GET/POST /tenant/billing-rules（TA 写 / ST·TA 读；
 *    真实变更缺 confirmed=true → 40003，前端二次确认后重发）
 *  - StBillController：/tenant/st/bills 全端点 + /tenant/st/payments/{id}/reverse
 *    + /tenant/st/bill-disputes（requireStOrTa，TA 兼岗放行；WE 42004 / WK·WA 42001）
 *  - WholesalerBillController：/wholesaler/bills 4 端点（仅批发商管理员；员工整域拒绝；
 *    仅已下发过的账单可见，待核对按不存在 50370 不泄漏）
 * 错误码 50323 + 50370-50383 已入 @cangchu/error-codes（全局拦截器 toast 中文文案）。
 */

import { downloadFile, request } from './http'
import type {
  Bill,
  BillAdjustRequest,
  BillDetail,
  BillDispute,
  BillDisputeResolveRequest,
  BillDisputeSubmitRequest,
  BillGenerateResult,
  BillListQuery,
  BillListResult,
  BillingRules,
  BillingRule,
  BillingRuleSaveRequest,
  BillReverseItemRequest,
  DailyBreakdownRow,
  PaymentRegisterRequest,
  PaymentReverseRequest,
} from '@cangchu/api-types'

// ============ 计费规则（W1） ============

export const billingRuleApi = {
  /** 当前规则 + 历史版本（TA/ST 只读复用）；无规则 {current:null, history:[]} */
  getRules: () =>
    request<BillingRules>({ method: 'GET', url: '/tenant/billing-rules' }),

  /** 保存规则（TA；R20 变更须 confirmed=true，否则 40003；首存免确认、当日生效、不补历史） */
  saveRule: (data: BillingRuleSaveRequest) =>
    request<BillingRule>({ method: 'POST', url: '/tenant/billing-rules', data }),
}

// ============ ST 账单（W3 · requireStOrTa，TA 兼岗并集） ============

export const stBillApi = {
  /** 列表 + 汇总卡（应收/已收/未收合计按当前筛选）；倒序 */
  list: (params: BillListQuery = {}) =>
    request<BillListResult>({ method: 'GET', url: '/tenant/st/bills', params }),

  /** 详情：三金额+状态+items 全量+payments+disputes（skuName 冗余带出） */
  detail: (id: string) =>
    request<BillDetail>({ method: 'GET', url: `/tenant/st/bills/${id}` }),

  /** 按日下钻（读每日快照聚合，当段单价现算；不暴露实时库存） */
  dailyBreakdown: (id: string) =>
    request<DailyBreakdownRow[]>({
      method: 'GET',
      url: `/tenant/st/bills/${id}/daily-breakdown`,
    }),

  /** 手动补生成指定月份账单（幂等；无规则 50380；仅已结束月份） */
  generate: (month: string) =>
    request<BillGenerateResult>({
      method: 'POST',
      url: '/tenant/st/bills/generate',
      data: { month },
    }),

  /** 调整（US-ST-02，仅待核对；类型=折扣/减免，入账为负值） */
  adjust: (id: string, data: BillAdjustRequest) =>
    request<BillDetail>({ method: 'POST', url: `/tenant/st/bills/${id}/adjust`, data }),

  /** 行冲销（R10，仅待核对；新增反向条目不删原条目） */
  reverseItem: (id: string, data: BillReverseItemRequest) =>
    request<BillDetail>({
      method: 'POST',
      url: `/tenant/st/bills/${id}/reverse-item`,
      data,
    }),

  /** 下发（US-ST-03；站内信真实送达批发商） */
  dispatch: (id: string) =>
    request<Bill>({ method: 'POST', url: `/tenant/st/bills/${id}/dispatch` }),

  /** 撤回（R11：0 回款且未确认，否则 50372） */
  withdraw: (id: string) =>
    request<Bill>({ method: 'POST', url: `/tenant/st/bills/${id}/withdraw` }),

  /** 回款登记（US-ST-04：不允许超收 50373；凭证 ≤5） */
  registerPayment: (id: string, data: PaymentRegisterRequest) =>
    request<BillDetail>({ method: 'POST', url: `/tenant/st/bills/${id}/payments`, data }),

  /** 回款冲销（R12：理由必填 + confirmed:true；状态按剩余已收额回退） */
  reversePayment: (paymentId: string, data: PaymentReverseRequest) =>
    request<BillDetail>({
      method: 'POST',
      url: `/tenant/st/payments/${paymentId}/reverse`,
      data,
    }),

  /** 申诉队列（待处理升序先到先处理） */
  listDisputes: (status?: string) =>
    request<BillDispute[]>({
      method: 'GET',
      url: '/tenant/st/bill-disputes',
      params: status ? { status } : undefined,
    }),

  /** 申诉处理（结论=成立/不成立 + 处理说明必填；非待处理 50376） */
  resolveDispute: (disputeId: string, data: BillDisputeResolveRequest) =>
    request<BillDispute>({
      method: 'POST',
      url: `/tenant/st/bill-disputes/${disputeId}/resolve`,
      data,
    }),

  // ============ 导出（W5 · D-P4-8=A 同步流式下载，不落存储） ============

  /**
   * 账单导出（US-ST-05）：format=pdf|excel；DRAFT 可导（PDF 带「未下发预览稿」水印）；
   * 明细超 5000 行自动降级按货品聚合；文件名 RFC 5987 中文（账单-{billNo}.pdf/.xlsx）。
   * 返回实际下载文件名；错误（50370 等）仍为 JSON R 体，由拦截器统一 toast。
   */
  exportBill: (id: string, format: 'pdf' | 'excel') =>
    downloadFile(`/tenant/st/bills/${id}/export`, { format }),

  /**
   * 对账单导出（BillingSnapshotController）：某商户某月按日明细 Excel
   * （对账单-{商户名}-{yyyy-MM}.xlsx）。
   */
  exportSnapshots: (month: string, wholesalerId: string) =>
    downloadFile('/tenant/st/snapshots/export', { month, wholesalerId }),
}

// ============ WA 账单（W3 · 仅批发商管理员，员工整域拒绝） ============

export const waBillApi = {
  /** 收到的账单（仅已下发过的可见；含汇总卡） */
  list: (params: { month?: string; status?: string } = {}) =>
    request<BillListResult>({ method: 'GET', url: '/wholesaler/bills', params }),

  detail: (id: string) =>
    request<BillDetail>({ method: 'GET', url: `/wholesaler/bills/${id}` }),

  /** 对账确认 → 待回款（下发满 1 天未确认自动进入） */
  confirm: (id: string) =>
    request<Bill>({ method: 'POST', url: `/wholesaler/bills/${id}/confirm` }),

  /** 发起申诉（下发后 7 天窗 50378；同账单至多一张待处理 50382；不冻结账单） */
  dispute: (id: string, data: BillDisputeSubmitRequest) =>
    request<BillDispute>({ method: 'POST', url: `/wholesaler/bills/${id}/dispute`, data }),
}
