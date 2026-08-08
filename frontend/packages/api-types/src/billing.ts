/**
 * P4 计费结算 · billing 域 TS 类型（W4 前端契约）
 *
 * 权威来源（契约以 backend controller 实测为准，2026-08-07 对 main=befdf85 核对）：
 *  - backend/.../billing/controller/BillingRuleController.java   规则 2 端点（W1）
 *  - backend/.../billing/controller/StBillController.java        ST 账单全端点（W3）
 *  - backend/.../billing/controller/WholesalerBillController.java WA 账单 4 端点（W3）
 *  - backend/.../billing/vo/*.java / dto/*.java                  字段逐一对齐
 * 设计文档：shared/architecture/14-p4-design.md §2.2/§3.3/§6；产品 PRD shared/product/13-p4-prd.md
 *
 * 注意：金额/单价后端为 BigDecimal（JSON number），雪花 id 一律 string（http.ts 拦截器保证）。
 */

import type { SnowflakeId } from './common'

// ============================================================
// 计费规则（W1 · billing_rules 版本链）
// ============================================================

/** tenant_settings.billing_dim 只读镜像三值（W1 起新增 BOTH=并存） */
export type BillingDim = 'QTY' | 'PALLET' | 'BOTH'

/** 规则版本（BillingRuleVo）——effectiveTo 为 null 表示当前生效 */
export interface BillingRule {
  id: SnowflakeId
  qtyEnabled: boolean
  /** 元/件·天，≥0，最多 4 位小数；未启用维为 null */
  pricePerQtyDay: number | null
  palletEnabled: boolean
  /** 元/托盘·天 */
  pricePerPalletDay: number | null
  /** 生效起（yyyy-MM-dd） */
  effectiveFrom: string
  /** 生效止（yyyy-MM-dd）；null=至今 */
  effectiveTo: string | null
  /** 自 1 递增；同日多次变更覆写当日行 */
  version: number
}

/** GET /api/v1/tenant/billing-rules（TA/ST）；无规则空态 {current:null, history:[]} */
export interface BillingRules {
  current: BillingRule | null
  history: BillingRule[]
}

/**
 * POST /api/v1/tenant/billing-rules（TA · R20 变更事务）
 * 首存免 confirmed；真实变更缺 confirmed=true → 40003（前端二次确认后重发）。
 */
export interface BillingRuleSaveRequest {
  billingByQty: boolean
  pricePerQtyDay?: number
  billingByPallet: boolean
  pricePerPalletDay?: number
  confirmed?: boolean
}

// ============================================================
// 账单（W3 · 6 态生命周期）
// ============================================================

/**
 * 账单 6 态（DocKind.BILL 矩阵 · 14 §3.1）。
 * 界面唯一话术（PRD 13 §3.1）：待核对/已下发/待回款/部分回款/已结清/争议中。
 */
export type BillStatus =
  | 'DRAFT'
  | 'DISPATCHED'
  | 'PENDING_PAYMENT'
  | 'PARTIAL_PAID'
  | 'PAID'
  | 'DISPUTED'

/** 账单概要（BillVo） */
export interface Bill {
  id: SnowflakeId
  /** BL-{仓简码}-W{商户id}-{yyyyMM} */
  billNo: string
  wholesalerId: SnowflakeId
  wholesalerName: string
  /** yyyy-MM */
  billingMonth: string
  periodStart: string
  periodEnd: string
  /** 仓储费小计 */
  subtotalAmount: number
  /** 调整合计（负值为减免） */
  adjustAmount: number
  /** 应收 = subtotal + adjust */
  totalAmount: number
  /** 已收 */
  paidAmount: number
  /** 未收 = total - paid */
  outstandingAmount: number
  status: BillStatus | string
  dispatchAt: string | null
  confirmedAt: string | null
  createdAt: string
}

/** 明细行类型（PRD §2.3 中文对照：仓储费/调整/冲销/盘点影响） */
export type BillItemType = 'STORAGE' | 'ADJUSTMENT' | 'REVERSAL' | 'STOCKTAKE_IMPACT'

/** 账单明细行（BillItemVo；skuName 冗余带出，L-5 口径） */
export interface BillItem {
  id: SnowflakeId
  itemType: BillItemType | string
  skuId: SnowflakeId | null
  skuName: string | null
  /** STORAGE 规则段（R20 分段一段一行） */
  periodStart: string | null
  periodEnd: string | null
  qtyDays: number | null
  palletDays: number | null
  unitPriceQty: number | null
  unitPricePallet: number | null
  /** STOCKTAKE_IMPACT 恒 0；REVERSAL 为负 */
  amount: number
  description: string | null
  /** REVERSAL 回指原条目（R10 冲销链） */
  reverseOfItemId: SnowflakeId | null
  /** 该条目已被冲销（原条目划线展示） */
  reversed: boolean | null
}

/** 回款记录（PaymentRecordVo） */
export interface PaymentRecord {
  id: SnowflakeId
  billId: SnowflakeId
  amount: number
  payAt: string
  /** BANK_TRANSFER/CASH/WX/ALIPAY/OTHER */
  payMethod: string
  /** 转账凭证 ≤5 张 */
  evidences: string[] | null
  remark: string | null
  /** EFFECTIVE / REVERSED（冲销保留可见） */
  status: 'EFFECTIVE' | 'REVERSED' | string
  reverseReason: string | null
  reverseAt: string | null
  createdAt: string
}

/** 申诉状态（PENDING 待处理 / RESOLVED 成立 / REJECTED 不成立） */
export type BillDisputeStatus = 'PENDING' | 'RESOLVED' | 'REJECTED'

/** 账单申诉（BillDisputeVo） */
export interface BillDispute {
  id: SnowflakeId
  billId: SnowflakeId
  billNo: string
  wholesalerName: string
  reason: string
  disputedItemIds: string[] | null
  attachments: string[] | null
  status: BillDisputeStatus | string
  /** 处理说明（结论必填留痕） */
  resolution: string | null
  resolvedAt: string | null
  createdAt: string
}

/** 账单列表（BillListVo：分页 + 汇总卡三数，按当前筛选） */
export interface BillListResult {
  records: Bill[]
  total: number
  page: number
  size: number
  /** 应收合计 */
  receivable: number
  /** 已收合计 */
  received: number
  /** 未收合计 */
  outstanding: number
}

/** 账单详情（BillDetailVo） */
export interface BillDetail {
  bill: Bill
  items: BillItem[]
  payments: PaymentRecord[]
  disputes: BillDispute[]
}

/** 按日下钻行（DailyBreakdownRowVo：快照物理量 + 当段单价现算金额） */
export interface DailyBreakdownRow {
  date: string
  qty: number
  palletQty: number
  amount: number
}

/** 手动补生成结果（BillGenerateResultVo） */
export interface BillGenerateResult {
  month: string
  generated: number
  existing: number
  skipped: number
  billIds: string[]
}

// ============================================================
// 请求 DTO（ST 操作集 · 14 §3.3）
// ============================================================

/** 列表查询（GET /tenant/st/bills） */
export interface BillListQuery {
  /** yyyy-MM */
  month?: string
  wholesalerId?: SnowflakeId
  status?: BillStatus | string
  page?: number
  size?: number
}

/** 调整（US-ST-02：类型=折扣/减免，金额>0 入账为负，原因必填） */
export interface BillAdjustRequest {
  type: 'DISCOUNT' | 'RELIEF'
  amount: number
  remark: string
}

/** 行冲销（R10：仅 STORAGE/ADJUSTMENT 且未被冲销过） */
export interface BillReverseItemRequest {
  itemId: SnowflakeId
}

/** 回款登记（US-ST-04：不允许超收；凭证 ≤5） */
export interface PaymentRegisterRequest {
  amount: number
  /** ISO LocalDateTime，如 2026-08-05T00:00:00 */
  payAt: string
  payMethod: 'BANK_TRANSFER' | 'CASH' | 'WX' | 'ALIPAY' | 'OTHER'
  evidences?: string[]
  remark?: string
}

/** 回款冲销（R12：理由必填 + 二次确认凭据） */
export interface PaymentReverseRequest {
  reason: string
  confirmed: boolean
}

/** WA 申诉（US-WA-08：下发后 7 天窗；同账单至多一张待处理） */
export interface BillDisputeSubmitRequest {
  reason: string
  disputedItemIds?: string[]
  attachments?: string[]
}

/** ST 申诉处理（结论=成立 RESOLVED / 不成立 REJECTED，说明必填） */
export interface BillDisputeResolveRequest {
  conclusion: 'RESOLVED' | 'REJECTED'
  resolution: string
}
