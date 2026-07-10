/**
 * 价格管理接口封装（admin · TA 端 · P2）
 *
 * 权威来源（backend Waves 1&2 已完成）：
 *  - 客户专属价：CustomerPriceController
 *      GET    /tenant/customer-prices?wholesalerId=          列出
 *      POST   /tenant/customer-prices                        设置
 *      PATCH  /tenant/customer-prices/{id}                   编辑
 *      DELETE /tenant/customer-prices/{id}                   撤销（→ DISABLED）
 *  - 批量调价：
 *      POST   /tenant/skus/batch-price-update                公开价批量（skuIds 1..200）
 *      POST   /tenant/customer-prices/batch-update           专属价批量（ids 1..500）
 *  - 调价历史：
 *      GET    /tenant/price-change-logs?wholesalerId=&changeType=
 *
 * 均需 TA 登录态；tenantId 由后端登录态推导，前端只传 wholesalerId。
 * 雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type {
  CustomerPriceVo,
  SetCustomerPriceRequest,
  UpdateCustomerPriceRequest,
  BatchPublicPriceRequest,
  BatchCustomerPriceRequest,
  BatchPriceResultVo,
  PriceChangeLogVo,
  ChangeType,
} from '@cangchu/api-types'

export const pricingApi = {
  /** 列出指定商户的客户专属价 */
  listCustomerPrices: (wholesalerId: string) =>
    request<CustomerPriceVo[]>({
      method: 'GET',
      url: '/tenant/customer-prices',
      params: { wholesalerId },
    }),

  /** 设置客户专属价 */
  setCustomerPrice: (data: SetCustomerPriceRequest) =>
    request<CustomerPriceVo>({
      method: 'POST',
      url: '/tenant/customer-prices',
      data,
    }),

  /** 编辑客户专属价（单价 / 过期时间 / 状态） */
  updateCustomerPrice: (id: string, data: UpdateCustomerPriceRequest) =>
    request<CustomerPriceVo>({
      method: 'PATCH',
      url: `/tenant/customer-prices/${id}`,
      data,
    }),

  /** 撤销客户专属价（→ DISABLED） */
  revokeCustomerPrice: (id: string) =>
    request<void>({
      method: 'DELETE',
      url: `/tenant/customer-prices/${id}`,
    }),

  /** 公开价批量调价（skuIds 1..200） */
  batchPublicPrice: (data: BatchPublicPriceRequest) =>
    request<BatchPriceResultVo>({
      method: 'POST',
      url: '/tenant/skus/batch-price-update',
      data,
    }),

  /** 客户专属价批量更新（ids 1..500 / 或按 skuId·rtPhone 圈选） */
  batchCustomerPrice: (data: BatchCustomerPriceRequest) =>
    request<BatchPriceResultVo>({
      method: 'POST',
      url: '/tenant/customer-prices/batch-update',
      data,
    }),

  /** 列出调价历史（可按 changeType 过滤） */
  listChangeLogs: (wholesalerId: string, changeType?: ChangeType) =>
    request<PriceChangeLogVo[]>({
      method: 'GET',
      url: '/tenant/price-change-logs',
      params: { wholesalerId, changeType },
    }),
}
