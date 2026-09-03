/**
 * WK 库管 API（F5-W1 · 出入库作业）。
 * 端点逐项对照后端 Controller（据实查证 2026-09-03）：
 *  - InboundController：GET/POST /tenant/inbound、/{id}/accept|reject|register|print
 *  - TenantOutboundController：GET /tenant/outbound-requests（分页）、/{id}/print|revert-to-pending|register|
 *      confirm-withdraw|reject-withdraw、POST /tenant/wk/outbound-requests（代建直达 COMPLETED）
 *  - WholesalerController：GET /tenant/wholesalers（WK 同租户可读）
 *  - SkuController：GET /tenant/skus?wholesalerId=（P3b 只读放宽 WK/WA/TA）
 *  - InventoryController：GET /tenant/inventories（WK 代建大额判定用）
 *  - BatchController：GET /wholesaler/tenants/{tenantId}/batch-config（该租户任一 ACTIVE 成员可读）
 * 鉴权由 request.ts 自动注入 Authorization + X-Tenant-Id（当前工作仓）。
 */
import { request } from '../utils/request'
import type {
  InboundForwardRegisterRequest,
  InboundRejectReason,
  InboundRejectRequest,
  InboundRequest,
  InventoryItem,
  MpPage,
  OutboundRequest,
  OutboundRegisterRequest,
  Sku,
  SnowflakeId,
  TenantBatchConfig,
  Wholesaler,
  WkOutboundCreateRequest,
} from '@cangchu/api-types'
import type { LoginResponse } from '@cangchu/api-types'

export interface WkAuthInfo {
  roleEntry: LoginResponse['roles'][number]
}

const outboundApi = {
  list(status?: string, page = 1, size = 20): Promise<MpPage<OutboundRequest>> {
    return request<MpPage<OutboundRequest>>({
      url: '/tenant/outbound-requests',
      params: { status, page, size },
    })
  },
  print(id: SnowflakeId): Promise<OutboundRequest> {
    return request<OutboundRequest>({ url: `/tenant/outbound-requests/${id}/print`, method: 'POST' })
  },
  revertToPending(id: SnowflakeId): Promise<OutboundRequest> {
    return request<OutboundRequest>({ url: `/tenant/outbound-requests/${id}/revert-to-pending`, method: 'POST' })
  },
  register(id: SnowflakeId, dto?: OutboundRegisterRequest): Promise<OutboundRequest> {
    return request<OutboundRequest>({
      url: `/tenant/outbound-requests/${id}/register`,
      method: 'POST',
      data: dto ?? {},
    })
  },
  confirmWithdraw(id: SnowflakeId): Promise<OutboundRequest> {
    return request<OutboundRequest>({ url: `/tenant/outbound-requests/${id}/confirm-withdraw`, method: 'POST' })
  },
  rejectWithdraw(id: SnowflakeId): Promise<OutboundRequest> {
    return request<OutboundRequest>({ url: `/tenant/outbound-requests/${id}/reject-withdraw`, method: 'POST' })
  },
  createByWk(dto: WkOutboundCreateRequest): Promise<OutboundRequest> {
    return request<OutboundRequest>({ url: '/tenant/wk/outbound-requests', method: 'POST', data: dto })
  },
}

const inboundApi = {
  /** 本租户入库单（可选 status 过滤；非分页） */
  list(status?: string): Promise<InboundRequest[]> {
    return request<InboundRequest[]>({ url: '/tenant/inbound', params: { status } })
  },
  accept(id: SnowflakeId): Promise<InboundRequest> {
    return request<InboundRequest>({ url: `/tenant/inbound/${id}/accept`, method: 'POST' })
  },
  reject(id: SnowflakeId, dto: InboundRejectRequest): Promise<InboundRequest> {
    return request<InboundRequest>({ url: `/tenant/inbound/${id}/reject`, method: 'POST', data: dto })
  },
  registerForward(id: SnowflakeId, dto: InboundForwardRegisterRequest): Promise<InboundRequest> {
    return request<InboundRequest>({ url: `/tenant/inbound/${id}/register`, method: 'POST', data: dto })
  },
  print(id: SnowflakeId): Promise<InboundRequest> {
    return request<InboundRequest>({ url: `/tenant/inbound/${id}/print`, method: 'POST' })
  },
}

export const wkApi = {
  inbound: inboundApi,
  outbound: outboundApi,

  /** 本租户商户列表（选货/名称映射） */
  listWholesalers(): Promise<Wholesaler[]> {
    return request<Wholesaler[]>({ url: '/tenant/wholesalers' })
  },

  /** 商户全部 SKU（WK 只读放宽，含下架，名称映射/选货） */
  listSkuByWholesaler(wholesalerId: SnowflakeId): Promise<Sku[]> {
    return request<Sku[]>({ url: '/tenant/skus', params: { wholesalerId: String(wholesalerId) } })
  },

  /** 库存查询（大额代建判定：onhand） */
  listInventories(wholesalerId?: SnowflakeId, skuId?: SnowflakeId): Promise<InventoryItem[]> {
    return request<InventoryItem[]>({
      url: '/tenant/inventories',
      params: { wholesalerId: wholesalerId ? String(wholesalerId) : undefined, skuId: skuId ? String(skuId) : undefined },
    })
  },

  /** 租户批次/货位配置（显隐批次字段与货位必填） */
  batchConfig(tenantId: SnowflakeId): Promise<TenantBatchConfig> {
    return request<TenantBatchConfig>({ url: `/wholesaler/tenants/${tenantId}/batch-config` })
  },
}

export const rejectReasonOptions: Array<{ value: InboundRejectReason; label: string }> = [
  { value: 'QTY', label: '数量不符' },
  { value: 'QUALITY', label: '质量破损' },
  { value: 'BATCH', label: '批次效期' },
  { value: 'OTHER', label: '其他原因' },
]
