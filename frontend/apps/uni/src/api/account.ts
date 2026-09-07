/**
 * 账号 API（WA/WE 登录用；F6 起 RT 免密验证码登录也走这里）。
 * device 统一记 H5（uni H5/小程序共用），后端不校验、仅会话审计。
 * RT 登录：POST /account/login/rt 为 query 参数（phone/code），首登自动建 RT 账号（D-49/D-50）。
 */
import { request } from '../utils/request'
import type { LoginResponse } from '@cangchu/api-types'

export const accountApi = {
  login(data: { phone: string; password?: string; deviceInfo?: string }): Promise<LoginResponse> {
    return request<LoginResponse>({
      url: '/account/login',
      method: 'POST',
      data: { ...data, device: 'H5' },
    })
  },
  logout(): Promise<{ ok: boolean }> {
    return request<{ ok: boolean }>({ url: '/account/logout', method: 'POST', data: {} })
  },
  /** 发送 RT 免密登录短信验证码（scene=RT_LOGIN；dev mock 固定 888888） */
  sendRtSmsCode(phone: string): Promise<{ ok?: boolean }> {
    return request<{ ok?: boolean }>({
      url: '/account/sms-code',
      method: 'POST',
      data: { phone, scene: 'RT_LOGIN' },
    })
  },
  /** RT 免密验证码登录 / 首次自动注册（后端 query 参数 phone/code，非 body） */
  rtLogin(data: { phone: string; code: string }): Promise<LoginResponse> {
    return request<LoginResponse>({
      url: '/account/login/rt',
      method: 'POST',
      params: data,
    })
  },
}
