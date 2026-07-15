/**
 * 批发商商户接口 TS 类型（phase-1 D1a 卖家侧）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../tenant/controller/WholesalerController.java
 *  - DTO：WholesalerCreateDto / WholesalerUpdateDto
 *  - VO：WholesalerVo
 *
 * 接口（均需 TA 登录态，tenantId 由后端登录态推导，前端不传）：
 *  - POST /api/v1/tenant/wholesalers          创建（TA 自营建商户）
 *  - PUT  /api/v1/tenant/wholesalers/{id}      改资料（仅 license / intro）
 *  - GET  /api/v1/tenant/wholesalers           列出本租户商户
 *
 * ⚠️ 雪花 ID 字段均为 string（后端 ToStringSerializer）。
 */

import type { SnowflakeId } from './common'

/** 商户视图对象（WholesalerVo） */
export interface Wholesaler {
  id: SnowflakeId
  tenantId: SnowflakeId
  name: string
  /** 商户负责人（创建者）用户 ID */
  ownerUserId: SnowflakeId
  license: string | null
  intro: string | null
  /** 状态：phase-1 后端创建即 ACTIVE（具体取值以后端为准） */
  status: string
  /** 来源：自营 SELF_OPERATED 等（以后端为准） */
  source: string
  /** WA 账号对应的 user_roles.id（开通 waPhone 时返回，否则 null） */
  waUserId: SnowflakeId | null
  createdAt: string
}

/** 创建商户请求（WholesalerCreateDto）：name @NotBlank 必填，其余可选 */
export interface CreateWholesalerRequest {
  name: string
  license?: string
  intro?: string
  /** 商户负责人手机号；传入则后端建/绑一个 WA 角色 */
  waPhone?: string
}

/** 改资料请求（WholesalerUpdateDto）：phase-1 仅 license / intro */
export interface UpdateWholesalerRequest {
  license?: string
  intro?: string
}

// ============================================================
// R13 退驻（P2 入驻生态 Wave2 契约，后端并行开发中）
//  - POST /api/v1/wholesaler/withdraw          发起退驻（body: { reason? }）
//  - GET  /api/v1/wholesaler/withdraw/mine     本人退驻申请状态（含驳回理由）
//  - POST /api/v1/wholesaler/withdraw/restore  60 天内申请恢复
//  - TA 端 GET  /api/v1/tenant/wholesaler-withdraw-applications?status&page&size
//         POST /api/v1/tenant/wholesaler-withdraw-applications/{id}/audit
// 错误码：50312+ 退驻前置不满足（50310-50329 段）/ 50202 已退驻
// ============================================================

/**
 * 退驻申请状态机（04 §1.8）：
 *  PENDING 审批中 → REJECTED 已驳回 | APPROVED 已退驻（60 天恢复期）
 *  APPROVED → RESTORED 已恢复（60 天内）| ARCHIVED 已归档（60 天后）
 */
export type WaWithdrawStatus =
  | 'PENDING'
  | 'REJECTED'
  | 'APPROVED'
  | 'RESTORED'
  | 'ARCHIVED'

/** 退驻申请（WA 端 mine / TA 端列表共用视图） */
export interface WaWithdrawApplication {
  id: SnowflakeId
  wholesalerId?: SnowflakeId
  wholesalerName?: string
  tenantId?: SnowflakeId
  /** 仓库名（WA 状态页展示"您已退驻 XX 海鲜库"） */
  tenantName?: string
  /** 退驻原因（WA 发起时选填） */
  reason?: string
  status: WaWithdrawStatus
  /** 审核备注；REJECTED 时为驳回理由 */
  remark?: string
  appliedAt: string
  auditedAt?: string
  /** 退驻生效时间（APPROVED 后） */
  effectiveAt?: string
  /** 恢复截止时间（生效 + 60 天） */
  restoreDeadline?: string
}

/** 发起退驻入参（POST /wholesaler/withdraw） */
export interface SubmitWithdrawRequest {
  /** 退驻原因（选填，≤200 字） */
  reason?: string
}

/** TA 退驻审批列表查询参数 */
export interface WaWithdrawListQuery {
  status?: WaWithdrawStatus
  page?: number
  size?: number
}

/**
 * 退驻前置自查（⚠️ 契约微调位 GET /wholesaler/withdraw/precheck）。
 * Wave2 契约未显式列出该端点；后端未提供时页面降级为
 * "提交时由后端复核"（50310-50329 段错误回填清单）。
 */
export interface WaWithdrawPrecheckDocItem {
  /** 单据类型（如 OUTBOUND_PRINTED / INQUIRY_CONFIRMED） */
  type: string
  /** 展示名（后端可选下发；缺省前端按 type 映射） */
  label?: string
  count: number
}

export interface WaWithdrawPrecheck {
  /** 当前在库件数（=0 才通过） */
  stockQty: number
  /** 未结单据（空数组才通过） */
  openDocs: WaWithdrawPrecheckDocItem[]
}

// ============================================================
// WE 员工管理（P2 入驻生态 Wave3 契约 · Team Lead 定稿 6 端点）
//  - POST   /api/v1/wholesaler/employee-invites      生码 {expireDays,maxUses,permissions}
//  - GET    /api/v1/wholesaler/employee-invites      列表
//  - DELETE /api/v1/wholesaler/employee-invites/{id} 作废
//  - GET    /api/v1/wholesaler/employees             本商户 WE 列表
//  - PUT    /api/v1/wholesaler/employees/{id}/permissions  授权即时生效
//  - POST   /api/v1/wholesaler/employees/{id}/disable  R17 禁用（踢出 + 草稿作废）
//    POST   /api/v1/wholesaler/employees/{id}/restore  30 天内恢复
// ============================================================

/** WE 授权位（O-4 最小集，仅此两枚） */
export type WePermission = 'PRICE_EDIT' | 'INQUIRY_CONFIRM'

export type WaEmployeeStatus = 'ACTIVE' | 'DISABLED'

/** 本商户 WE 员工（GET /wholesaler/employees） */
export interface WaEmployee {
  userId: SnowflakeId
  realName: string
  /** 手机号（后端脱敏下发） */
  phone: string
  permissions: WePermission[]
  status: WaEmployeeStatus
  /** 禁用时间（DISABLED 时下发） */
  disabledAt?: string
  /** 恢复截止（禁用 + 30 天；过期后不可恢复） */
  restoreDeadline?: string
  joinedAt?: string
}

/** WE 员工注册码（复用 invite_codes + wholesaler_id） */
export type WaEmployeeInviteStatus = 'ACTIVE' | 'EXHAUSTED' | 'REVOKED'

export interface WaEmployeeInvite {
  id: SnowflakeId
  wholesalerId?: SnowflakeId
  code: string
  /** 初始授权（注册后可在员工列表调整） */
  permissions: WePermission[]
  maxUses: number
  usedCount: number
  /** 剩余可用次数（后端可选下发） */
  remaining?: number
  expireAt: string
  status: WaEmployeeInviteStatus
}

/** 生码入参（targetRole 固定 WE，后端不收该字段） */
export interface CreateWaEmployeeInviteRequest {
  /** 有效天数（默认 7；线框三档 7 天 / 3 天 / 24 小时=1） */
  expireDays?: number
  /** 使用次数上限 1~20（默认 5） */
  maxUses?: number
  /** 初始授权 ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]，可为空数组 */
  permissions: WePermission[]
}

/** 改授权入参（PUT /wholesaler/employees/{id}/permissions） */
export interface UpdateWaEmployeePermissionsRequest {
  permissions: WePermission[]
}
