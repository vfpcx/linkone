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

// ============ WA 入驻审批（P2 入驻生态 Wave1 契约） ============
/**
 * 契约（shared/task_plan.md 接口契约 + architecture/03-database-schema.sql wholesaler_applications）：
 *  - POST /api/v1/wholesaler/applications                    WA 提交入驻申请（body: targetTenantId/name/contact/phone/license?）
 *  - GET  /api/v1/tenant/wholesaler-applications?status=&page=&size=   TA 分页列表
 *  - POST /api/v1/tenant/wholesaler-applications/{id}/audit  TA 审批（action: APPROVED|REJECTED，驳回 remark 必填）
 * 错误码：50201 审核中（重复提交）/ 50204 重复入驻 / 50205 黑名单拦截。
 */
export type WaApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface WholesalerApplication {
  applicationId: SnowflakeId
  /** 目标仓库（tenant）ID */
  tenantId?: SnowflakeId
  /** 申请人用户 ID */
  applicantUserId?: SnowflakeId
  wholesalerName: string
  contactName: string
  contactPhone: string
  /** 营业执照号（可选键，黑名单双键之一） */
  licenseNo?: string
  businessLicenseUrl?: string
  appliedAt: string
  status: WaApplicationStatus
  /** 审核备注；REJECTED 时为驳回理由 */
  remark?: string
  /** 审核时间 */
  auditedAt?: string
  /** 通过后生成的商户 ID */
  wholesalerId?: SnowflakeId
}

/** WA 提交入驻申请入参（POST /wholesaler/applications） */
export interface SubmitWaApplicationRequest {
  /** 目标仓库 tenantId（雪花字符串） */
  targetTenantId: SnowflakeId
  /** 商户名 */
  name: string
  /** 联系人 */
  contact: string
  /** 联系电话 */
  phone: string
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

// ============ 强制下架 WA ============
export interface ForceOfflineWaRequest {
  reason: string
  effectImmediate: boolean
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
}

/** 入库单视图对象（InboundRequestVo） */
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
  /** 登记后该 sku 最新库存（便于前端回显） */
  currentStock: number | null
  createdAt: string
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
