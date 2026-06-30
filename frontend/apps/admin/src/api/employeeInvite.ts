/**
 * 员工注册码接口封装（admin · TA 端）
 *
 * 权威来源：backend EmployeeInviteController（phase-1 已上线）
 *  - POST   /tenant/employee-invites      生码（TA 登录态）
 *  - GET    /tenant/employee-invites      列表（倒序）
 *  - DELETE /tenant/employee-invites/{id} 作废
 *
 * 均需 TA 登录态；tenantId 由后端登录态推导，前端不传。
 */

import { request } from './http'
import type {
  EmployeeInvite,
  CreateEmployeeInviteRequest,
} from '@cangchu/api-types'

export const employeeInviteApi = {
  /** 列出本租户员工注册码（倒序） */
  list: () =>
    request<EmployeeInvite[]>({ method: 'GET', url: '/tenant/employee-invites' }),

  /** 生成员工注册码（role + maxUses + expiresInDays） */
  create: (data: CreateEmployeeInviteRequest) =>
    request<EmployeeInvite>({
      method: 'POST',
      url: '/tenant/employee-invites',
      data,
    }),

  /** 作废注册码 */
  revoke: (id: string) =>
    request<void>({ method: 'DELETE', url: `/tenant/employee-invites/${id}` }),
}
