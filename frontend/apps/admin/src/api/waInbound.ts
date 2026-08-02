/**
 * WA 入库确认链 + 正向申请链接口封装（P3 FE-W1 / P3b T1-FE）
 *
 * 权威来源：backend/.../document/controller/WholesalerInboundController.java
 *  - GET  /api/v1/wholesaler/inbound-requests?status=&source=&page=&size=
 *      我的入库单队列（status=PENDING_WA_CONFIRM 时后端按 72h 倒计时升序；
 *      P3b T1 扩 source 过滤：WA_SUBMIT=我的申请 / WK_CREATED=代建）
 *  - POST /api/v1/wholesaler/inbound-requests            P3b T1 提交入库申请
 *      （多行拆 N 单共享 batchSubmitId，单事务任一行非法整批回滚；全程零库存）
 *  - POST /api/v1/wholesaler/inbound-requests/{id}/withdraw  R1 撤回（仅 SUBMITTED，50350）
 *  - POST /api/v1/wholesaler/inbound-requests/{id}/confirm   确认代建入库（CAS）
 *  - POST /api/v1/wholesaler/inbound-requests/{id}/dispute   异议（单事务：
 *      CAS→封顶冲销→建 YY- 仲裁单→通知 TA/WK）
 * 错误码：50331 CAS 冲突 / 50332 超窗已自动确认 / 50350 已受理不可撤 /
 *   50313 商户非营业（R14）/ 42004 WE 无 INBOUND_SUBMIT 授权位 / 40xxx 参数校验。
 * 归属（WA 本人或持 INBOUND_CONFIRM / INBOUND_SUBMIT 的 WE）由后端登录态推导，前端不传归属参数。
 */

import { request } from './http'
import type {
  InboundRequest,
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundStockPreview,
  InboundSubmitRequest,
  InboundWithdrawRequest,
  MpPage,
} from '@cangchu/api-types'

export const waInboundApi = {
  /** 我的入库单队列（status/source 可选过滤；PENDING_WA_CONFIRM 按倒计时升序，其余按创建时间倒序） */
  list: (params: { status?: string; source?: string; page?: number; size?: number } = {}) =>
    request<MpPage<InboundRequest>>({
      method: 'GET',
      url: '/wholesaler/inbound-requests',
      params: {
        ...(params.status ? { status: params.status } : {}),
        ...(params.source ? { source: params.source } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** P3b T1：提交入库申请（多行拆 N 单，返回全部新单；≤50 行） */
  submit: (data: InboundSubmitRequest) =>
    request<InboundRequest[]>({
      method: 'POST',
      url: '/wholesaler/inbound-requests',
      data,
    }),

  /** P3b T1 R1：撤回自己提交的申请（仅待受理；理由必填） */
  withdraw: (id: string, data: InboundWithdrawRequest) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/wholesaler/inbound-requests/${id}/withdraw`,
      data,
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
