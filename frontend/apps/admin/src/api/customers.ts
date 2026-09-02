/**
 * wa 客户跟进接口封装（C3 · US-WE-04 · architecture/24-p5-c-c3 §4，document 域）
 *
 * 权威来源：backend/.../document/controller/CustomerController.java
 *  - GET    /api/v1/tenant/customers                    客户列表（分页；仅打码号）
 *  - GET    /api/v1/tenant/customers/{key}/detail      客户详情（统计 + 备注 + 提醒）
 *  - PUT    /api/v1/tenant/customers/{key}/remark      备注覆盖（空串=清除，无提醒清档）
 *  - POST   /api/v1/tenant/customers/{key}/reminders   新建提醒（remindAt 须晚于 now → 50841）
 *  - DELETE /api/v1/tenant/customers/{key}/reminders/{rid}  删除提醒
 *  - 查全号：GET /pii/phone-reveal?biz=INQUIRY&id={lastInquiryId}（api/pii.ts 既有）
 * customerKey = URL-safe Base64(hmac)，wholesalerId 回传供收敛校验；越权一律 50840。
 */

import { request } from './http'
import type {
  AddCustomerReminderRequest,
  MpPage,
  SaveCustomerRemarkRequest,
  WaCustomer,
  WaCustomerDetail,
  WaFollowupReminder,
} from '@cangchu/api-types'

export const customerApi = {
  /** 客户列表（当前租户 + 登录人归属商户；按最近询价倒序） */
  list: (params: { page?: number; size?: number } = {}) =>
    request<MpPage<WaCustomer>>({
      method: 'GET',
      url: '/tenant/customers',
      params: { page: params.page ?? 1, size: params.size ?? 20 },
    }),

  /** 客户详情（统计 + 档案备注 + 全部提醒） */
  detail: (customerKey: string, wholesalerId: string) =>
    request<WaCustomerDetail>({
      method: 'GET',
      url: `/tenant/customers/${customerKey}/detail`,
      params: { wholesalerId },
    }),

  /** 备注覆盖保存（空串=清除备注，无提醒时清档） */
  saveRemark: (customerKey: string, data: SaveCustomerRemarkRequest) =>
    request<void>({
      method: 'PUT',
      url: `/tenant/customers/${customerKey}/remark`,
      data,
    }),

  /** 新建跟进提醒（到点站内信给创建人） */
  addReminder: (customerKey: string, data: AddCustomerReminderRequest) =>
    request<WaFollowupReminder>({
      method: 'POST',
      url: `/tenant/customers/${customerKey}/reminders`,
      data,
    }),

  /** 删除跟进提醒 */
  deleteReminder: (customerKey: string, wholesalerId: string, reminderId: string) =>
    request<void>({
      method: 'DELETE',
      url: `/tenant/customers/${customerKey}/reminders/${reminderId}`,
      params: { wholesalerId },
    }),
}
