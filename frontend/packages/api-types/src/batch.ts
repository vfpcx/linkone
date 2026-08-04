/**
 * P3b T4 批次登记簿 / 临期看板 / 清库单类型（13-p3b-design.md §5.3）
 *
 * 权威来源（据实查证，2026-08-04）：
 *  - backend/.../inventory/controller/BatchController.java
 *      （BatchVo / BatchListVo / ExpiryDashboardVo / BatchToggleVo / BatchToggleDto / BatchBackfillDto）
 *  - backend/.../document/controller/TenantClearanceController.java
 *      （ClearanceRequestVo / ClearanceCreateDto / ClearanceUpdateDto / ClearanceDecideDto）
 *
 * 契约要点（13 v1.4 备注 12 / v1.5 备注 13）：
 *  - BatchVo 不含 skuName/wholesalerName（列表量大免 join），前端以 SKU/商户接口自行映射；
 *  - remainingDays = expiry − today，可负（已过期）、无到效期为 null；
 *  - remainingQty 为 FIFO 离线推算值（截至 02:00），UI 须标注「推算」；
 *  - unpooledQty 仅 SKU 下钻返回，可为负（推算滞后窗口），UI 归 0 展示；
 *  - manualNotifiedAt 供「通知批发商」按钮 24h 冷却展示（50367）。
 */

import type { SnowflakeId } from './common'

// ============ 批次登记簿 ============

/**
 * 批次状态（登记簿，PRD §3.3-5）：
 *  在库 → 临期(02:00 判定) → 待清理(02:30 到期归零标记) → 已清库(清库单通过)；
 *  任一阶段推算剩余归 0 → 已售罄；启→关 → 已冻结（再启用不复活）。
 */
export type BatchStatus =
  | 'IN_STOCK'
  | 'EXPIRING'
  | 'PENDING_CLEARANCE'
  | 'SOLD_OUT'
  | 'CLEARED'
  | 'CLOSED'

/** 批次来源：INBOUND=入库登记产生 / DEFAULT=开关启用时吸收存量的默认批次（可补录效期） */
export type BatchSource = 'INBOUND' | 'DEFAULT'

/** 批次行（BatchVo） */
export interface Batch {
  id: SnowflakeId
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  batchNo: string
  /** yyyy-MM-dd；默认批次未补录时为 null */
  productionDate: string | null
  expiryDate: string | null
  /** 剩余天数（expiry − today；无到效期 null；可负=已过期） */
  remainingDays: number | null
  /** 累计入库量 */
  initialQty: number
  /** FIFO 推算剩余（非记账值，截至 02:00） */
  remainingQty: number
  status: BatchStatus | string
  source: BatchSource | string
  /** WK 一键通知商户时刻（24h 冷却基准，50367） */
  manualNotifiedAt: string | null
  clearedAt: string | null
  createdAt: string
}

/** 批次列表（BatchListVo） */
export interface BatchList {
  list: Batch[]
  /** 无批次在池量（仅 wholesalerId+skuId 齐时返回；可为负，UI 归 0 展示） */
  unpooledQty?: number | null
}

/** TA 批次开关（POST /tenant/settings/batch-toggle；真实翻转须 confirmed=true，24h≤2 → 50361） */
export interface BatchToggleRequest {
  enable: boolean
  confirmed?: boolean
}

/** 开关结果（BatchToggleVo） */
export interface BatchToggleResult {
  /** 操作后开关状态 1/0 */
  batchEnabled: number
  /** 最近启用时刻（FIFO 切割时点） */
  batchEnabledAt: string | null
  /** 本次生成的默认批次数（关→启吸收存量；其余 0） */
  defaultBatchCount: number
  /** 本次冻结的批次数（启→关；其余 0） */
  closedBatchCount: number
}

/** 默认批次补录（PUT /tenant/batches/{id}；仅 source=DEFAULT 且非 CLEARED/CLOSED） */
export interface BatchBackfillRequest {
  /** ≤今天（40205）；可空=不改 */
  productionDate?: string
  /** >生产日期（40206）；可空=不改 */
  expiryDate?: string
}

// ============ TA 临期看板（GET /tenant/batches/expiry-dashboard） ============

/** 看板按 SKU 分组行（skuId/wholesalerId 字符串键防 JS 精度） */
export interface ExpiryDashboardSkuGroup {
  skuId: string
  skuName: string
  wholesalerId: string
  expiringBatchCount: number
  expiredBatchCount: number
  /** Σ推算剩余（临期∪待清理） */
  remainingQtyTotal: number
  /** 组内最近到效期（组间按此升序） */
  nearestExpiryDate: string | null
}

/** 临期看板（ExpiryDashboardVo：四卡汇总 + bySku 分组） */
export interface ExpiryDashboard {
  /** 临期阈值天数（tenant_settings.expiry_threshold_days） */
  thresholdDays: number
  expiringBatchCount: number
  expiringQtyTotal: number
  expiredBatchCount: number
  expiredQtyTotal: number
  clearedBatchCount: number
  /** 清库单待审批数（Controller 编排合入） */
  pendingClearanceDocCount: number | null
  bySku: ExpiryDashboardSkuGroup[]
}

// ============ 清库单（QK-，状态机完全套用盘点） ============

export type ClearanceStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'REJECTED' | 'APPROVED'

/** 清库原因单选（OTHER 时 reasonRemark 必填） */
export type ClearanceReason = 'EXPIRED' | 'DAMAGED' | 'OTHER'

export type ClearanceConclusion = 'APPROVED' | 'REJECTED'

/** 清库单视图（ClearanceRequestVo；列表不带名称，详情附批次五字段 + 封顶预览） */
export interface ClearanceRequest {
  id: SnowflakeId
  docNo: string
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  /** 详情链路附带（列表为省 join 不带，盘点先例） */
  wholesalerName: string | null
  skuId: SnowflakeId
  /** 详情链路附带 */
  skuName: string | null
  batchId: SnowflakeId
  /** 批次只读信息（表单/审批弹窗展示） */
  batchNo: string | null
  batchExpiryDate: string | null
  /** 批次推算剩余（截至 02:00，UI 标注「推算」） */
  batchRemainingQty: number | null
  /** 清库件数（现场核数） */
  qty: number
  /** 释放托盘（提交=覆盖值或 null 默认比例；APPROVED 后=实际释放值） */
  palletRelease: number | null
  reason: ClearanceReason | string
  reasonRemark: string | null
  attachments: string[] | null
  status: ClearanceStatus | string
  wkUserId: SnowflakeId | null
  taUserId: SnowflakeId | null
  decidedAt: string | null
  rejectRemark: string | null
  remark: string | null
  /** 详情链路：当刻池在库（审批封顶预览基准 min(qty, currentStock)） */
  currentStock: number | null
  /** 详情链路：默认释放托盘建议值（覆盖时=覆盖值） */
  suggestedPalletRelease: number | null
  createdAt: string
  updatedAt: string | null
}

/** WK 建草稿（POST /tenant/clearance-requests；前置 50365、照片 ≥1≤3 50366） */
export interface ClearanceCreateRequest {
  batchId: SnowflakeId
  /** 现场核数（≤该 SKU 池当前在库 50251；空=默认批次推算剩余） */
  qty?: number
  reason: ClearanceReason
  /** OTHER 时必填 */
  reasonRemark?: string
  /** 释放托盘覆盖值（空=默认比例；含 0） */
  palletRelease?: number
  /** 实物照片 URL ≥1 ≤3（刚性，不受拍照开关影响） */
  attachments: string[]
  remark?: string
}

/** WK 编辑（PUT /{id}；DRAFT 直改 / REJECTED 改回 DRAFT 重提；batchId 不可变） */
export interface ClearanceUpdateRequest {
  qty?: number
  reason: ClearanceReason
  reasonRemark?: string
  palletRelease?: number
  attachments: string[]
  remark?: string
}

/** TA 审批（POST /{id}/decide；REJECTED 时 remark 必填） */
export interface ClearanceDecideRequest {
  conclusion: ClearanceConclusion
  remark?: string
}
