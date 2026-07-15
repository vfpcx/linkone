/**
 * 批发商商户接口封装（admin · TA 端 + WA 端）
 *
 * 权威来源：backend/.../tenant/controller/WholesalerController.java
 *  - POST /tenant/wholesalers       创建（TA 自营建商户）
 *  - PUT  /tenant/wholesalers/{id}  改资料（intro / license）
 *  - GET  /tenant/wholesalers       列出本租户商户
 *
 * P2 入驻生态（Wave1 契约，后端 feat/p2-onboarding 并行开发中）：
 *  - POST /wholesaler/applications  WA 提交入驻申请
 *    错误码：50201 审核中（重复提交）/ 50204 重复入驻 / 50205 黑名单拦截
 *
 * 均需登录态；tenantId 由后端登录态推导，前端不传。
 */

import { request } from './http'
import type {
  Wholesaler,
  CreateWholesalerRequest,
  UpdateWholesalerRequest,
  SubmitWaApplicationRequest,
  SubmitWaApplicationResponse,
  WholesalerApplication,
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

/** WA 端 · 入驻申请（P2 入驻生态） */
export const waApplicationApi = {
  /** ✅ P2 · WA 提交入驻申请 */
  submit: (data: SubmitWaApplicationRequest) =>
    request<SubmitWaApplicationResponse>({
      method: 'POST',
      url: '/wholesaler/applications',
      data,
    }),

  /**
   * ⚠️ 契约微调位 · 查询本人入驻申请（含驳回理由）。
   * Wave1 契约未显式列出该端点；后端未提供时调用失败，
   * 页面回退到本地缓存的提交记录展示（Apply.vue 已做优雅降级）。
   */
  listMine: () =>
    request<WholesalerApplication[]>({
      method: 'GET',
      url: '/wholesaler/applications',
    }),
}
