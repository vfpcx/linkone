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

// ============ 退货单（return-requests · P3b T3-W1） ============

/**
 * 退货单状态（13 §2.1 状态机，无审批）：
 * PENDING_ACCEPT 待受理 → WITHDRAWN 已撤回（终态，仅待受理可撤）
 *                       | ACCEPTED 已受理（WK 锁单防撤回）→ COMPLETED 已退货（终态）
 * 库存时点 D-7：登记时扣——待受理/已受理期间货仍可售、库存零变化。
 */
export type ReturnStatus = 'PENDING_ACCEPT' | 'WITHDRAWN' | 'ACCEPTED' | 'COMPLETED'

/**
 * 退货单视图（ReturnRequestVo；WA 列表 / WK 受理队列共用）。
 * 权威来源：TenantReturnController / WholesalerReturnController（据实查证）。
 * 13 v1.2 备注 10：租户侧 status=PENDING_ACCEPT/ACCEPTED 时每行附
 * currentStock 与 suggestedPalletRelease（登记页免另拉库存接口）。
 */
export interface ReturnRequest {
  id: SnowflakeId
  /** 退货单号（RTN-） */
  docNo: string
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  /** 退货件数（提交落值；登记可按实覆写并留痕） */
  qty: number
  /** 实际释放托盘（登记后回写封顶后的生效值；登记前 0） */
  palletRelease: number | null
  status: ReturnStatus | string
  withdrawReason: string | null
  waUserId: SnowflakeId | null
  wkUserId: SnowflakeId | null
  acceptedAt: string | null
  completedAt: string | null
  remark: string | null
  createdAt: string
  /** 当前在库件数（受理/登记链路附带；其余 null） */
  currentStock: number | null
  /** 默认释放托盘建议值（13 §2.4-2 公式；WK 可覆盖含 0） */
  suggestedPalletRelease: number | null
}

/** WA 发起退货（POST /wholesaler/return-requests）：软校验在库，超量拒绝（50251） */
export interface ReturnCreateRequest {
  skuId: SnowflakeId
  qty: number
  /** 退货原因/备注（选填 ≤512） */
  remark?: string
}

/** WA 撤回退货（POST /wholesaler/return-requests/{id}/withdraw；仅待受理，理由必填） */
export interface ReturnWithdrawRequest {
  reason: string
}

/**
 * WK 退货登记（POST /tenant/return-requests/{id}/register · D-7 登记时扣）。
 * 在库不足 → 50251（单据保持已受理，联系商户改单）。
 */
export interface ReturnRegisterRequest {
  /** 实退件数（可空=按申请件数；覆写时后端自动留痕） */
  actualQty?: number
  /** 释放托盘覆盖值（可空=默认建议值；0 合法=托盘未腾空；落库前封顶） */
  palletRelease?: number
  /** 登记备注（选填 ≤512） */
  remark?: string
}

// ============ 盘点单（count-sheets · P3b T3-W2） ============

/**
 * 盘点单状态（13 §2.2 状态机）：
 * DRAFT 草稿（可编辑/删除）→ PENDING_APPROVAL 待审批 → APPROVED 已通过（终态不可逆）
 *                                              | REJECTED 已驳回 →（编辑重提回）DRAFT
 * 同商户在途（DRAFT/PENDING_APPROVAL）至多一张（50356）。
 */
export type CountSheetStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'REJECTED' | 'APPROVED'

/** TA 审批结论 */
export type CountSheetConclusion = 'APPROVED' | 'REJECTED'

/**
 * 盘点明细行视图（CountSheetItemVo）。
 * 13 v1.3 备注 3：palletDelta NULL=盘亏默认比例建议值、非空=WK 覆盖（含 0）；
 * 审批通过后回写生效带符号值（盘盈 +M / 盘亏 −实际释放）；
 * appliedDiff 同样带符号（盘盈 +diff / 盘亏 −applied / 无差异 0），驳回恒 null。
 */
export interface CountSheetItem {
  id: SnowflakeId
  skuId: SnowflakeId
  /** SKU 名称（详情链路填充，前端免二次拉取） */
  skuName: string | null
  /** 提交时刻账面快照（草稿期为预填当刻账面） */
  systemQty: number
  /** 实物数 ≥0 */
  actualQty: number
  /** 差异 = 实物 − 账面（正=盘盈、负=盘亏；不做在途还原折算） */
  diff: number
  /** 审批通过实际生效值（盘亏 D-10 封顶后带符号）；驳回/未决 null */
  appliedDiff: number | null
  /** 托盘覆盖值/生效值（null=按默认建议值） */
  palletDelta: number | null
  remark: string | null
  /** 当前在库（详情链路只读快照——审批弹窗封顶预览 min(|盘亏|, currentStock)） */
  currentStock: number | null
  /** 盘亏默认释放托盘建议值（盘盈/无差异行为 null） */
  suggestedPalletRelease: number | null
}

/**
 * 在途提示条聚合（StocktakeInTransitHintVo · 13 v1.3 备注 9）：
 * 出库在途=PENDING_ACCEPT/PRINTED、退货在途=ACCEPTED（待受理退货不计）。
 * skuId 字符串键防 JS 精度丢失。
 */
export interface StocktakeInTransitHint {
  outboundDocCount: number
  outboundQtyTotal: number
  returnDocCount: number
  returnQtyTotal: number
  skuOutboundQty: Record<string, number> | null
  skuReturnQty: Record<string, number> | null
}

/** 盘点单视图（CountSheetVo；列表精简 / 详情含 items + inTransitHint） */
export interface CountSheet {
  id: SnowflakeId
  /** 盘点单号（PD-） */
  docNo: string
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  /** 商户名（详情链路填充） */
  wholesalerName: string | null
  status: CountSheetStatus | string
  wkUserId: SnowflakeId | null
  taUserId: SnowflakeId | null
  decidedAt: string | null
  rejectRemark: string | null
  remark: string | null
  attachments: string[] | null
  createdAt: string
  updatedAt: string | null
  /** 明细（详情链路） */
  items: CountSheetItem[] | null
  /** 在途提示条（详情链路；已决单仍返回当刻值供追溯） */
  inTransitHint: StocktakeInTransitHint | null
}

/** 盘点明细行入参（CountSheetItemDto；items 全量替换语义） */
export interface CountSheetItemInput {
  skuId: SnowflakeId
  /** 实物数 ≥0 */
  actualQty: number
  /** 托盘覆盖值（省略=默认建议值；0 合法=托盘未腾空） */
  palletDelta?: number
  /** 差异理由 ≤512 */
  remark?: string
}

/** WK 建盘点草稿（POST /tenant/count-sheets；items ≥1 行 ≤200 行、同单 SKU 不重复 50355） */
export interface CountSheetCreateRequest {
  wholesalerId: SnowflakeId
  remark?: string
  /** 现场照片 URL ≤5 */
  attachments?: string[]
  items: CountSheetItemInput[]
}

/** WK 编辑草稿/驳回重改（PUT /tenant/count-sheets/{id}；items 全量替换） */
export interface CountSheetUpdateRequest {
  remark?: string
  attachments?: string[]
  items: CountSheetItemInput[]
}

/** TA 审批（POST /tenant/count-sheets/{id}/decide；REJECTED 时 remark 必填） */
export interface CountSheetDecideRequest {
  conclusion: CountSheetConclusion
  remark?: string
}

/**
 * WK 登记出库入参（P3b T3-W1 改造 · D-8=A 出库处托盘补齐）。
 * body 整体可选（旧调用不传行为兼容——按默认建议值释放）。
 */
export interface OutboundRegisterRequest {
  /** 释放托盘覆盖值（可空=默认建议值；0 合法；落库前对在库托盘封顶） */
  palletRelease?: number
}

// ============ 站内信（notifications） ============

/**
 * 站内信类型（Notification 实体常量；OUTBOUND_* / COMPLAINT_* 已随 BE-W2 落地；
 * INBOUND_*（正向链）/ CORRECTION_* 随 P3b T1、RETURN_* 随 T3-W1、STOCKTAKE_* 随 T3-W2 落地）
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
  | 'INQUIRY_VOIDED'
  | 'INBOUND_SUBMITTED'
  | 'INBOUND_ACCEPTED'
  | 'INBOUND_REJECTED'
  | 'INBOUND_REGISTERED'
  | 'CORRECTION_PENDING'
  | 'CORRECTION_DECIDED'
  | 'RETURN_CREATED'
  | 'RETURN_COMPLETED'
  | 'STOCKTAKE_PENDING'
  | 'STOCKTAKE_DECIDED'

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
