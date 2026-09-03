/**
 * 客户跟进 API（WA/WE · US-WE-04）。
 * 端点路径与 admin apps/admin/src/api/customers.ts 一致。
 */
import { request } from '../utils/request'
import type {
  AddCustomerReminderRequest,
  MpPage,
  SaveCustomerRemarkRequest,
  WaCustomer,
  WaCustomerDetail,
  WaFollowupReminder,
} from '@cangchu/api-types'

export const customerApi = {
  list(params: { page: number; size: number }): Promise<MpPage<WaCustomer>> {
    return request<MpPage<WaCustomer>>({
      url: '/tenant/customers',
      params: { page: params.page, size: params.size },
    })
  },

  detail(customerKey: string, wholesalerId: string): Promise<WaCustomerDetail> {
    return request<WaCustomerDetail>({
      url: `/tenant/customers/${encodeURIComponent(customerKey)}/detail`,
      params: { wholesalerId },
    })
  },

  saveRemark(customerKey: string, body: SaveCustomerRemarkRequest): Promise<void> {
    return request<void>({
      url: `/tenant/customers/${encodeURIComponent(customerKey)}/remark`,
      method: 'PUT',
      data: body,
    })
  },

  addReminder(customerKey: string, body: AddCustomerReminderRequest): Promise<WaFollowupReminder> {
    return request<WaFollowupReminder>({
      url: `/tenant/customers/${encodeURIComponent(customerKey)}/reminders`,
      method: 'POST',
      data: body,
    })
  },

  deleteReminder(customerKey: string, wholesalerId: string, reminderId: string): Promise<void> {
    return request<void>({
      url: `/tenant/customers/${encodeURIComponent(customerKey)}/reminders/${encodeURIComponent(reminderId)}`,
      method: 'DELETE',
      params: { wholesalerId },
    })
  },
}
