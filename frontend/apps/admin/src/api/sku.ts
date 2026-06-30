/**
 * 商品 SKU 接口封装（admin · TA/WA 端）
 *
 * 权威来源：backend/.../product/controller/SkuController.java
 *  - POST /api/v1/tenant/skus?wholesalerId=     创建（wholesalerId 走 query，非 body）
 *  - PUT  /api/v1/tenant/skus/{id}              修改
 *  - PUT  /api/v1/tenant/skus/{id}/listing?on=  上下架（on=true 上架 / false 下架）
 *  - GET  /api/v1/tenant/skus?wholesalerId=     商户看自己的 SKU（含下架）
 *
 * 均需 TA/WA 登录态；归属在 service 内以 user_roles 推导，前端只传 wholesalerId。
 * 雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type { Sku, CreateSkuRequest, UpdateSkuRequest } from '@cangchu/api-types'

export const skuApi = {
  /** 列出指定商户的 SKU（含下架） */
  list: (wholesalerId: string) =>
    request<Sku[]>({
      method: 'GET',
      url: '/tenant/skus',
      params: { wholesalerId },
    }),

  /** 创建 SKU（wholesalerId 走 query） */
  create: (wholesalerId: string, data: CreateSkuRequest) =>
    request<Sku>({
      method: 'POST',
      url: '/tenant/skus',
      params: { wholesalerId },
      data,
    }),

  /** 修改 SKU */
  update: (id: string, data: UpdateSkuRequest) =>
    request<Sku>({
      method: 'PUT',
      url: `/tenant/skus/${id}`,
      data,
    }),

  /** 上下架（on=true 上架 / false 下架） */
  toggleListing: (id: string, on: boolean) =>
    request<Sku>({
      method: 'PUT',
      url: `/tenant/skus/${id}/listing`,
      params: { on },
    }),
}
