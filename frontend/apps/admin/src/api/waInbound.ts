/**
 * WA 入库确认链接口封装（P3 FE-W1）
 *
 * 权威来源：backend/.../document/controller/WholesalerInboundController.java
 *  - GET  /api/v1/wholesaler/inbound-requests?status=&page=&size=
 *      我的入库单队列（status=PENDING_WA_CONFIRM 时后端按 72h 倒计时升序）
 *  - POST /api/v1/wholesaler/inbound-requests/{id}/confirm   确认代建入库（CAS）
 *  - POST /api/v1/wholesaler/inbound-requests/{id}/dispute   异议（单事务：
 *      CAS→封顶冲销→建 YY- 仲裁单→通知 TA/WK）
 * 错误码：50331 CAS 冲突 / 50332 超窗已自动确认 / 40xxx 参数校验。
 * 归属（WA 本人或持 INBOUND_CONFIRM 的 WE）由后端登录态推导，前端不传归属参数。
 */

import { request } from './http'
import type {
  InboundRequest,
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundStockPreview,
  MpPage,
} from '@cangchu/api-types'

export const waInboundApi = {
  /** 我的入库单队列（status 可选过滤；PENDING_WA_CONFIRM 按倒计时升序） */
  list: (params: { status?: string; page?: number; size?: number } = {}) =>
    request<MpPage<InboundRequest>>({
      method: 'GET',
      url: '/wholesaler/inbound-requests',
      params: {
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** 异议前在库预览（M3）：实时在库/预计冲销/预计差额三数字（轻量快照，实际以锁内为准） */
  stockPreview: (id: string) =>
    request<InboundStockPreview>({
      method: 'GET',
      url: `/wholesaler/inbound-requests/${id}/stock-preview`,
    }),

  /** 确认代建入库 */
  confirm: (id: string) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/wholesaler/inbound-requests/${id}/confirm`,
    }),

  /** 异议（理由必填 ≤512、附件 URL ≤5） */
  dispute: (id: string, data: InboundDisputeRequest) =>
    request<InboundDisputeResult>({
      method: 'POST',
      url: `/wholesaler/inbound-requests/${id}/dispute`,
      data,
    }),
}
