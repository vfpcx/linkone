// WA/WE 入库确认（US-WA-09 移动化，代建入库 72h 确认/异议）+ 正向申请链（F7-4 我的申请移动化）。
// 端点与 admin apps/admin/src/api/waInbound.ts 同一组 /wholesaler/inbound-requests
// （后端 WholesalerInboundController；鉴权由 request.ts 自动注入 Authorization + X-Tenant-Id）。
import { request } from '../utils/request'
import type {
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundRequest,
  InboundStockPreview,
  InboundSubmitRequest,
  InboundWithdrawRequest,
  MpPage,
  SnowflakeId,
} from '@cangchu/api-types'

export const waInboundApi = {
  /** 入库单据列表（status/source 可选过滤；status=PENDING_WA_CONFIRM 后端按 72h 倒计时升序，
   *  其余按创建时间倒序；source=WA_SUBMIT=我的申请 / WK_CREATED=仓库代建） */
  list(params: { status?: string; source?: string; page?: number; size?: number } = {}): Promise<MpPage<InboundRequest>> {
    return request<MpPage<InboundRequest>>({
      url: '/wholesaler/inbound-requests',
      params: {
        status: params.status,
        source: params.source,
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    })
  },

  /** 提交入库申请（P3b T1 · D-5 多行拆 N 单共享 batchSubmitId；全程零库存零计费，登记才加库存） */
  submit(data: InboundSubmitRequest): Promise<InboundRequest[]> {
    return request<InboundRequest[]>({
      url: '/wholesaler/inbound-requests',
      method: 'POST',
      data,
    })
  },

  /** 撤回自己提交的申请（R1 · 仅 SUBMITTED 可撤，50350；理由必填 ≤100） */
  withdraw(id: SnowflakeId, data: InboundWithdrawRequest): Promise<InboundRequest> {
    return request<InboundRequest>({
      url: `/wholesaler/inbound-requests/${encodeURIComponent(String(id))}/withdraw`,
      method: 'POST',
      data,
    })
  },

  /** 确认入库（默认接受，后端含自动确认同口径 50331/50332 二次校验） */
  confirm(id: SnowflakeId): Promise<InboundRequest> {
    return request<InboundRequest>({
      url: `/wholesaler/inbound-requests/${encodeURIComponent(String(id))}/confirm`,
      method: 'POST',
      data: {},
    })
  },

  /** 实时库存预估（异议填写时展示 在库/冲销/差额 三数字） */
  stockPreview(id: SnowflakeId): Promise<InboundStockPreview> {
    return request<InboundStockPreview>({
      url: `/wholesaler/inbound-requests/${encodeURIComponent(String(id))}/stock-preview`,
    })
  },

  /** 提出异议（触发冲销并通知 TA 仲裁；附件 ≤5 先经 uploadImage 得 URL 再提交） */
  dispute(id: SnowflakeId, body: InboundDisputeRequest): Promise<InboundDisputeResult> {
    return request<InboundDisputeResult>({
      url: `/wholesaler/inbound-requests/${encodeURIComponent(String(id))}/dispute`,
      method: 'POST',
      data: body,
    })
  },
}
