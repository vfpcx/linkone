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

// ============ 站内信（notifications） ============

/**
 * 站内信类型（Notification 实体常量；OUTBOUND_* / COMPLAINT_* 为 BE-W2 预留）
 */
export type NotificationType =
  | 'INBOUND_PENDING_CONFIRM'
  | 'INBOUND_AUTO_CONFIRMED'
  | 'DISPUTE_CREATED'
  | 'ARBITRATION_DECIDED'
  | 'OUTBOUND_WITHDRAW_REQUESTED'
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
