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
