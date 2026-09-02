/**
 * 批次登记簿 / 临期看板接口封装（P3b T4-FE）
 *
 * 权威来源（据实查证）：backend/.../inventory/controller/BatchController.java
 *  - POST /api/v1/tenant/settings/batch-toggle   TA 批次开关专用端点
 *      （{enable, confirmed:true}；24h≤2 → 50361；幂等空转不计次；缺凭据 40003）
 *  - GET  /api/v1/tenant/batches?wholesalerId=&skuId=&status=  WK/TA 批次列表/下钻
 *      （wholesalerId+skuId 齐时附「无批次在池量」unpooledQty，可负 UI 归 0）
 *  - GET  /api/v1/tenant/batches/expiring        WK/TA 预警列表
 *      （EXPIRING∪PENDING_CLEARANCE 剩余天数升序；行含 manualNotifiedAt）
 *  - POST /api/v1/tenant/batches/{id}/notify-wholesaler  WK 一键通知（24h 限 1 → 50367）
 *  - GET  /api/v1/tenant/batches/expiry-dashboard  TA 临期看板（四卡 + bySku）
 *  - PUT  /api/v1/tenant/batches/{id}            默认批次补录（仅 source=DEFAULT 且未终态；
 *      生产日期 ≤今天 40205 / 到效期 >生产日期 40206 / 50363 不泄漏存在性）
 *  - GET  /api/v1/wholesaler/batches?skuId=&status=  WA/WE 只读下钻
 *  - GET  /api/v1/wholesaler/tenants/{tenantId}/batch-config  批次配置只读（P3b 收口 L-1；
 *      该仓任一 ACTIVE 角色可读，越权 42001；供表单按开关隐藏批次三字段）
 *
 * 展示口径（PRD §3.3）：所有推算剩余旁标「推算值 · 截至今日 02:00」。
 */

import { request } from './http'
import type {
  Batch,
  BatchBackfillRequest,
  BatchList,
  BatchLocationLog,
  BatchLocationUpdateRequest,
  BatchToggleRequest,
  BatchToggleResult,
  ExpiryDashboard,
  MpPage,
  TenantBatchConfig,
} from '@cangchu/api-types'

export const batchApi = {
  /** TA 批次开关（真实翻转须 confirmed=true；24h ≤2 次 50361） */
  toggle: (data: BatchToggleRequest) =>
    request<BatchToggleResult>({
      method: 'POST',
      url: '/tenant/settings/batch-toggle',
      data,
    }),

  /** 租户侧批次列表/下钻（WK/TA） */
  list: (params: { wholesalerId?: string; skuId?: string; status?: string } = {}) =>
    request<BatchList>({
      method: 'GET',
      url: '/tenant/batches',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.skuId ? { skuId: params.skuId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),

  /** 临期预警列表（EXPIRING∪PENDING_CLEARANCE，剩余天数升序） */
  expiring: () =>
    request<BatchList>({
      method: 'GET',
      url: '/tenant/batches/expiring',
    }),

  /** WK 一键通知商户（同批次 24h 限 1 → 50367） */
  notifyWholesaler: (id: string) =>
    request<void>({
      method: 'POST',
      url: `/tenant/batches/${id}/notify-wholesaler`,
    }),

  /** TA 临期看板（四卡汇总 + bySku 分组，组间最近到效期升序） */
  expiryDashboard: () =>
    request<ExpiryDashboard>({
      method: 'GET',
      url: '/tenant/batches/expiry-dashboard',
    }),

  /** 默认批次补录生产/到效期（仅 source=DEFAULT 且非 CLEARED/CLOSED） */
  backfill: (id: string, data: BatchBackfillRequest) =>
    request<Batch>({
      method: 'PUT',
      url: `/tenant/batches/${id}`,
      data,
    }),

  /** 租户批次配置只读（该仓任一 ACTIVE 角色；P3b 收口 L-1） */
  config: (tenantId: string) =>
    request<TenantBatchConfig>({
      method: 'GET',
      url: `/wholesaler/tenants/${tenantId}/batch-config`,
    }),

  /** 批次移库（PUT /tenant/batches/{id}/location · P5-D C2：location null=清空货位；无差异幂等不落日志；批次不存在/跨租户 50363） */
  updateLocation: (id: string, data?: BatchLocationUpdateRequest) =>
    request<Batch>({
      method: 'PUT',
      url: `/tenant/batches/${id}/location`,
      data,
    }),

  /** 批次移库变更记录（GET /tenant/batches/{id}/location-logs · P5-D C2：倒序分页 ≤50/页） */
  locationLogs: (id: string, params: { page?: number; size?: number } = {}) =>
    request<MpPage<BatchLocationLog>>({
      method: 'GET',
      url: `/tenant/batches/${id}/location-logs`,
      params: { page: params.page ?? 1, size: params.size ?? 20 },
    }),

  /** 商户侧批次下钻/临期卡（WA/WE 只读） */
  listForWholesaler: (params: { skuId?: string; status?: string } = {}) =>
    request<BatchList>({
      method: 'GET',
      url: '/wholesaler/batches',
      params: {
        ...(params.skuId ? { skuId: params.skuId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),
}
