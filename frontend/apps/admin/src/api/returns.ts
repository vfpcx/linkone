/**
 * 退货链接口封装（P3b T3-FE · RTN-）
 *
 * 权威来源（据实查证）：
 *  - backend/.../document/controller/WholesalerReturnController.java（WA 侧）
 *      POST /api/v1/wholesaler/return-requests              WA 发起（D-7：提交零库存，软校验在库）
 *      GET  /api/v1/wholesaler/return-requests?status=&page=&size=   我的退货单（WA 全量 / WE 只读）
 *      POST /api/v1/wholesaler/return-requests/{id}/withdraw  撤回（仅待受理，reason 必填）
 *  - backend/.../document/controller/TenantReturnController.java（WK/TA 作业侧）
 *      GET  /api/v1/tenant/return-requests?wholesalerId=&status=   受理队列（返回 List 非分页；
 *           status=PENDING_ACCEPT 创建升序先到先受理；PENDING_ACCEPT/ACCEPTED 行附
 *           currentStock/suggestedPalletRelease，13 v1.2 备注 10）
 *      POST /api/v1/tenant/return-requests/{id}/accept       WK 受理（CAS 锁单，仍零库存）
 *      POST /api/v1/tenant/return-requests/{id}/register     WK 登记（D-7 此刻扣库存+释放托盘）
 *
 * 错误码：50251 在库不足（登记拒绝，单据保持已受理）/ 50330 状态不可达 / 50331 CAS 冲突。
 * 归属鉴权（WA 归属 / WK·TA 本租户）由后端登录态推导，前端不传归属参数（G-2.1）。
 * D-9：发起/撤回仅 WA，WE 只读列表（入口对 WE 隐藏）。
 */

import { request } from './http'
import type {
  MpPage,
  ReturnRequest,
  ReturnCreateRequest,
  ReturnWithdrawRequest,
  ReturnRegisterRequest,
} from '@cangchu/api-types'

/** WA 侧（/wholesaler/**） */
export const waReturnApi = {
  /** 发起退货申请（零库存；超在库量拒绝 50251） */
  create: (data: ReturnCreateRequest) =>
    request<ReturnRequest>({
      method: 'POST',
      url: '/wholesaler/return-requests',
      data,
    }),

  /** 我的退货单列表（status 可选过滤；WE 只读） */
  list: (params: { status?: string; page?: number; size?: number } = {}) =>
    request<MpPage<ReturnRequest>>({
      method: 'GET',
      url: '/wholesaler/return-requests',
      params: {
        ...(params.status ? { status: params.status } : {}),
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    }),

  /** 撤回（仅待受理；受理后 50330 须走仓库流转） */
  withdraw: (id: string, data: ReturnWithdrawRequest) =>
    request<ReturnRequest>({
      method: 'POST',
      url: `/wholesaler/return-requests/${id}/withdraw`,
      data,
    }),
}

/** WK/TA 作业侧（/tenant/**；返回 List 非分页） */
export const tenantReturnApi = {
  /** 退货队列（status=PENDING_ACCEPT 创建升序；PENDING_ACCEPT/ACCEPTED 行附在库与托盘建议值） */
  list: (params: { wholesalerId?: string; status?: string } = {}) =>
    request<ReturnRequest[]>({
      method: 'GET',
      url: '/tenant/return-requests',
      params: {
        ...(params.wholesalerId ? { wholesalerId: params.wholesalerId } : {}),
        ...(params.status ? { status: params.status } : {}),
      },
    }),

  /** WK 受理（CAS PENDING_ACCEPT→ACCEPTED 锁单防撤回；仍不动库存——D-7） */
  accept: (id: string) =>
    request<ReturnRequest>({
      method: 'POST',
      url: `/tenant/return-requests/${id}/accept`,
    }),

  /** WK 现场出货登记（D-7 登记时扣）；在库不足 → 50251（单据保持已受理） */
  register: (id: string, data: ReturnRegisterRequest = {}) =>
    request<ReturnRequest>({
      method: 'POST',
      url: `/tenant/return-requests/${id}/register`,
      data,
    }),
}
