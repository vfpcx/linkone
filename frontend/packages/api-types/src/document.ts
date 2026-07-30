/**
 * P3 单据异常链类型（FE-W1 · 入库异常链 + TA 仲裁 + 站内信 + 附件）
 *
 * 权威来源（backend controller/VO/DTO，据实查证）：
 *  - WholesalerInboundController：GET /wholesaler/inbound-requests（MpPage<InboundRequest>，
 *      status=PENDING_WA_CONFIRM 时按 72h 倒计时升序）；POST {id}/confirm；POST {id}/dispute
 *  - TenantArbitrationController：GET /tenant/arbitrations（bizType/status 过滤）；
 *      POST /tenant/arbitrations/{id}/decide
 *  - NotificationController：GET /notifications、GET /notifications/unread-count、
 *      POST /notifications/{id}/read
 *  - FileController：POST /files（multipart 单文件 ≤5MB jpg/png/webp）→ {url}
 * 错误码 50330-50342（error-codes 包已登记）。
 * ⚠️ LocalDateTime 字段无时区偏移，前端直接格式化不做时区转换（既有约定）。
 */

import type { SnowflakeId } from './common'

// ============ WA 入库异议 ============

/** WA 入库异议入参（InboundDisputeDto）：reason 必填 ≤512；attachments URL ≤5 */
export interface InboundDisputeRequest {
  reason: string
  attachments?: string[]
}

/** WA 异议结果出参（InboundDisputeResultVo）：冲销明细 + YY- 仲裁单引用 */
export interface InboundDisputeResult {
  inboundId: SnowflakeId
  /** 入库单号（WK-） */
  docNo: string
  /** 入库单新状态（DISPUTED） */
  status: string
  /** 登记件数 Q */
  registeredQty: number
  /** 实际冲销件数（按在库封顶） */
  reversedQty: number
  /** 已售差额（进入定责） */
  shortfallQty: number
  arbitrationId: SnowflakeId
  /** 仲裁单号（YY-） */
  arbitrationDocNo: string
}

/**
 * 异议前在库预览（InboundStockPreviewVo · M3，PRD 09 §6.2）：
 * GET /wholesaler/inbound-requests/{id}/stock-preview。
 * 轻量只读快照，实际冲销以后端锁内计算为准。
 */
export interface InboundStockPreview {
  /** 当前在库件数 */
  onhand: number
  /** 预计冲销量 = min(registeredQty, max(onhand, 0)) */
  expectedReversal: number
  /** 预计差额 = registeredQty − expectedReversal（进 TA 仲裁定责） */
  expectedShortfall: number
}

// ============ 仲裁单（arbitrations） ============

/** 仲裁子类型（09 PRD §1.1） */
export type ArbitrationBizType = 'INBOUND_DISPUTE' | 'OUTBOUND_COMPLAINT'

/** 仲裁状态：两态最小、不可逆 */
export type ArbitrationStatus = 'PENDING' | 'DECIDED'

/** 入库仲裁结论：通过·恢复流水 / 驳回·保留冲销（文案须写全防歧义，09 §6.3） */
export type InboundArbitrationConclusion = 'APPROVED' | 'REJECTED'

/** 差额责任方四选（仅 REJECTED ∧ shortfallQty>0 时必填，其余必空 · 50342） */
export type ArbitrationLiability = 'WK_LIABLE' | 'WA_LIABLE' | 'NEGOTIATED' | 'NO_LIABILITY'

/** 仲裁单视图对象（ArbitrationVo） */
export interface Arbitration {
  id: SnowflakeId
  /** YY-（入库异议）/ KS-（出库客诉） */
  docNo: string
  bizType: ArbitrationBizType
  refDocType: string
  refDocId: SnowflakeId
  /** 关联单据号（入库异议 → WK-） */
  refDocNo: string
  wholesalerId: SnowflakeId
  /** 涉事商户名（列表展示） */
  wholesalerName: string | null
  initiatorUserId: SnowflakeId
  initiatorRole: string
  reason: string
  /** 附件 URL 列表（落库 JSON 的解码视图） */
  attachments: string[] | null
  /** 异议时实际冲销件数（按在库封顶） */
  reversedQty: number | null
  /** 已售差额件数（异议时刻快照固化） */
  shortfallQty: number | null
  status: ArbitrationStatus
  conclusion: string | null
  liability: ArbitrationLiability | null
  conclusionRemark: string | null
  arbitratorUserId: SnowflakeId | null
  decidedAt: string | null
  createdAt: string
}

/** 仲裁裁决入参（ArbitrationDecideDto）：remark REJECTED 必填；liability 三态校验 */
export interface ArbitrationDecideRequest {
  conclusion: InboundArbitrationConclusion
  remark?: string
  liability?: ArbitrationLiability
}

// ============ 库存（inventories · 出库作业辅助展示） ============

/** 库存行视图（InventoryVo；GET /tenant/inventories?wholesalerId=&skuId=） */
export interface InventoryItem {
  id: SnowflakeId
  wholesalerId: SnowflakeId
  tenantId: SnowflakeId
  skuId: SnowflakeId
  qty: number
  palletQty: number | null
  updatedAt: string | null
}

// ============ 出库单（outbound-requests · P3 BE-W2） ============

/**
 * 出库单状态（OutboundRequestVo.status）：
 * PENDING_ACCEPT 待受理 / PRINTED 已打印 / COMPLETED 已出库 /
 * WITHDRAWN 已撤回（待受理直撤）/ CANCELLED 已取消（已打印撤回经 WK 确认）/
 * COMPLAINED 客诉处理中（裁决后回 COMPLETED）
 */
export type OutboundStatus =
  | 'PENDING_ACCEPT'
  | 'PRINTED'
  | 'COMPLETED'
  | 'WITHDRAWN'
  | 'CANCELLED'
  | 'COMPLAINED'

/** 出库单来源：询价确认自动 / 商户手动提交 / 仓库代建（「已确认（代建）」队列=WK_CREATED 过滤） */
export type OutboundSource = 'INQUIRY_AUTO' | 'WA_SUBMIT' | 'WK_CREATED'

/**
 * 出库单视图（OutboundRequestVo；WA 列表 / WK 作业列表共用）。
 * 权威来源：TenantOutboundController / WholesalerOutboundController（据实查证）。
 */
export interface OutboundRequest {
  id: SnowflakeId
  /** 出库单号（CK-） */
  docNo: string
  inquiryId: SnowflakeId | null
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  /** 商户名（列表展示，页内缓存填充） */
  wholesalerName: string | null
  skuId: SnowflakeId
  qty: number
  palletQty: number | null
  status: OutboundStatus
  source: OutboundSource
  /** 代建单登记 WK（source=WK_CREATED 时非空） */
  wkUserId: SnowflakeId | null
  /** 首打时间 */
  printedAt: string | null
  printCount: number | null
  /** 实际出库时间（30 天客诉窗口锚点） */
  completedAt: string | null
  /** 1=已申请撤回待 WK 二次确认（仅 PRINTED 态有意义） */
  withdrawRequested: number | null
  withdrawRequestedAt: string | null
  createdAt: string
}

/** WA 手动出库申请（OutboundSubmitDto）：提交即扣，不足 50251 整体回滚 */
export interface OutboundSubmitRequest {
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  qty: number
  /** 托盘数（可空，默认 0） */
  palletQty?: number
}

/**
 * WK 代建出库（WkOutboundCreateDto）：直达 COMPLETED。
 * confirmed 必须显式 true（前端显著二次确认弹窗的后端凭据）；
 * qty > 在库×50% 时 restatedQty 必填且须等于 qty，否则 50338。
 */
export interface WkOutboundCreateRequest {
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  qty: number
  palletQty?: number
  confirmed: boolean
  restatedQty?: number
}

/** 30 天客诉发起（OutboundComplainDto）：仅 source=WK_CREATED 且已出库；超窗 50339 */
export interface OutboundComplainRequest {
  reason: string
  attachments?: string[]
}

/**
 * 出库客诉结论四选（OPS decide；conclusion 即判责，取值域与差额定责枚举一致）。
 * 仅判责不动库存/账单（D43）；remark 必填（结论备注是线下赔偿唯一依据）。
 */
export type OutboundComplaintConclusion = ArbitrationLiability

/** OPS 客诉裁决入参（ArbitrationDecideDto · OUTBOUND_COMPLAINT 分支）：liability 必空（50342） */
export interface OpsArbitrationDecideRequest {
  conclusion: OutboundComplaintConclusion
  remark: string
}

// ============ 站内信（notifications） ============

/**
 * 站内信类型（Notification 实体常量；OUTBOUND_* / COMPLAINT_* 已随 BE-W2 落地）
 */
export type NotificationType =
  | 'INBOUND_PENDING_CONFIRM'
  | 'INBOUND_AUTO_CONFIRMED'
  | 'DISPUTE_CREATED'
  | 'ARBITRATION_DECIDED'
  | 'OUTBOUND_WITHDRAW_REQUESTED'
  | 'OUTBOUND_WITHDRAWN'
  | 'OUTBOUND_WITHDRAW_REJECTED'
  | 'OUTBOUND_PROXY_CREATED'
  | 'COMPLAINT_CREATED'

/** 站内信出参（NotificationVo） */
export interface NotificationItem {
  id: SnowflakeId
  type: NotificationType | string
  title: string
  content: string
  /** INBOUND / OUTBOUND / ARBITRATION */
  refType: string | null
  refId: SnowflakeId | null
  /** null=未读 */
  readAt: string | null
  createdAt: string
}

/** 未读数出参（GET /notifications/unread-count） */
export interface UnreadCountResponse {
  count: number
}

// ============ 附件上传（files） ============

/** 上传出参（POST /files）：url 形如 /files/xxx.png（GET 静态放行免登录） */
export interface FileUploadResponse {
  url: string
}
