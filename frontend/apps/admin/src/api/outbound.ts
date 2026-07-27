/**
 * 出库链接口封装（P3 FE-W2）
 *
 * 权威来源（据实查证）：
 *  - backend/.../document/controller/TenantOutboundController.java（WK/TA 作业侧）
 *      GET  /api/v1/tenant/outbound-requests?status=&page=&size=   作业列表
 *      POST /api/v1/tenant/outbound-requests/{id}/print            打印（首打迁 PRINTED / 补打 count++）
 *      POST /api/v1/tenant/outbound-requests/{id}/revert-to-pending 重新核对回退（清撤回 flag）
 *      POST /api/v1/tenant/outbound-requests/{id}/register         登记出库 PRINTED→COMPLETED
 *      POST /api/v1/tenant/outbound-requests/{id}/confirm-withdraw R4 确认撤回 PRINTED→CANCELLED+回补
 *      POST /api/v1/tenant/outbound-requests/{id}/reject-withdraw  R4 拒绝撤回（清 flag 继续履约）
 *      POST /api/v1/tenant/wk/outbound-requests                    WK 代建出库（直达 COMPLETED）
 *  - backend/.../document/controller/WholesalerOutboundController.java（WA 侧）
 *      POST /api/v1/wholesaler/outbound-requests                   手动出库申请（提交即扣）
 *      GET  /api/v1/wholesaler/outbound-requests?status=&source=&page=&size=
 *      POST /api/v1/wholesaler/outbound-requests/{id}/withdraw     R4 撤回（分状态两路）
 *      POST /api/v1/wholesaler/outbound-requests/{id}/complain     30 天客诉（仅代建已出库）
 *
 * 错误码：50330 状态不可达 / 50331 CAS 冲突 / 50335 不可撤回 / 50336 无撤回申请 /
 *        50338 大额需复述确认 / 50339 客诉超窗 / 50251 库存不足。
 * 归属鉴权（WA 归属 / WK 本租户）由后端登录态推导，前端不传归属参数。
 */

import { request } from './http'
import type {
  MpPage,
  OutboundRequest,
  OutboundSubmitRequest,
  WkOutboundCreateRequest,
  OutboundComplainRequest,
} from '@cangchu/api-types'

/** WK/TA 作业侧（/tenant/**） */
export const tenantOutboundApi = {
  /** 出库作业列表（status 可选过滤） */
  list: (params: { status?: string; page?: number; size?: number } = {}) =>
    request<MpPage<OutboundRequest>>({
      method: 'GET',
      url: '/tenant/outbound-requests',
      params: {
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** 打印（首打 PENDING_ACCEPT→PRINTED；补打 count++ 不迁移状态） */
  print: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/tenant/outbound-requests/${id}/print`,
    }),

  /** 重新核对回退 PRINTED→PENDING_ACCEPT（同时清撤回申请标记） */
  revertToPending: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/tenant/outbound-requests/${id}/revert-to-pending`,
    }),

  /** 登记出库 PRINTED→COMPLETED（+询价终态联动） */
  register: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/tenant/outbound-requests/${id}/register`,
    }),

  /** R4 确认撤回 PRINTED→CANCELLED + 库存回补（无撤回申请 → 50336） */
  confirmWithdraw: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/tenant/outbound-requests/${id}/confirm-withdraw`,
    }),

  /** R4 拒绝撤回：清 flag 通知商户，单据继续履约 */
  rejectWithdraw: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/tenant/outbound-requests/${id}/reject-withdraw`,
    }),

  /** WK 代建出库（confirmed=true 凭据；大额>在库50% 须复述件数，50338） */
  createByWk: (data: WkOutboundCreateRequest) =>
    request<OutboundRequest>({
      method: 'POST',
      url: '/tenant/wk/outbound-requests',
      data,
    }),
}

/** WA 侧（/wholesaler/**） */
export const waOutboundApi = {
  /** 手动出库申请（提交即扣库存，不足 50251 整体回滚） */
  submit: (data: OutboundSubmitRequest) =>
    request<OutboundRequest>({
      method: 'POST',
      url: '/wholesaler/outbound-requests',
      data,
    }),

  /** 我的出库单列表（status/source 可选过滤；「已确认（代建）」=source=WK_CREATED） */
  list: (
    params: { status?: string; source?: string; page?: number; size?: number } = {},
  ) =>
    request<MpPage<OutboundRequest>>({
      method: 'GET',
      url: '/wholesaler/outbound-requests',
      params: {
        ...(params.status ? { status: params.status } : {}),
        ...(params.source ? { source: params.source } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** R4 撤回（待受理直撤回补 / 已打印置 flag 待 WK 二次确认 / 已出库 50335） */
  withdraw: (id: string) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/wholesaler/outbound-requests/${id}/withdraw`,
    }),

  /** 30 天客诉（仅代建出库且已出库；超窗 50339）→ COMPLAINED + KS- 仲裁单 */
  complain: (id: string, data: OutboundComplainRequest) =>
    request<OutboundRequest>({
      method: 'POST',
      url: `/wholesaler/outbound-requests/${id}/complain`,
      data,
    }),
}
