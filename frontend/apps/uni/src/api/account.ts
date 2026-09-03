/**
 * 账号 API（WA/WE 登录用；密码登录为批发商端唯一入口，员工密码由店主在电脑端分配）。
 * device 统一记 H5（uni H5/小程序共用），后端不校验、仅会话审计。
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
}
