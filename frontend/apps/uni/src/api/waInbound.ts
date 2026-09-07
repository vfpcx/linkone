// WA/WE 入库确认（US-WA-09 移动化，代建入库 72h 确认/异议）。
// 端点与 admin apps/admin/src/api/waInbound.ts 同一组 /wholesaler/inbound-requests
// （后端 WholesalerInboundController；鉴权由 request.ts 自动注入 Authorization + X-Tenant-Id）。
import { request } from '../utils/request'
import type {
  InboundDisputeRequest,
  InboundDisputeResult,
  InboundRequest,
  InboundStockPreview,
  MpPage,
  SnowflakeId,
} from '@cangchu/api-types'

export const waInboundApi = {
  /** 入库单据列表（待确认 tab：status=PENDING_WA_CONFIRM 按 72h 倒计时升序；全部 tab 不分 status） */
  list(status: string | undefined, page: number, size: number): Promise<MpPage<InboundRequest>> {
    return request<MpPage<InboundRequest>>({
      url: '/wholesaler/inbound-requests',
      params: { status, page, size },
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
