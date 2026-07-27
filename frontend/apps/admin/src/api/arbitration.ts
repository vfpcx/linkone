/**
 * TA 仲裁中心接口封装（P3 FE-W1）
 *
 * 权威来源：backend/.../document/controller/TenantArbitrationController.java
 *  - GET  /api/v1/tenant/arbitrations?bizType=&status=&page=&size=
 *      本店仲裁列表（审批中心角标 = status=PENDING 的 total）
 *  - POST /api/v1/tenant/arbitrations/{id}/decide
 *      裁决（入库：APPROVED=通过·恢复流水 / REJECTED=驳回·保留冲销；
 *      liability 三态校验 50342，REJECTED 缺 remark → 50333）
 * TA 角色鉴权在后端 Service 内；前端只带登录态。
 */

import { request } from './http'
import type {
  Arbitration,
  ArbitrationDecideRequest,
  OpsArbitrationDecideRequest,
  MpPage,
} from '@cangchu/api-types'

export const arbitrationApi = {
  /** 本店仲裁列表（bizType/status 可选过滤） */
  list: (
    params: { bizType?: string; status?: string; page?: number; size?: number } = {},
  ) =>
    request<MpPage<Arbitration>>({
      method: 'GET',
      url: '/tenant/arbitrations',
      params: {
        ...(params.bizType ? { bizType: params.bizType } : {}),
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** 裁决 */
  decide: (id: string, data: ArbitrationDecideRequest) =>
    request<Arbitration>({
      method: 'POST',
      url: `/tenant/arbitrations/${id}/decide`,
      data,
    }),
}

/**
 * OPS 客诉仲裁（P3 FE-W2）
 *
 * 权威来源：backend/.../document/controller/OpsArbitrationController.java
 *  - GET  /api/v1/ops/arbitrations?bizType=&status=&page=&size=
 *      跨租户客诉仲裁列表（bizType 仅 OUTBOUND_COMPLAINT；角标 = PENDING total）
 *  - POST /api/v1/ops/arbitrations/{id}/decide
 *      裁决（结论四选 WK_LIABLE/WA_LIABLE/NEGOTIATED/NO_LIABILITY，remark 必填 50333；
 *      liability 必空 50342；并发双裁被抢占 50334）；仅判责不动库存/账单（D43）。
 */
export const opsArbitrationApi = {
  /** 客诉仲裁列表（status 可选过滤） */
  list: (
    params: { bizType?: string; status?: string; page?: number; size?: number } = {},
  ) =>
    request<MpPage<Arbitration>>({
      method: 'GET',
      url: '/ops/arbitrations',
      params: {
        ...(params.bizType ? { bizType: params.bizType } : {}),
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** 裁决（四选结论 + 备注必填） */
  decide: (id: string, data: OpsArbitrationDecideRequest) =>
    request<Arbitration>({
      method: 'POST',
      url: `/ops/arbitrations/${id}/decide`,
      data,
    }),
}
