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
  Batch,
  BatchBackfillRequest,
  BatchList,
  BatchLocationLog,
  BatchLocationUpdateRequest,
  CountSheet,
  CountSheetCreateRequest,
  CountSheetUpdateRequest,
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
  StocktakeInTransitHint,
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

const batchApi = {
  /** 批次列表（wholesalerId+skuId 齐时附无批次在池量；status 可选过滤） */
  list(params?: { wholesalerId?: SnowflakeId; skuId?: SnowflakeId; status?: string }): Promise<BatchList> {
    return request<BatchList>({
      url: '/tenant/batches',
      params: {
        wholesalerId: params?.wholesalerId ? String(params.wholesalerId) : undefined,
        skuId: params?.skuId ? String(params.skuId) : undefined,
        status: params?.status,
      },
    })
  },
  /** 默认批次补录生产/到效期（仅 source=DEFAULT 且未终态） */
  backfill(id: SnowflakeId, dto: BatchBackfillRequest): Promise<Batch> {
    return request<Batch>({ url: `/tenant/batches/${id}`, method: 'PUT', data: dto })
  },
  /** 批次移库（location null=清空；同值幂等空转） */
  updateLocation(id: SnowflakeId, dto: BatchLocationUpdateRequest): Promise<Batch> {
    return request<Batch>({ url: `/tenant/batches/${id}/location`, method: 'PUT', data: dto ?? {} })
  },
  /** 移库变更记录（分页，size ≤50） */
  locationLogs(id: SnowflakeId, page = 1, size = 20): Promise<MpPage<BatchLocationLog>> {
    return request<MpPage<BatchLocationLog>>({
      url: `/tenant/batches/${id}/location-logs`,
      params: { page, size },
    })
  },
}

const expiryApi = {
  /** 临期预警（EXPIRING ∪ PENDING_CLEARANCE，剩余天数升序） */
  list(): Promise<BatchList> {
    return request<BatchList>({ url: '/tenant/batches/expiring' })
  },
  /** WK 一键通知商户（同批次 24h 限 1 → 50367） */
  notify(id: SnowflakeId): Promise<void> {
    return request<void>({ url: `/tenant/batches/${id}/notify-wholesaler`, method: 'POST' })
  },
}

const stocktakeApi = {
  /** 盘点单列表（status 可选：DRAFT/PENDING_APPROVAL/APPROVED/REJECTED） */
  list(status?: string): Promise<CountSheet[]> {
    return request<CountSheet[]>({ url: '/tenant/count-sheets', params: { status } })
  },
  /** 详情（含明细 + 在途提示条；名称已填充） */
  detail(id: SnowflakeId): Promise<CountSheet> {
    return request<CountSheet>({ url: `/tenant/count-sheets/${id}` })
  },
  /** 在途提示条（盘点页护栏） */
  inTransitHint(wholesalerId: SnowflakeId): Promise<StocktakeInTransitHint> {
    return request<StocktakeInTransitHint>({
      url: '/tenant/count-sheets/in-transit-hint',
      params: { wholesalerId: String(wholesalerId) },
    })
  },
  /** 建草稿（同商户在途至多一张 → 50356；items 非空 ≤200 行 50355） */
  create(dto: CountSheetCreateRequest): Promise<CountSheet> {
    return request<CountSheet>({ url: '/tenant/count-sheets', method: 'POST', data: dto })
  },
  /** 编辑草稿 / 驳回重提（DRAFT 直改、REJECTED 回 DRAFT；items 全量替换） */
  update(id: SnowflakeId, dto: CountSheetUpdateRequest): Promise<CountSheet> {
    return request<CountSheet>({ url: `/tenant/count-sheets/${id}`, method: 'PUT', data: dto })
  },
  /** 删除草稿（仅 DRAFT） */
  remove(id: SnowflakeId): Promise<void> {
    return request<void>({ url: `/tenant/count-sheets/${id}`, method: 'DELETE' })
  },
  /** 提交（CAS DRAFT→PENDING_APPROVAL，systemQty 快照定格 → 通知 TA） */
  submit(id: SnowflakeId): Promise<CountSheet> {
    return request<CountSheet>({ url: `/tenant/count-sheets/${id}/submit`, method: 'POST' })
  },
}

export const wkApi = {
  inbound: inboundApi,
  outbound: outboundApi,
  batch: batchApi,
  expiry: expiryApi,
  stocktake: stocktakeApi,

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
