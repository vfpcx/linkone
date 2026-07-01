/**
 * 租户接口 TS 类型
 * 对齐 shared/architecture/04-api-spec.md §4.3
 */

import type { SnowflakeId, PageRequest, PageData } from './common'

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

// ============ WA 入驻审批 ============
export interface WholesalerApplication {
  applicationId: SnowflakeId
  wholesalerName: string
  contactName: string
  contactPhone: string
  businessLicenseUrl?: string
  appliedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  remark?: string
}

export interface ApproveWaRequest {
  remark?: string
}
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
