/**
 * 清库单链接口封装（P3b T4-FE · QK-，状态机完全套用盘点 PD-）
 *
 * 权威来源（据实查证）：backend/.../document/controller/TenantClearanceController.java
 *  - POST   /api/v1/tenant/clearance-requests        WK 建草稿（一单一批次；
 *      仅 PENDING_CLEARANCE 且推算剩余>0 / 同批次在途至多一张 → 50365；
 *      照片 ≥1（50366）≤3（40001）；qty ≤ 池当前在库（50251）；OTHER 时 reasonRemark 必填）
 *  - PUT    /api/v1/tenant/clearance-requests/{id}   WK 编辑（DRAFT 直改 /
 *      REJECTED 改回 DRAFT 重提——重提复检批次仍 PENDING_CLEARANCE，否则 50365；batchId 不可变）
 *  - DELETE /api/v1/tenant/clearance-requests/{id}   WK 删草稿（仅 DRAFT，硬删并释放在途唯一位）
 *  - POST   /api/v1/tenant/clearance-requests/{id}/submit  WK 提交（CAS DRAFT→PENDING_APPROVAL）
 *  - GET    /api/v1/tenant/clearance-requests?wholesalerId=&status=  列表（WK/TA；
 *      待审批创建升序先到先审；列表不带名称——盘点先例）
 *  - GET    /api/v1/tenant/clearance-requests/{id}   详情（附批次五字段 +
 *      currentStock/suggestedPalletRelease 封顶预览，免另拉库存）
 *  - POST   /api/v1/tenant/clearance-requests/{id}/decide  TA 审批（APPROVED 锁内
 *      clearStock 封顶 + EXPIRY_CLEARANCE 流水 + 批次 CLEARED + 商户凭证通知；REJECTED remark 必填）
 *
 * 口径：通过=按 SKU 池剩余在库封顶（min(qty, currentStock)，D-10 同构），差额写备注；
 *  仓储费当日截止、不计正常出库统计（文案在审批弹窗明示）。
 */

import { request } from './http'
import type {
  ClearanceCreateRequest,
  ClearanceDecideRequest,
  ClearanceRequest,
  ClearanceUpdateRequest,
} from '@cangchu/api-types'

export const clearanceApi = {
  /** WK 建草稿（一单一批次） */
  create: (data: ClearanceCreateRequest) =>
    request<ClearanceRequest>({
      method: 'POST',
      url: '/tenant/clearance-requests',
      data,
    }),

  /** WK 编辑（DRAFT 直改 / REJECTED 改回 DRAFT 重提） */
  update: (id: string, data: ClearanceUpdateRequest) =>
    request<ClearanceRequest>({
      method: 'PUT',
      url: `/tenant/clearance-requests/${id}`,
      data,
    }),

  /** WK 删除草稿（仅 DRAFT） */
  remove: (id: string) =>
    request<void>({
      method: 'DELETE',
      url: `/tenant/clearance-requests/${id}`,
    }),

  /** WK 提交审批 */
  submit: (id: string) =>
    request<ClearanceRequest>({
      method: 'POST',
      url: `/tenant/clearance-requests/${id}/submit`,
    }),

  /** 列表（WK/TA；待审批队列创建升序先到先审） */
  list: (params: { wholesalerId?: string; status?: string } = {}) =>
    request<ClearanceRequest[]>({
      method: 'GET',
      url: '/tenant/clearance-requests',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),

  /** 详情（附批次五字段 + 封顶预览数据） */
  detail: (id: string) =>
    request<ClearanceRequest>({
      method: 'GET',
      url: `/tenant/clearance-requests/${id}`,
    }),

  /** TA 审批（REJECTED 时 remark 必填） */
  decide: (id: string, data: ClearanceDecideRequest) =>
    request<ClearanceRequest>({
      method: 'POST',
      url: `/tenant/clearance-requests/${id}/decide`,
      data,
    }),
}
