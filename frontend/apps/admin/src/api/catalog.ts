/**
 * 平台标品目录（只读，登录态可见——非 OPS 专属；P5-D D56 22 §3.1）
 *
 * 权威来源：backend/.../product/controller/CatalogSpuController.java
 *  - GET /api/v1/catalog/spus?page=&size=&keyword=   仅 ACTIVE，名称/编码模糊
 *
 * 用途：TA/WA 建 SKU「选择标品」（OPS 端点 requireOps，TA 调 42002，故走本只读目录）。
 */

import { request } from './http'
import type { PageRecords, Spu } from '@cangchu/api-types'

export const catalogApi = {
  /** 标品目录搜索（仅 ACTIVE，雪花 ID 为 string） */
  searchSpus: (params: { page?: number; size?: number; keyword?: string }) =>
    request<PageRecords<Spu>>({ method: 'GET', url: '/catalog/spus', params }),
}
