/**
 * 账号接口 TS 类型
 * 对齐 shared/architecture/04-api-spec.md §4.1 + §5.1
 *
 * 共 12 个接口：
 *  1. POST /api/v1/public/account/sms-code      发送短信验证码
 *  2. POST /api/v1/public/account/register      注册
 *  3. POST /api/v1/public/account/login         登录
 *  4. POST /api/v1/public/rt/sms-login          RT 验证码登录
 *  5. POST /api/v1/common/account/logout        退出登录
 *  6. POST /api/v1/common/account/change-password   改密
 *  7. POST /api/v1/public/account/reset-password    找回密码
 *  8. POST /api/v1/common/account/change-phone      换绑手机号
 *  9. GET  /api/v1/common/account/profile           我的资料
 * 10. PATCH /api/v1/common/account/profile          改资料
 * 11. GET  /api/v1/common/account/roles             我的所有角色
 * 12. POST /api/v1/common/account/switch-role       切换主角色
 */

import type { Role, Device, SnowflakeId } from './common'

// ============ 短信验证码 ============
export type SmsCodePurpose = 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD' | 'CHANGE_PHONE' | 'CHANGE_PASSWORD'

export interface SendSmsCodeRequest {
  phone: string
  purpose: SmsCodePurpose
  /** 滑块/极验 token，由产品决定哪些 purpose 必填 */
  captchaToken?: string
}

export interface SendSmsCodeResponse {
  /** 下次可重发的秒数 */
  cooldownSec: number
  /** 该手机号是否已注册（仅 purpose=RESET_PASSWORD/CHANGE_PASSWORD 返回） */
  registered?: boolean
}

// ============ 注册 ============
export interface RegisterRequest {
  phone: string
  smsCode: string
  password?: string             // RT 注册时为空
  realName?: string             // RT 注册时为空
  role: Role                    // 从 URL ?role= 解析得到
  inviteCode?: string           // WK/ST/WE 必填
  tenantName?: string           // TA 必填
  wholesalerName?: string       // WA 必填
  targetTenantId?: SnowflakeId  // WA 入驻必填
  agreedTerms: boolean
}

export interface RegisterResponse {
  userId: SnowflakeId
  role: Role
  token: string
  primaryRouter: string
}

// ============ 登录 ============
export interface LoginRequest {
  phone: string
  password: string
  device: Device
  deviceInfo?: string
}

export interface LoginRoleEntry {
  role: Role
  tenantId: SnowflakeId | null
  wholesalerId: SnowflakeId | null
  priority: number           // 数字小优先级高（TA=10/ST=20/WK=30/WA=40/WE=50/RT=60）
  storeName?: string
  pendingCount?: number      // 待办数量（用于切换器）
}

export interface LoginResponse {
  token: string
  userId: SnowflakeId
  primaryRole: Role
  roles: LoginRoleEntry[]
  primaryRouter: string
  expireAt: string
  tenantInfo?: {
    tenantId: SnowflakeId
    tenantName: string
    tenantSimpleCode: string
  }
  /** 是否需要弹出多角色切换器（roles.length > 1） */
  multiRole?: boolean
}

// ============ RT 验证码登录 ============
export interface RtSmsLoginRequest {
  phone: string
  smsCode: string
  agreedTerms?: boolean      // 仅新号需勾选
}

export type RtSmsLoginResponse = LoginResponse

// ============ 退出 ============
export interface LogoutRequest {
  /** 是否退出所有设备 */
  allDevices?: boolean
}

// ============ 改密 ============
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
  smsCode: string
}

// ============ 找回密码 ============
export interface ResetPasswordRequest {
  phone: string
  smsCode: string
  newPassword: string
  confirmPassword: string
}

// ============ 换绑手机号 ============
export interface ChangePhoneRequest {
  oldPhoneSmsCode: string
  newPhone: string
  newPhoneSmsCode: string
  password?: string          // RT 除外
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
