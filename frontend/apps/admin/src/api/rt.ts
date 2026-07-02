/**
 * RT 扫码进店 + 提交询价接口封装（admin · RT H5 公开端点）
 *
 * 权威来源：
 *  - GET  /api/v1/rt/store    backend/.../storefront/controller/RtStoreController.java（公开只读）
 *  - POST /api/v1/rt/inquiry  backend/.../document/controller/RtInquiryController.java（公开提交）
 *
 * 均为公开端点（不在 SaInterceptor include），无需登录态。tenantId 由后端以
 * storeId/code→store→tenant 解析推导，前端不传（防跨店，G-2.1）。
 * 雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type { RtStoreFront, Inquiry, SubmitInquiryRequest } from '@cangchu/api-types'

export const rtApi = {
  /** 进店页：店铺 + 店内 ACTIVE 批发商 + 各自在售 SKU（含公开价 + 库存）。code = 租户简码。 */
  getStore: (code: string) =>
    request<RtStoreFront>({
      method: 'GET',
      url: '/rt/store',
      params: { code },
    }),

  /** RT 提交询价 → 返回新建询价单（status=PENDING，含 docNo 单号）。 */
  submitInquiry: (data: SubmitInquiryRequest) =>
    request<Inquiry>({
      method: 'POST',
      url: '/rt/inquiry',
      data,
    }),
}
