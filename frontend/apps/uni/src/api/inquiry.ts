/**
 * 询价单 API（WA/WE 视角，X-Tenant-Id 定位本仓）。
 * 端点路径与 admin apps/admin/src/api/inquiry.ts 一致。
 */
import { request } from '../utils/request'
import type { ConfirmInquiryRequest, CustomerPriceVo, Inquiry, SnowflakeId } from '@cangchu/api-types'

export const inquiryApi = {
  /** 本仓询价单列表（createdAt 倒序） */
  list(): Promise<Inquiry[]> {
    return request<Inquiry[]>({ url: '/tenant/inquiry' })
  },

  /** 确认（可议价 + 沉淀专属价；省略 body = 旧行为：成交价=公开价快照，不沉淀） */
  confirm(id: SnowflakeId, body?: ConfirmInquiryRequest): Promise<Inquiry> {
    return request<Inquiry>({
      url: `/tenant/inquiry/${encodeURIComponent(String(id))}/confirm`,
      method: 'POST',
      data: body ?? {},
    })
  },

  /** 该批发商全部客户专属价（确认弹层「将覆盖」提示用；与 admin pricingApi 同端点） */
  listCustomerPrices(wholesalerId: SnowflakeId): Promise<CustomerPriceVo[]> {
    return request<CustomerPriceVo[]>({
      url: '/tenant/customer-prices',
      params: { wholesalerId: String(wholesalerId) },
    })
  },
}
