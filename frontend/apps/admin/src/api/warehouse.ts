/**
 * 老板多仓接口封装（admin 端）
 *
 * 后端契约（TenantController，已上线）：
 *  - POST /api/v1/tenant/warehouses  已登录 TA 新建一个仓 → { tenantId, simpleCode, status }
 *  - GET  /api/v1/tenant/warehouses  名下所有仓（顶栏切换器）→ Warehouse[]
 * tenantId 由后端登录态推导，前端不传（G-2.1）。
 */

import { request } from './http'
import type {
  Warehouse,
  CreateWarehouseRequest,
  CreateWarehouseResponse,
} from '@cangchu/api-types'

export const warehouseApi = {
  /** 名下所有仓（顶栏切换器） */
  list: () => request<Warehouse[]>({ method: 'GET', url: '/tenant/warehouses' }),

  /** 已登录 TA 新建一个仓（name + contactPhone 必填） */
  create: (data: CreateWarehouseRequest) =>
    request<CreateWarehouseResponse>({
      method: 'POST',
      url: '/tenant/warehouses',
      data,
    }),
}
