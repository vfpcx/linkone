/**
 * WK 入库登记接口封装（admin · WK/TA 端）
 *
 * 权威来源：backend/.../document/controller/InboundController.java
 *  - POST /api/v1/tenant/inbound                  WK 登记入库（单事务：建单 + 增库存）
 *  - GET  /api/v1/tenant/inbound?wholesalerId=    列出本租户入库单（wholesalerId 可选过滤）
 *
 * 登记需该租户 WK 登录态；归属/tenantId 在 service 内以 user_roles 登录态 + wholesaler
 * 真实归属推导，前端只传 wholesalerId/skuId/qty/palletQty。
 * 雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type { InboundRequest, InboundRegisterRequest } from '@cangchu/api-types'

export const inboundApi = {
  /** WK 登记入库 */
  register: (data: InboundRegisterRequest) =>
    request<InboundRequest>({
      method: 'POST',
      url: '/tenant/inbound',
      data,
    }),

  /** 列出本租户入库单（wholesalerId 可选过滤） */
  list: (wholesalerId?: string) =>
    request<InboundRequest[]>({
      method: 'GET',
      url: '/tenant/inbound',
      params: wholesalerId ? { wholesalerId } : undefined,
    }),
}
