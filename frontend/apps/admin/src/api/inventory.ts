/**
 * 库存查询接口封装（P3 FE-W2 · 出库作业辅助展示）
 *
 * 权威来源：backend/.../inventory/controller/InventoryController.java
 *  - GET /api/v1/tenant/inventories?wholesalerId=&skuId=   本租户库存（可选过滤）
 * 仅作展示/大额预警参考；真正扣减以后端 deductStock 锁内校验为准。
 */

import { request } from './http'
import type { InventoryItem } from '@cangchu/api-types'

export const inventoryApi = {
  /** 查询库存（wholesalerId / skuId 可选过滤） */
  query: (params: { wholesalerId?: string; skuId?: string } = {}) =>
    request<InventoryItem[]>({
      method: 'GET',
      url: '/tenant/inventories',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.skuId ? { skuId: params.skuId } : {}),
      },
    }),
}
