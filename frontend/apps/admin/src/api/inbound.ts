/**
 * WK 入库接口封装（admin · WK/TA 端）
 *
 * 权威来源：backend/.../document/controller/InboundController.java（P3b T1 据实测）
 *  - POST /api/v1/tenant/inbound                  WK 登记代建入库（单事务：建单 + 增库存）
 *  - GET  /api/v1/tenant/inbound?wholesalerId=&status=
 *      列出本租户入库单；status=SUBMITTED 为申请待受理队列（后端按创建时间升序，先到先受理）
 *  - POST /api/v1/tenant/inbound/{id}/accept      受理锁单（CAS SUBMITTED→ACCEPTED；50313/50330/50331）
 *  - POST /api/v1/tenant/inbound/{id}/reject      R2 驳回（reason 单选 + remark 必填 + 附件 ≤5）
 *  - POST /api/v1/tenant/inbound/{id}/register    登记正向链入库（5% 边界 50351；此刻才加库存；
 *      仅 source=WA_SUBMIT，代建链走 POST /tenant/inbound）
 *  - POST /api/v1/tenant/inbound/{id}/print       打印（非状态节点，printed_at/print_count++，补打均可）
 *  - POST /api/v1/tenant/inbound/{id}/corrections R3 发起纠错（≤24h 50352 / 防重 50353 / 非法 50354）
 *  - GET  /api/v1/tenant/inbound/corrections?status=&page=&size=  纠错列表（TA 审批中心 / WK 查看）
 *  - POST /api/v1/tenant/inbound/corrections/{id}/decide  TA 审批（APPROVED 封顶事务 / REJECTED remark 必填）
 *
 * 登记需该租户 WK 登录态（纠错审批 TA）；归属/tenantId 在 service 内以 user_roles 登录态 +
 * wholesaler 真实归属推导。雪花 ID 为 string（http.ts safeJsonParse 已防精度丢失）。
 */

import { request } from './http'
import type {
  InboundRequest,
  InboundRegisterRequest,
  InboundRejectRequest,
  InboundForwardRegisterRequest,
  InboundCorrection,
  InboundCorrectionCreateRequest,
  InboundCorrectionDecideRequest,
  MpPage,
} from '@cangchu/api-types'

export const inboundApi = {
  /** WK 登记代建入库 */
  register: (data: InboundRegisterRequest) =>
    request<InboundRequest>({
      method: 'POST',
      url: '/tenant/inbound',
      data,
    }),

  /** 列出本租户入库单（wholesalerId/status 可选过滤；SUBMITTED=待受理队列升序） */
  list: (params: { wholesalerId?: string; status?: string } = {}) =>
    request<InboundRequest[]>({
      method: 'GET',
      url: '/tenant/inbound',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),

  // ==================== P3b T1 正向申请链 ====================

  /** WK 受理（锁单防撤回；一次点击完成） */
  accept: (id: string) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/tenant/inbound/${id}/accept`,
    }),

  /** R2 驳回（原因单选 + 备注必填 + 举证附件 ≤5；零库存影响） */
  reject: (id: string, data: InboundRejectRequest) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/tenant/inbound/${id}/reject`,
      data,
    }),

  /** WK 登记正向链入库（5% 差异边界 50351；此刻才加库存） */
  registerForward: (id: string, data: InboundForwardRegisterRequest) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/tenant/inbound/${id}/register`,
      data,
    }),

  /** 打印核对单/补打（非状态节点，print_count++） */
  print: (id: string) =>
    request<InboundRequest>({
      method: 'POST',
      url: `/tenant/inbound/${id}/print`,
    }),

  // ==================== P3b T1 R3 登记纠错 ====================

  /** WK 发起纠错（登记后 ≤24h） */
  createCorrection: (id: string, data: InboundCorrectionCreateRequest) =>
    request<InboundCorrection>({
      method: 'POST',
      url: `/tenant/inbound/${id}/corrections`,
      data,
    }),

  /** 纠错列表（status 可选过滤：PENDING/APPROVED/REJECTED） */
  listCorrections: (params: { status?: string; page?: number; size?: number } = {}) =>
    request<MpPage<InboundCorrection>>({
      method: 'GET',
      url: '/tenant/inbound/corrections',
      params: {
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** TA 审批纠错（APPROVED 封顶事务 / REJECTED remark 必填） */
  decideCorrection: (id: string, data: InboundCorrectionDecideRequest) =>
    request<InboundCorrection>({
      method: 'POST',
      url: `/tenant/inbound/corrections/${id}/decide`,
      data,
    }),
}
