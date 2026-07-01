/**
 * WA 询价确认接口封装（admin · WA 端）
 *
 * 权威来源：backend/.../document/controller/InquiryController.java + InquiryServiceImpl
 *  - GET  /api/v1/tenant/inquiry               WA 列出本人归属 wholesaler 的询价单（登录态 WA）
 *  - POST /api/v1/tenant/inquiry/{id}/confirm  WA 确认 → 状态机 PENDING→CONFIRMED→COMPLETED，
 *          单事务内建出库单 + 扣库存（库存不足整体回滚，仍 PENDING）。错误码 50280-50287 / 50251。
 *
 * WA 归属 / tenantId 均在 service 内以 user_roles 登录态推导，前端不传（G-2.1）。
 * 雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type { Inquiry } from '@cangchu/api-types'

export const inquiryApi = {
  /** WA 列出本人归属 wholesaler 的询价单（后端按 createdAt 倒序） */
  list: () =>
    request<Inquiry[]>({
      method: 'GET',
      url: '/tenant/inquiry',
    }),

  /** WA 确认询价 → 自动转出库扣库存；返回更新后的单（COMPLETED） */
  confirm: (id: string) =>
    request<Inquiry>({
      method: 'POST',
      url: `/tenant/inquiry/${id}/confirm`,
    }),
}
