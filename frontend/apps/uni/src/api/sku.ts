/**
 * 我的商品（SKU）API（WA/WE）。
 * 端点路径与 admin apps/admin/src/api/sku.ts 一致；本仓经 X-Tenant-Id + wholesalerId 双定位。
 */
import { request } from '../utils/request'
import type { Sku, SnowflakeId } from '@cangchu/api-types'

export const skuApi = {
  /** 本批发商全部 SKU（含下架，上下架管理用） */
  listMine(wholesalerId: SnowflakeId): Promise<Sku[]> {
    return request<Sku[]>({ url: '/tenant/skus', params: { wholesalerId: String(wholesalerId) } })
  },

  /** 上下架切换（PUT /tenant/skus/{id}/listing?on=） */
  toggleListing(id: SnowflakeId, on: boolean): Promise<Sku> {
    return request<Sku>({
      url: `/tenant/skus/${encodeURIComponent(String(id))}/listing`,
      method: 'PUT',
      params: { on: on ? 'true' : 'false' },
    })
  },
}
