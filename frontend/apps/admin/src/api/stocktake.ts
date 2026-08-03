/**
 * 盘点链接口封装（P3b T3-FE · PD-）
 *
 * 权威来源（据实查证）：backend/.../document/controller/TenantStocktakeController.java
 *  - POST   /api/v1/tenant/count-sheets            WK 建草稿（同商户在途至多一张 50356；明细 50355）
 *  - PUT    /api/v1/tenant/count-sheets/{id}       WK 编辑（DRAFT 直改 / REJECTED 改回 DRAFT 重提；items 全量替换）
 *  - DELETE /api/v1/tenant/count-sheets/{id}       WK 删除草稿（仅 DRAFT，硬删并释放在途唯一位）
 *  - POST   /api/v1/tenant/count-sheets/{id}/submit  WK 提交（system_qty 提交时刻快照定格）→ 通知 TA
 *  - GET    /api/v1/tenant/count-sheets?wholesalerId=&status=  列表（List 非分页；
 *           status=PENDING_APPROVAL 创建升序先到先审）
 *  - GET    /api/v1/tenant/count-sheets/in-transit-hint?wholesalerId=  在途提示条聚合
 *  - GET    /api/v1/tenant/count-sheets/{id}       详情（items 含 currentStock/suggestedPalletRelease
 *           封顶预览数据 + inTransitHint）
 *  - POST   /api/v1/tenant/count-sheets/{id}/decide  TA 审批（APPROVED 逐 SKU 锁内 GAIN/LOSS，
 *           盘亏 D-10 按审批时刻在库封顶；REJECTED 时 remark 必填）
 *
 * 错误码：50355 明细非法 / 50356 在途盘点已存在 / 50330 状态不可达 / 50331 CAS 冲突。
 * 两时点口径（13 §7.3）：diff 以提交快照为准，生效量以审批时刻在库封顶——前端文案须区分。
 */

import { request } from './http'
import type {
  CountSheet,
  CountSheetCreateRequest,
  CountSheetUpdateRequest,
  CountSheetDecideRequest,
  StocktakeInTransitHint,
} from '@cangchu/api-types'

export const stocktakeApi = {
  /** WK 建草稿 */
  create: (data: CountSheetCreateRequest) =>
    request<CountSheet>({
      method: 'POST',
      url: '/tenant/count-sheets',
      data,
    }),

  /** WK 编辑（DRAFT 直改 / REJECTED 改回 DRAFT 重提） */
  update: (id: string, data: CountSheetUpdateRequest) =>
    request<CountSheet>({
      method: 'PUT',
      url: `/tenant/count-sheets/${id}`,
      data,
    }),

  /** WK 删除草稿（仅 DRAFT） */
  remove: (id: string) =>
    request<void>({
      method: 'DELETE',
      url: `/tenant/count-sheets/${id}`,
    }),

  /** WK 提交审批（账面数按提交时刻重新快照定格） */
  submit: (id: string) =>
    request<CountSheet>({
      method: 'POST',
      url: `/tenant/count-sheets/${id}/submit`,
    }),

  /** 列表（WK/TA；待审批队列创建升序先到先审） */
  list: (params: { wholesalerId?: string; status?: string } = {}) =>
    request<CountSheet[]>({
      method: 'GET',
      url: '/tenant/count-sheets',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),

  /** 在途提示条聚合（盘点录入页/审批弹窗护栏） */
  inTransitHint: (wholesalerId: string) =>
    request<StocktakeInTransitHint>({
      method: 'GET',
      url: '/tenant/count-sheets/in-transit-hint',
      params: { wholesalerId },
    }),

  /** 详情（含明细封顶预览数据 + 在途提示条） */
  detail: (id: string) =>
    request<CountSheet>({
      method: 'GET',
      url: `/tenant/count-sheets/${id}`,
    }),

  /** TA 审批（REJECTED 时 remark 必填） */
  decide: (id: string, data: CountSheetDecideRequest) =>
    request<CountSheet>({
      method: 'POST',
      url: `/tenant/count-sheets/${id}/decide`,
      data,
    }),
}
