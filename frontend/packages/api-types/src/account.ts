/**
 * 账号接口 TS 类型
 * 权威契约：shared/architecture/api-contract-account.md（以后端实现为单一事实源）
 *
 * 所有账号接口 base path 统一为 /api/v1/account（后端未做 public/common 拆分）。
 * 后端已实现 7 个接口 + 短信发送：
 *  1. POST /api/v1/account/sms-code             发送短信验证码
 *  2. POST /api/v1/account/register             注册（角色感知入口）
 *  3. POST /api/v1/account/login                登录（密码 / 验证码二选一）
 *  4. PUT  /api/v1/account/password             修改密码（登录态）
 *  5. POST /api/v1/account/password/reset       找回密码
 *  6. PUT  /api/v1/account/phone                换绑手机号（登录态）
 *  7. POST /api/v1/account/login/rt?phone=&code= RT 免密验证码登录（query 参数，非 body）
 *  8. POST /api/v1/account/logout               退出登录（登录态）
 *
 * 后端 MVP 尚未实现（保留兼容包装，调用会报错）：
 *  - GET/PATCH /api/v1/account/profile  我的资料 / 改资料
 *  - GET  /api/v1/account/roles         我的所有角色（登录响应已带 roles）
 *  - POST /api/v1/account/switch-role   切换主角色
 */

import type { Role, Device, SnowflakeId } from './common'

// ============ 短信验证码 ============
export type SmsCodePurpose = 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD' | 'CHANGE_PHONE' | 'CHANGE_PASSWORD'

export interface SendSmsCodeRequest {
  phone: string
  /** 后端字段名 = scene（对齐 SmsCodeSendDto） */
  scene: SmsCodePurpose
  /** 滑块/极验 token，由产品决定哪些 scene 必填 */
  captchaToken?: string
}

/**
 * 后端 #1 返回 R<Void>（data: null），不下发任何字段。
 * 倒计时由前端本地维护（默认 60s）；该响应仅用于占位/兼容，全部字段可选。
 */
export interface SendSmsCodeResponse {
  /** 后端 MVP 不下发；前端本地兜底（缺省 60） */
  cooldownSec?: number
  /** 后端 MVP 不下发，保留扩展位 */
  registered?: boolean
}

// ============ 注册 ============
/**
 * 对齐后端 RegisterDto（phone/password/smsCode/role/inviteCode/nickname）。
 * 注：realName/tenantName/wholesalerName/targetTenantId/agreedTerms 后端 DTO 不接收，
 * 已从请求体移除（业务字段后续待后端补 DTO 后再加，见契约 §7.2 / Team Lead 协调点）。
 */
export interface RegisterRequest {
  phone: string
  /** 后端校验：6–20 位含字母数字；RT 入口可不传（默认 TA 才必填） */
  password?: string
  smsCode: string
  /** 注册入口角色，默认 TA；可选 TA/WK/ST/WA/WE/RT */
  role?: Role
  /** 员工注册（WK/ST/WE）邀请码 */
  inviteCode?: string
  /** 昵称（后端唯一接收的展示字段） */
  nickname?: string
}

/** 注册与登录返回同一结构 LoginVo，isNew=true。 */
export type RegisterResponse = LoginResponse

// ============ 登录 ============
/** 对齐后端 LoginDto：密码登录与验证码登录二选一。 */
export interface LoginRequest {
  phone: string
  /** 密码登录时必填 */
  password?: string
  /** 验证码登录时必填 */
  smsCode?: string
  /** 默认 PC */
  device?: Device
  deviceInfo?: string
}

export interface LoginRoleEntry {
  role: Role
  tenantId: SnowflakeId | null
  wholesalerId: SnowflakeId | null
  priority: number           // 数字小优先级高（TA=10/ST=20/WK=30/WA=40/WE=50/RT=60）
  /** 前端可选扩展：后端 MVP 未下发，切换器用 */
  storeName?: string
  /** 前端可选扩展：后端 MVP 未下发 */
  pendingCount?: number
}

export interface LoginResponse {
  token: string
  userId: SnowflakeId
  primaryRole: Role
  /** 后端字段名 roles（原 roleList，2026-06-28 已统一） */
  roles: LoginRoleEntry[]
  primaryRouter: string
  expireAt: string
  /** NON_NULL：无租户上下文时后端不下发 */
  tenantInfo?: {
    tenantId: SnowflakeId
    tenantName: string
    tenantSimpleCode: string
  }
  /** NON_NULL：仅注册 / RT 首次自动注册时为 true */
  isNew?: boolean
  // 注：多角色不由后端下发，前端按 roles.length > 1 自行推导（勿依赖后端字段）。
}

// ============ RT 免密验证码登录 ============
/**
 * 后端 #7 用 query 参数（?phone=&code=），非 JSON body。
 * 字段名对齐后端 @RequestParam：phone / code。
 */
export interface RtSmsLoginRequest {
  phone: string
  /** 后端参数名为 code（非 smsCode） */
  code: string
}

export type RtSmsLoginResponse = LoginResponse

// ============ 退出 ============
export interface LogoutRequest {
  /** 是否退出所有设备 */
  allDevices?: boolean
}

// ============ 改密 ============
/** 对齐后端 ChangePasswordDto：仅 oldPassword/newPassword（无 smsCode）。 */
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

// ============ 找回密码 ============
/** 对齐后端 ResetPasswordDto：无 confirmPassword（两次输入校验在前端做）。 */
export interface ResetPasswordRequest {
  phone: string
  smsCode: string
  newPassword: string
}

// ============ 换绑手机号 ============
/** 对齐后端 ChangePhoneDto：oldSmsCode/newPhone/newSmsCode/password。 */
export interface ChangePhoneRequest {
  /** 原手机号验证码（后端字段名 oldSmsCode） */
  oldSmsCode: string
  newPhone: string
  /** 新手机号验证码（后端字段名 newSmsCode） */
  newSmsCode: string
  password: string
}

// ============ 资料 ============
export interface UserProfile {
  userId: SnowflakeId
  phone: string              // 脱敏：138****1234
  realName?: string
  nickname?: string
  avatarUrl?: string
  primaryRole: Role
  createdAt: string
  lastLoginAt?: string
  lastLoginIp?: string
}

export interface UpdateProfileRequest {
  nickname?: string
  avatarUrl?: string
}

// ============ 多角色 ============
export interface MyRolesResponse {
  primaryRole: Role
  roles: LoginRoleEntry[]
}

export interface SwitchRoleRequest {
  targetRole: Role
  tenantId?: SnowflakeId
  wholesalerId?: SnowflakeId
}

export interface SwitchRoleResponse {
  token: string              // 新 token（角色切换通常重发）
  primaryRole: Role
  primaryRouter: string
}
