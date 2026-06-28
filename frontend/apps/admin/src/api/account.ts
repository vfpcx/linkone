/**
 * 账号接口封装（admin 端）
 *
 * ⚠️ 路径已对齐后端真实实现（commit c7d397a）：
 *   - 后端 controller @RequestMapping = /api/v1/account
 *   - 前端 http baseURL = /api/v1
 *   - 所以 url 直接写 /account/xxx 即可
 *
 * 未实现的接口（profile / roles / switch-role）保留兼容包装，
 * 调用时会返回 SYSTEM error，前端可降级或 mock。
 */

import { request } from './http'
import type {
  ChangePasswordRequest,
  ChangePhoneRequest,
  LoginRequest,
  LoginResponse,
  LogoutRequest,
  MyRolesResponse,
  RegisterRequest,
  RegisterResponse,
  ResetPasswordRequest,
  RtSmsLoginRequest,
  RtSmsLoginResponse,
  SendSmsCodeRequest,
  SendSmsCodeResponse,
  SwitchRoleRequest,
  SwitchRoleResponse,
  UpdateProfileRequest,
  UserProfile,
} from '@cangchu/api-types'

export const accountApi = {
  /** 1. 发送短信验证码（后端 mock：固定 888888） */
  sendSmsCode: (data: SendSmsCodeRequest) =>
    request<SendSmsCodeResponse>({
      method: 'POST',
      url: '/account/sms-code',
      data,
    }),

  /** 2. 注册（返回 LoginVo，isNew=true） */
  register: (data: RegisterRequest) =>
    request<RegisterResponse>({
      method: 'POST',
      url: '/account/register',
      data,
    }),

  /** 3. 登录（密码 / 验证码二选一） */
  login: (data: LoginRequest) =>
    request<LoginResponse>({
      method: 'POST',
      url: '/account/login',
      data,
    }),

  /** 4. RT 免密验证码登录（首次自动注册）。后端用 query 参数 phone/code，非 body。 */
  rtSmsLogin: (data: RtSmsLoginRequest) =>
    request<RtSmsLoginResponse>({
      method: 'POST',
      url: '/account/login/rt',
      params: { phone: data.phone, code: data.code },
    }),

  /** 5. 退出登录 */
  logout: (data?: LogoutRequest) =>
    request<void>({
      method: 'POST',
      url: '/account/logout',
      data: data ?? {},
    }),

  /** 6. 改密码（PUT） */
  changePassword: (data: ChangePasswordRequest) =>
    request<void>({
      method: 'PUT',
      url: '/account/password',
      data,
    }),

  /** 7. 找回密码 */
  resetPassword: (data: ResetPasswordRequest) =>
    request<void>({
      method: 'POST',
      url: '/account/password/reset',
      data,
    }),

  /** 8. 换绑手机号（PUT） */
  changePhone: (data: ChangePhoneRequest) =>
    request<void>({
      method: 'PUT',
      url: '/account/phone',
      data,
    }),

  /** 9. 我的资料 ⚠️ 后端未实现，调用会 500 */
  profile: () =>
    request<UserProfile>({
      method: 'GET',
      url: '/account/profile',
    }),

  /** 10. 修改资料 ⚠️ 后端未实现 */
  updateProfile: (data: UpdateProfileRequest) =>
    request<UserProfile>({
      method: 'PATCH',
      url: '/account/profile',
      data,
    }),

  /** 11. 我的所有角色 ⚠️ 后端未实现，登录响应里已带 roles */
  myRoles: () =>
    request<MyRolesResponse>({
      method: 'GET',
      url: '/account/roles',
    }),

  /** 12. 切换主操作角色 ⚠️ 后端未实现 */
  switchRole: (data: SwitchRoleRequest) =>
    request<SwitchRoleResponse>({
      method: 'POST',
      url: '/account/switch-role',
      data,
    }),
}
