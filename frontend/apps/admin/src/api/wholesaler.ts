/**
 * 批发商商户接口封装（admin · TA 端）
 *
 * 权威来源：backend/.../tenant/controller/WholesalerController.java
 *  - POST /tenant/wholesalers       创建（TA 自营建商户）
 *  - PUT  /tenant/wholesalers/{id}  改资料（intro / license）
 *  - GET  /tenant/wholesalers       列出本租户商户
 *
 * 均需 TA 登录态；tenantId 由后端登录态推导，前端不传。
 */

import { request } from './http'
import type {
  Wholesaler,
  CreateWholesalerRequest,
  UpdateWholesalerRequest,
} from '@cangchu/api-types'

export const wholesalerApi = {
  /** 列出本租户商户 */
  list: () =>
    request<Wholesaler[]>({ method: 'GET', url: '/tenant/wholesalers' }),

  /** TA 自营创建批发商商户 */
  create: (data: CreateWholesalerRequest) =>
    request<Wholesaler>({ method: 'POST', url: '/tenant/wholesalers', data }),

  /** 修改商户资料（intro / license） */
  update: (id: string, data: UpdateWholesalerRequest) =>
    request<Wholesaler>({ method: 'PUT', url: `/tenant/wholesalers/${id}`, data }),
}
