/**
 * 账号接口封装（admin 端）
 * 对齐 shared/architecture/04-api-spec.md §4.1
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
  /** 1. 发送短信验证码 */
  sendSmsCode: (data: SendSmsCodeRequest) =>
    request<SendSmsCodeResponse>({
      method: 'POST',
      url: '/public/account/sms-code',
      data,
    }),

  /** 2. 注册 */
  register: (data: RegisterRequest) =>
    request<RegisterResponse>({
      method: 'POST',
      url: '/public/account/register',
      data,
    }),

  /** 3. 登录 */
  login: (data: LoginRequest) =>
    request<LoginResponse>({
      method: 'POST',
      url: '/public/account/login',
      data,
    }),

  /** 4. RT 验证码登录 */
  rtSmsLogin: (data: RtSmsLoginRequest) =>
    request<RtSmsLoginResponse>({
      method: 'POST',
      url: '/public/rt/sms-login',
      data,
    }),

  /** 5. 退出登录 */
  logout: (data?: LogoutRequest) =>
    request<void>({
      method: 'POST',
      url: '/common/account/logout',
      data: data ?? {},
    }),

  /** 6. 改密 */
  changePassword: (data: ChangePasswordRequest) =>
    request<void>({
      method: 'POST',
      url: '/common/account/change-password',
      data,
    }),

  /** 7. 找回密码 */
  resetPassword: (data: ResetPasswordRequest) =>
    request<void>({
      method: 'POST',
      url: '/public/account/reset-password',
      data,
    }),

  /** 8. 换绑手机号 */
  changePhone: (data: ChangePhoneRequest) =>
    request<void>({
      method: 'POST',
      url: '/common/account/change-phone',
      data,
    }),

  /** 9. 我的资料 */
  profile: () =>
    request<UserProfile>({
      method: 'GET',
      url: '/common/account/profile',
    }),

  /** 10. 修改资料 */
  updateProfile: (data: UpdateProfileRequest) =>
    request<UserProfile>({
      method: 'PATCH',
      url: '/common/account/profile',
      data,
    }),

  /** 11. 我的所有角色 */
  myRoles: () =>
    request<MyRolesResponse>({
      method: 'GET',
      url: '/common/account/roles',
    }),

  /** 12. 切换主操作角色 */
  switchRole: (data: SwitchRoleRequest) =>
    request<SwitchRoleResponse>({
      method: 'POST',
      url: '/common/account/switch-role',
      data,
    }),
}
