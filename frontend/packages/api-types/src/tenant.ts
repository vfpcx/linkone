/**
 * 租户接口 TS 类型
 * 对齐 shared/architecture/04-api-spec.md §4.3
 */

import type { SnowflakeId, PageRequest, PageData } from './common'

// ============ 老板多仓（已上线：TenantController.createWarehouse / listMyWarehouses） ============
/**
 * 后端契约（权威：backend/.../tenant TenantController + WarehouseVo + TenantApplyDto）：
 *  - POST /api/v1/tenant/warehouses  已登录 TA 新建一个仓（TenantApplyDto，name+contactPhone 必填）
 *      → { tenantId, simpleCode, status }
 *  - GET  /api/v1/tenant/warehouses  当前账号名下所有仓（顶栏切换器用）→ WarehouseVo[]
 * 多仓切换：前端保存"当前仓 tenantId"，各请求带头 X-Tenant-Id；
 *   后端 TenantInterceptor 校验该用户确属该仓（不属→42101），TenantLine 按此隔离。
 */
export type WarehouseStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'

/** WarehouseVo：名下单个仓库概要（顶栏切换器 / 多仓列表） */
export interface Warehouse {
  tenantId: SnowflakeId
  name: string
  simpleCode: string
  status: WarehouseStatus
}

/** 新建仓库入参（TenantApplyDto 子集；name + contactPhone 必填） */
export interface CreateWarehouseRequest {
  name: string
  contactPhone: string
  addressText?: string
  legalName?: string
  licenseNo?: string
  licenseUrl?: string
  lng?: number
  lat?: number
}

/** 新建仓库返回（后端 Map<String,Object>：tenantId / simpleCode / status） */
export interface CreateWarehouseResponse {
  tenantId: SnowflakeId
  simpleCode: string
  status: WarehouseStatus
}

// ============ 店铺设置（5 开关） ============
export type CapacityVisibility = 'PRIVATE' | 'WA_ONLY' | 'PUBLIC'
export type CapacityPrecision = 'EXACT' | 'TIER'
export type PhotoMode = 'OFF' | 'OPTIONAL' | 'REQUIRED'

export interface TenantSettings {
  tenantId: SnowflakeId
  storeName: string
  tenantSimpleCode: string

  // 地址
  address: {
    text: string
    lng: number
    lat: number
    accuracySource: 'GPS' | 'MAP_CLICK' | 'GEOCODE' | 'IP'
  } | null

  // 5 开关
  batchEnabled: boolean
  photoMode: PhotoMode
  capacityVisibility: CapacityVisibility
  capacityPrecision: CapacityPrecision

  // 计费维度
  billingByQty: boolean
  billingByPallet: boolean
  pricePerQtyDay?: number
  pricePerPalletDay?: number
  expiryThresholdDays?: number       // 临期阈值（仅批次启用时有效）

  // 容量
  totalQty?: number
  totalPallet?: number
}

export interface UpdateTenantSettingsRequest extends Partial<TenantSettings> {
  /** 用于副作用确认（计费规则变更需要 confirmed=true） */
  confirmed?: boolean
}

// ============ 工作台聚合 ============
export interface DashboardKpi {
  pendingInbound: number       // 待审入驻申请
  pendingCount: number         // 待审盘点单
  pendingClearance: number     // 待审清库单
  pendingDispute: number       // 申诉/异议处理
}

export interface DashboardCapacity {
  usedQty: number
  totalQty: number
  usedPallet: number
  totalPallet: number
  utilization: number          // 0-100
  visibility: CapacityVisibility
  snapshotAt: string
}

export interface DashboardTodayStats {
  inboundCount: number
  outboundCount: number
  inquiryCount: number
  /** 临期 3 天内批次数（仅批次启用时） */
  expiringBatches: number
}

export interface TenantDashboardResponse {
  storeName: string
  kpi: DashboardKpi
  capacity: DashboardCapacity
  today: DashboardTodayStats
  /** 是否启用批次（决定临期入口可见性） */
  batchEnabled: boolean
}

// ============ 撮合页 ============
export interface StoreFront {
  storeId: SnowflakeId
  intro: string
  bannerUrls: string[]
  featuredSkuIds: SnowflakeId[]
  pinnedWholesalerIds: SnowflakeId[]
}

// ============ 员工 ============
export interface Employee {
  userId: SnowflakeId
  realName: string
  phone: string                    // 脱敏
  role: 'WK' | 'ST'
  status: 'ACTIVE' | 'DISABLED'
  joinedAt: string
}

export interface InviteEmployeeRequest {
  role: 'WK' | 'ST'
  realName?: string
  expireAt?: string
  usageLimit?: number
}

export interface InviteCode {
  code: string
  qrUrl: string
  role: 'WK' | 'ST' | 'WE'
  expireAt: string
  usageLimit: number
  usedCount: number
}

// ============ 员工注册码（phase-1，已上线） ============
/**
 * 后端契约（EmployeeInviteController，已上线）：
 *  - POST   /api/v1/tenant/employee-invites      生码（TA 登录态）
 *  - GET    /api/v1/tenant/employee-invites      列表（倒序）
 *  - DELETE /api/v1/tenant/employee-invites/{id} 作废
 * VO：{id,tenantId,code,role,maxUses,usedCount,remaining,expireAt,status}
 */
export type EmployeeInviteRole = 'WK' | 'ST'
export type EmployeeInviteStatus = 'ACTIVE' | 'EXHAUSTED' | 'REVOKED'

export interface EmployeeInvite {
  id: SnowflakeId
  tenantId: SnowflakeId
  /** 注册码（员工凭码注册时填入） */
  code: string
  role: EmployeeInviteRole
  /** 最大可用次数 */
  maxUses: number
  /** 已使用次数 */
  usedCount: number
  /** 剩余可用次数 = maxUses - usedCount */
  remaining: number
  /** 过期时间 ISO */
  expireAt: string
  status: EmployeeInviteStatus
}

export interface CreateEmployeeInviteRequest {
  role: EmployeeInviteRole
  /** 最大可用次数，默认 1 */
  maxUses?: number
  /** 有效天数，默认 7 */
  expiresInDays?: number
}

// ============ WA 入驻审批（P2 入驻生态 Wave1 · 后端已落地） ============
/**
 * 权威来源：shared/architecture/10-onboarding-design.md §1.1/§2（据实现编写）：
 *  - POST /api/v1/wholesaler/applications   WA 提交入驻申请
 *    body: {targetTenantId, name, contactName?, contactPhone?, license?} → {applicationId, status}
 *  - GET  /api/v1/tenant/wholesaler-applications?status=&page=&size=   TA 分页列表
 *    → {records, total, page, size}
 *  - POST /api/v1/tenant/wholesaler-applications/{id}/audit  TA 审批（action: APPROVED|REJECTED，
 *    驳回 remark 必填）→ {applicationId, status, wholesalerId?}
 *  - GET  /api/v1/wholesaler/applications   WA 本人入驻申请列表（字段同 TA 列表）
 * 错误码：50201 审核中（重复提交）/ 50203 申请不存在或状态不可审 / 50204 重复入驻 / 50205 黑名单拦截。
 */
export type WaApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 入驻申请（对齐 wholesaler_applications 表 / 后端 VO） */
export interface WholesalerApplication {
  id: SnowflakeId
  /** 目标仓库（tenant）ID */
  tenantId: SnowflakeId
  /** 申请人用户 ID */
  applicantUserId: SnowflakeId
  /** 商户名称 */
  name: string
  contactName?: string
  contactPhone?: string
  /** 营业执照号/凭证（黑名单键） */
  license?: string
  status: WaApplicationStatus
  /** 来源：SELF_APPLY / OPS_CREATED / TA_SELF_OPERATED */
  source?: string
  /** OPS 代建授权依据 */
  authBasis?: string
  auditUserId?: SnowflakeId
  /** 审核时间 */
  auditedAt?: string
  /** 审核备注；REJECTED 时为驳回理由 */
  auditRemark?: string
  /** 通过后回填的商户 ID */
  wholesalerId?: SnowflakeId
  createdAt: string
}

/** WA 提交入驻申请入参（POST /wholesaler/applications） */
export interface SubmitWaApplicationRequest {
  /** 目标仓库 tenantId（雪花字符串） */
  targetTenantId: SnowflakeId
  /** 商户名 */
  name: string
  /** 联系人 */
  contactName?: string
  /** 联系电话 */
  contactPhone?: string
  /** 营业执照号（可选） */
  license?: string
}

/** WA 提交入驻申请返回 */
export interface SubmitWaApplicationResponse {
  applicationId: SnowflakeId
  status: WaApplicationStatus
}

/** TA 审批入参（POST /tenant/wholesaler-applications/{id}/audit；REJECTED 时 remark 必填） */
export interface AuditWaApplicationRequest {
  action: 'APPROVED' | 'REJECTED'
  remark?: string
}

/** TA 审批返回 */
export interface WaApplicationAuditResult {
  applicationId: SnowflakeId
  status: WaApplicationStatus
  /** APPROVED 时为新建/回填的商户 ID */
  wholesalerId?: SnowflakeId
}

/** TA 分页列表查询参数 */
export interface WaApplicationListQuery {
  status?: WaApplicationStatus
  page?: number
  size?: number
}

/** @deprecated 后端统一走 audit 端点，保留兼容旧封装 */
export interface ApproveWaRequest {
  remark?: string
}
/** @deprecated 后端统一走 audit 端点，保留兼容旧封装 */
export interface RejectWaRequest {
  reason: string
}

// ============ 自营批发商 ============
export interface CreateSelfOperatedWaRequest {
  wholesalerName: string
  contactPhone: string
  contactName?: string
}

// ============ 强制下架 WA（R14 · Wave2 契约） ============
/**
 * POST /api/v1/tenant/wholesalers/{id}/force-offline
 * TA 单方即时生效，不走审批；不可原地恢复（已下架→正常不可达）。
 */
export interface ForceOfflineWaRequest {
  /** 下架原因（5~200 字，留痕并通知商户） */
  reason: string
}

// ============ 审批中心 ============
export type ApprovalDocType =
  | 'INBOUND'
  | 'COUNT_SHEET'
  | 'EXPIRY_CLEARANCE'
  | 'WITHDRAW'
  | 'INBOUND_DISPUTE'
  | 'WA_APPLICATION'

export interface ApprovalCenterItem {
  docId: SnowflakeId
  docType: ApprovalDocType
  docNo: string
  submitterName: string
  submittedAt: string
  summary: string
}

export interface ApprovalCenterResponse extends PageData<ApprovalCenterItem> {
  countByType: Record<ApprovalDocType, number>
}

// ============ 账单总览 ============
export interface BillsOverviewQuery extends PageRequest {
  yearMonth?: string           // 2026-06
}

export interface BillsOverviewItem {
  wholesalerId: SnowflakeId
  wholesalerName: string
  totalAmount: number
  paidAmount: number
  unpaidAmount: number
  status: 'PENDING' | 'PARTIAL' | 'PAID'
}

export interface BillsOverviewResponse {
  yearMonth: string
  totalReceivable: number
  totalPaid: number
  totalUnpaid: number
  list: BillsOverviewItem[]
}

// ============ 仲裁 ============
export interface ArbitrateInboundRequest {
  decision: 'ACCEPT_WK' | 'ACCEPT_WA' | 'COMPROMISE'
  qty?: number
  remark: string
}

// ============ WK 入库登记（phase-1 C1，已上线） ============
/**
 * 后端契约（InboundController + InboundRegisterDto + InboundRequestVo，已上线）：
 *  - POST /api/v1/tenant/inbound   WK 登记入库（单事务：建单 + 增库存）
 *  - GET  /api/v1/tenant/inbound?wholesalerId=  列出本租户入库单（wholesalerId 可选过滤）
 * 错误码 50270-50274（qty 非法 / 缺商户 / sku 不属商户 等）。
 * tenantId 不由客户端传入——后端由 wholesaler 真实归属推导（G-2.1）。
 * ⚠️ createdAt 后端为 LocalDateTime（无时区偏移），见契约出入说明。
 */
export interface InboundRegisterRequest {
  /** 批发商商户 id（必填） */
  wholesalerId: SnowflakeId
  /** 商品 SKU id（必填） */
  skuId: SnowflakeId
  /** 入库数量（>0） */
  qty: number
  /** 本次托盘数（可空，默认 0；>=0） */
  palletQty?: number
  // ==================== P3b T4-W1 批次三字段（租户批次开关启用时必填，13 §3.2；代建=提交即登记按当刻开关校验） ====================
  /** 批次号 ≤64（(商户,SKU,批次号) 唯一 50362） */
  batchNo?: string
  /** 生产日期 yyyy-MM-dd（≤今天 40205） */
  productionDate?: string
  /** 到效期 yyyy-MM-dd（>生产日期 40206；≤今天须 expiredConfirmed=true，否则 50364） */
  expiryDate?: string
  /** 过期批次强警告二次确认凭据（50364 回显后重发 true） */
  expiredConfirmed?: boolean
}

/**
 * 入库单状态机（P3 BE-W1 · 12-p3-design.md §2.1；P3b T1 · 13-p3b-design.md §1.1 扩正向链 4 值）：
 *  - 代建链：PENDING_WA_CONFIRM → CONFIRMED | DISPUTED → CONFIRMED | REVOKED
 *  - 正向链：SUBMITTED → WITHDRAWN | REJECTED | ACCEPTED → CONFIRMED（登记才加库存）
 * CONFIRMED 双链共用，正向链界面文案统一「已入库」（D-3，靠 source 区分链路）。
 */
export type InboundStatus =
  | 'PENDING_WA_CONFIRM'
  | 'CONFIRMED'
  | 'DISPUTED'
  | 'REVOKED'
  | 'SUBMITTED'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'WITHDRAWN'

/** 入库单来源（P3 BE-W1）：WK 现场代建 / WA 正向申请 */
export type InboundSource = 'WK_CREATED' | 'WA_SUBMIT'

/** 入库单视图对象（InboundRequestVo · P3 BE-W1 增确认链字段） */
export interface InboundRequest {
  id: SnowflakeId
  docNo: string
  wholesalerId: SnowflakeId
  tenantId: SnowflakeId
  skuId: SnowflakeId
  qty: number
  palletQty: number | null
  status: string
  wkUserId: SnowflakeId
  /** 登记后该 sku 最新库存（便于前端回显；列表端点为 null） */
  currentStock: number | null
  // ==================== P3 BE-W1 确认链字段 ====================
  /** 来源：WK_CREATED / WA_SUBMIT */
  source: InboundSource | null
  /** 72h 确认截止（WA 队列按此升序倒计时）· LocalDateTime 无时区偏移 */
  waConfirmDeadline: string | null
  waConfirmAt: string | null
  /** 1=72h 超时自动确认 */
  autoAccepted: number | null
  disputedAt: string | null
  createdAt: string
  // ==================== P3b T1 正向申请链字段（InboundRequestVo 扩展） ====================
  /** 申请件数（正向链；qty=实登件数，登记前二者相等；代建链 null） */
  requestedQty?: number | null
  /** R2 驳回原因单选：QTY/QUALITY/BATCH/OTHER */
  rejectReason?: string | null
  rejectRemark?: string | null
  /** R2 驳回举证附件 URL 列表 */
  rejectAttachments?: string[] | null
  /** R1 撤回理由 */
  withdrawReason?: string | null
  /** 登记照片 URL 列表 */
  attachments?: string[] | null
  printedAt?: string | null
  printCount?: number | null
  /** 登记时刻（R3 24h 窗口锚点） */
  registeredAt?: string | null
  /** 同批提交共享 id（多行拆单打印聚合，「同批 N 单」标识） */
  batchSubmitId?: SnowflakeId | null
  // ==================== P3b T4-W1 批次三字段（InboundRequestVo 扩展；开关关闭档恒 null） ====================
  /** 批次号（提交/代建时录入；登记页回显 + 过期判定锚点） */
  batchNo?: string | null
  productionDate?: string | null
  expiryDate?: string | null
  /** 提交人（WA 或被授权 WE） */
  waUserId?: SnowflakeId | null
  /** 备注（提交行备注 / 登记差异备注） */
  remark?: string | null
}

// ============ P3b T1 正向申请链（13-p3b-design.md §5.1，契约=后端 controller 实测） ============

/** R2 驳回原因单选枚举（InboundRejectDto.reason） */
export type InboundRejectReason = 'QTY' | 'QUALITY' | 'BATCH' | 'OTHER'

/** WA/WE 提交入库申请（POST /wholesaler/inbound-requests；多行拆 N 单共享 batchSubmitId） */
export interface InboundSubmitRequest {
  wholesalerId: SnowflakeId
  /** ≥1 行、≤50 行（40001）；每行拆一张一单一 SKU 的申请单 */
  items: Array<{
    skuId: SnowflakeId
    /** 申请件数 >0（落 requestedQty，登记前不可变） */
    qty: number
    /** 预计托盘数（可空默认 0；登记时库管员可覆写） */
    palletQty?: number
    /** 行备注 ≤512 */
    remark?: string
    // ==================== P3b T4-W1 批次三字段（商户批次开关启用时必填，13 §3.2；
    // 同批提交内 (skuId,batchNo) 重复同样 50362；过期二次确认在 WK 登记侧 50364） ====================
    /** 批次号 ≤64（(商户,SKU,批次号) 唯一 50362） */
    batchNo?: string
    /** 生产日期 yyyy-MM-dd（≤今天 40205） */
    productionDate?: string
    /** 到效期 yyyy-MM-dd（>生产日期 40206） */
    expiryDate?: string
  }>
}

/** R1 撤回入库申请（POST /wholesaler/inbound-requests/{id}/withdraw；仅 SUBMITTED，50350） */
export interface InboundWithdrawRequest {
  reason: string
}

/** R2 驳回入库申请（POST /tenant/inbound/{id}/reject） */
export interface InboundRejectRequest {
  reason: InboundRejectReason
  /** 备注必填 ≤512 */
  remark: string
  /** 举证附件 ≤5 */
  attachments?: string[]
}

/** WK 登记正向链入库（POST /tenant/inbound/{id}/register；5% 边界 50351，此刻才加库存） */
export interface InboundForwardRegisterRequest {
  /** 实登件数 >0 */
  actualQty: number
  /** 实际托盘数（可空 → 沿用提交值） */
  palletQty?: number
  /** 差异备注（实登 ≠ 申请件数时必填） */
  remark?: string
  /** 登记照片 ≤5 */
  attachments?: string[]
  /**
   * P3b T4-W1：过期批次强警告二次确认（13 备注 8：登记时按单据自身 expiryDate 判，
   * 到效期 ≤ 今天且缺此凭据 → 50364；前端弹强警告确认后重发 true）
   */
  expiredConfirmed?: boolean
}

/** R3 纠错单状态 */
export type InboundCorrectionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** R3 登记纠错单（InboundCorrectionVo） */
export interface InboundCorrection {
  id: SnowflakeId
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  inboundRequestId: SnowflakeId
  /** 冗余入库单号（列表展示免 join） */
  refDocNo: string
  oldQty: number
  newQty: number
  reason: string
  status: InboundCorrectionStatus | string
  /** APPROVED 实际生效变动量（改小封顶后） */
  appliedQty: number | null
  /** 改小遇已售差额（线下定责） */
  shortfallQty: number | null
  remark: string | null
  decideRemark: string | null
  wkUserId: SnowflakeId | null
  taUserId: SnowflakeId | null
  decidedAt: string | null
  createdAt: string
}

/** R3 发起纠错（POST /tenant/inbound/{id}/corrections；24h/防重/合法性 50352-50354） */
export interface InboundCorrectionCreateRequest {
  /** 纠错后件数 ≥0（=当前实登值 / 为负 → 50354） */
  newQty: number
  reason: string
}

/** TA 审批纠错（POST /tenant/inbound/corrections/{id}/decide；REJECTED 时 remark 必填） */
export interface InboundCorrectionDecideRequest {
  conclusion: 'APPROVED' | 'REJECTED'
  remark?: string
}

// ============ WA 询价确认（phase-1 C2） ============
/**
 * 后端契约（权威：backend/.../document InquiryController + InquiryServiceImpl + InquiryVo）：
 *  - GET  /api/v1/tenant/inquiry               WA 列出本人归属 wholesaler 的询价单（登录态 WA）
 *  - POST /api/v1/tenant/inquiry/{id}/confirm  WA 确认 → 状态机 PENDING→CONFIRMED→COMPLETED，
 *          单事务内为每条明细建出库单 + 扣库存；库存不足整体回滚（仍 PENDING）。
 *  - POST /api/v1/rt/inquiry                   RT 提交询价（公开，联调造数据用）
 * 错误码 50280-50287（items 空 / qty 非法 / 批发商不属店 / sku 不属批发商 / 单不存在 /
 *   状态不允许 / 非 WA 越权 / 出库单生成失败）；库存不足为 50251(STOCK_NOT_ENOUGH)。
 * tenantId/wholesalerId 归属均由后端登录态/店铺解析推导，前端不传（G-2.1）。
 * ⚠️ createdAt/confirmedAt 后端为 LocalDateTime（无时区偏移），见契约出入说明。
 */

/** 询价单状态机 */
export type InquiryStatus = 'PENDING' | 'CONFIRMED' | 'COMPLETED'

/** 询价单明细（InquiryVo.InquiryItemVo，含价格快照） */
export interface InquiryItem {
  id: SnowflakeId
  skuId: SnowflakeId
  qty: number
  /** 下单时单价快照（BigDecimal→number/string，前端按 number 处理） */
  unitPriceSnapshot: number
  /** 起批价快照 */
  moqPriceSnapshot: number
  /** 起批量快照 */
  moqQtySnapshot: number
  /** 成交价（phase-1 = 单价快照） */
  dealPrice: number
}

/** 询价单视图对象（InquiryVo） */
export interface Inquiry {
  id: SnowflakeId
  docNo: string
  storeId: SnowflakeId
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  /** PENDING / CONFIRMED / COMPLETED */
  status: InquiryStatus
  /** 买家（RT）手机号 */
  rtPhone: string
  createdAt: string
  confirmedAt: string | null
  items: InquiryItem[]
}

/**
 * 确认询价明细项（ConfirmInquiryDto.items[]，P2 议价沉淀）。
 * inquiryItemId = InquiryItem.id（后端下发的明细行雪花 id）。
 */
export interface ConfirmInquiryItem {
  inquiryItemId: SnowflakeId
  /** 本行成交价（TA/WA 议价后的实际单价，两位小数） */
  dealPrice: number
}

/**
 * WA 确认询价入参（ConfirmInquiryDto，P2）。
 * 全部字段可选——整体省略 body 时保持旧行为（成交价=公开价快照，不沉淀专属价）。
 *  - items：逐行改写成交价；省略的行按公开价快照成交。
 *  - settleAsCustomerPrice：勾选后，对成交价≠公开价的行沉淀为该买家的客户专属价。
 */
export interface ConfirmInquiryRequest {
  items?: ConfirmInquiryItem[]
  settleAsCustomerPrice?: boolean
}

/**
 * RT 提交询价入参（SubmitInquiryDto，公开端点，联调造数据用）。
 * storeId 与 code 二选一（后端解析 store→tenant，不取客户端 tenantId）。
 */
export interface SubmitInquiryRequest {
  /** 店铺 id（与 code 二选一） */
  storeId?: SnowflakeId
  /** 店铺码（与 storeId 二选一） */
  code?: string
  /** 批发商商户 id（必填，须属该店） */
  wholesalerId: SnowflakeId
  /** 买家手机号（必填） */
  rtPhone: string
  /** 询价明细（非空；每项 skuId 必填、qty>0） */
  items: Array<{ skuId: SnowflakeId; qty: number }>
}

// ============================================================
// 公开租户目录（DEF-1 · 10-onboarding-design.md §32，Wave6）
// ============================================================
/**
 * `GET /api/v1/tenants/directory?keyword=&limit=`（注意前缀是 tenants 复数）
 *  - 匿名可访问（SaTokenConfig 显式公开）；WA 注册页「选择想入驻的仓库」数据源。
 *  - 防枚举：仅 status=ACTIVE 租户可见；DTO 恰好 id/name 两字段，无任何敏感信息。
 *  - IP 限流：同 IP 30 次/分钟，超限 43001「操作过于频繁，请稍后再试」。
 */

/** 目录查询参数（均可选）：keyword 按仓库名模糊匹配；limit 默认 10、上限 20（后端强制钳制） */
export interface TenantDirectoryQuery {
  keyword?: string
  limit?: number
}

/** 目录条目（恰好两个字段） */
export interface TenantDirectoryItem {
  /** 租户（仓库）id · 字符串化 Long——WA 注册时原样作为 targetTenantId 提交 */
  id: SnowflakeId
  /** 仓库名 */
  name: string
}
