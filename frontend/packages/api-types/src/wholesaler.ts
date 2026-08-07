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
// R13 退驻 / R14 强制下架（P2 入驻生态 Wave2 · 后端已落地）
// 权威来源：shared/architecture/10-onboarding-design.md §10-§14（据实现编写）
//  - POST /api/v1/wholesaler/withdraw          发起退驻（body: { reason? }）
//  - GET  /api/v1/wholesaler/withdraw/precheck 前置自查（与提交校验同一份逻辑）
//  - POST /api/v1/wholesaler/withdraw/cancel   撤回本人 PENDING 申请
//  - GET  /api/v1/wholesaler/withdraw/mine     本人最近一次退驻申请（无申请 data=null）
//  - POST /api/v1/wholesaler/withdraw/restore  60 天内恢复（WITHDRAWN→ACTIVE）
//  - TA 端 GET  /api/v1/tenant/wholesaler-withdraw-applications?status&page&size
//         POST /api/v1/tenant/wholesaler-withdraw-applications/{id}/audit
//         POST /api/v1/tenant/wholesalers/{id}/force-offline
// 错误码：50312 库存未清 / 50314 未结单据 / 50315 不可审(撤) / 50316 重复申请 /
//        50317 恢复窗口已过 / 50318 状态机不可达 / 50202 已退驻
// ============================================================

/**
 * 退驻申请单状态（wholesaler_withdraw_applications.status，四值）：
 *  PENDING 审批中 → APPROVED 已通过 | REJECTED 已驳回 | CANCELLED 已撤回（WA 撤回，可重新发起）
 * 60 天恢复/归档窗口以 auditedAt（通过时刻）为唯一时间起点，前端自行 +60 天计算。
 */
export type WaWithdrawStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'

/** 商户主体状态（wholesalers.status，Wave2 状态机） */
export type WholesalerLifecycleStatus = 'ACTIVE' | 'WITHDRAWN' | 'OFFLINE' | 'ARCHIVED'

/** 退驻申请（WithdrawApplicationVo · WA 端 mine / TA 端列表共用视图） */
export interface WaWithdrawApplication {
  id: SnowflakeId
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  /** 商户名冗余（仅 TA 列表有值） */
  wholesalerName?: string
  /** 申请人（该商户 WA）用户 ID */
  applicantUserId: SnowflakeId
  /** 退驻原因（WA 发起时选填） */
  reason?: string
  status: WaWithdrawStatus
  auditUserId?: SnowflakeId
  /** 审核时间；APPROVED 时即退驻生效时刻，60 天恢复窗口起点 */
  auditedAt?: string
  /** 审核备注；REJECTED 时为驳回理由 */
  auditRemark?: string
  createdAt: string
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
 * 退驻前置自查返回（GET /wholesaler/withdraw/precheck，只读，与提交校验同一份逻辑）。
 * billing.cleared 恒 null（O-5 计费 P4 落地前灰态占位 ⊘）。
 */
export interface WaWithdrawPrecheck {
  wholesalerId: SnowflakeId
  /** 商户当前主体状态 */
  status: WholesalerLifecycleStatus
  /** 库存已清零（false → 50312） */
  stockCleared: boolean
  /** 未结单据（cleared=false 时 count>0 → 50314） */
  openDocs: {
    cleared: boolean
    count: number
  }
  /**
   * 账单结清（P4 W3 起为真值 {cleared, count}——14-p4 §3.5-1 O-5 兑现；
   * cleared=false 时 count>0 为未结清账单张数（含争议中），发起/审批双检 50323）
   */
  billing: {
    cleared: boolean
    count: number
  }
}

/** 发起退驻返回（POST /wholesaler/withdraw） */
export interface WithdrawSubmitResult {
  applicationId: SnowflakeId
  wholesalerId: SnowflakeId
  status: WaWithdrawStatus
}

/** 撤回退驻申请返回（POST /wholesaler/withdraw/cancel） */
export interface WithdrawCancelResult {
  applicationId: SnowflakeId
  status: 'CANCELLED'
}

/** 恢复入驻返回（POST /wholesaler/withdraw/restore） */
export interface WithdrawRestoreResult {
  wholesalerId: SnowflakeId
  status: 'ACTIVE'
}

/** TA 审批退驻返回（POST /tenant/wholesaler-withdraw-applications/{id}/audit） */
export interface WithdrawAuditResult {
  applicationId: SnowflakeId
  status: WaWithdrawStatus
  wholesalerId: SnowflakeId
}

/** R14 强制下架返回（POST /tenant/wholesalers/{id}/force-offline） */
export interface ForceOfflineResult {
  wholesalerId: SnowflakeId
  status: 'OFFLINE'
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

/**
 * WE 授权位（后端 WePermissions.ALLOWED 白名单，P3b T1 起共 4 枚）：
 * 改价 / 询价确认 / 代建入库确认（P3 BE-W1）/ 提交入库申请（P3b T1-BE，D-5 第 4 枚）
 */
export type WePermission =
  | 'PRICE_EDIT'
  | 'INQUIRY_CONFIRM'
  | 'INBOUND_CONFIRM'
  | 'INBOUND_SUBMIT'

export type WaEmployeeStatus = 'ACTIVE' | 'DISABLED'

/** 本商户 WE 员工（GET /wholesaler/employees · Wave3 后端最终 VO） */
export interface WaEmployee {
  /** 角色绑定行 id（员工管理端点 {id} 一律用它，不是 userId） */
  id: SnowflakeId
  userId: SnowflakeId
  wholesalerId: SnowflakeId
  /** 手机号（后端脱敏下发） */
  phone: string
  nickname: string
  realName: string
  permissions: WePermission[]
  status: WaEmployeeStatus
  /** 禁用时间（DISABLED 时下发；恢复截止 = disabledAt + 30 天，前端自算） */
  disabledAt?: string
  createdAt: string
}

/** WE 员工注册码（复用 invite_codes + wholesaler_id） */
export type WaEmployeeInviteStatus = 'ACTIVE' | 'EXHAUSTED' | 'REVOKED'

export interface WaEmployeeInvite {
  id: SnowflakeId
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  code: string
  /** 目标角色（固定 'WE'，后端下发） */
  role: string
  /** 初始授权（注册后可在员工列表调整） */
  permissions: WePermission[]
  maxUses: number
  usedCount: number
  /** 剩余可用次数（后端下发 = maxUses - usedCount） */
  remaining: number
  expireAt: string
  status: WaEmployeeInviteStatus
  createdAt: string
}

/** 生码入参（targetRole 固定 WE，后端不收该字段） */
export interface CreateWaEmployeeInviteRequest {
  /** 有效天数（后端缺省 7；线框三档 7 天 / 3 天 / 24 小时=1） */
  expireDays?: number
  /** 使用次数上限 1~20（后端缺省 1） */
  maxUses?: number
  /** 初始授权 ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]，可省略/空数组 */
  permissions?: WePermission[]
}

/** 改授权入参（PUT /wholesaler/employees/{id}/permissions；空数组=收回全部） */
export interface UpdateWaEmployeePermissionsRequest {
  permissions: WePermission[]
}

/** R17 禁用返回（POST /wholesaler/employees/{id}/disable） */
export interface DisableWaEmployeeResult {
  /** 恢复窗口天数（固定 30；倒计时前端按 disabledAt + 30 天自算） */
  restoreWindowDays: number
}
