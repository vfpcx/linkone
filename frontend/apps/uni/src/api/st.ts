/**
 * ST 结算账单 API（F4 · 移动端 P0 操作集）。
 * 端点路径/方法/字段与 admin apps/admin/src/api/billing.ts 及后端 StBillController 一致
 * （requireStOrTa 鉴权在 Service；本端仅 ST 结算区可达）。
 */
import { request } from '../utils/request'
import type {
  Bill,
  BillDetail,
  BillDispute,
  BillDisputeResolveRequest,
  BillListQuery,
  BillListResult,
  PaymentRegisterRequest,
  PaymentReverseRequest,
  SnowflakeId,
} from '@cangchu/api-types'

export const stBillApi = {
  /** 列表 + 汇总卡（应收/已收/未收按当前筛选合计） */
  list(params: BillListQuery): Promise<BillListResult> {
    return request<BillListResult>({
      url: '/tenant/st/bills',
      params: {
        month: params.month,
        wholesalerId: params.wholesalerId,
        status: params.status,
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    })
  },

  /** 详情：三金额 + items + payments + disputes */
  detail(id: SnowflakeId | string): Promise<BillDetail> {
    return request<BillDetail>({ url: `/tenant/st/bills/${encodeURIComponent(String(id))}` })
  },

  /** 下发（仅待核对 DRAFT） */
  dispatch(id: SnowflakeId | string): Promise<Bill> {
    return request<Bill>({ url: `/tenant/st/bills/${encodeURIComponent(String(id))}/dispatch`, method: 'POST' })
  },

  /** 撤回（仅已下发 DISPATCHED 且 0 回款未确认） */
  withdraw(id: SnowflakeId | string): Promise<Bill> {
    return request<Bill>({ url: `/tenant/st/bills/${encodeURIComponent(String(id))}/withdraw`, method: 'POST' })
  },

  /** 回款登记（US-ST-04，不允许超收） */
  registerPayment(id: SnowflakeId | string, body: PaymentRegisterRequest): Promise<BillDetail> {
    return request<BillDetail>({
      url: `/tenant/st/bills/${encodeURIComponent(String(id))}/payments`,
      method: 'POST',
      data: body,
    })
  },

  /** 回款冲销（R12：理由必填 + 二次确认） */
  reversePayment(paymentId: SnowflakeId | string, body: PaymentReverseRequest): Promise<BillDetail> {
    return request<BillDetail>({
      url: `/tenant/st/payments/${encodeURIComponent(String(paymentId))}/reverse`,
      method: 'POST',
      data: body,
    })
  },

  /** 申诉队列（status 省略 = 全部；PENDING 升序先到先处理） */
  listDisputes(status?: string): Promise<BillDispute[]> {
    return request<BillDispute[]>({ url: '/tenant/st/bill-disputes', params: { status } })
  },

  /** 申诉处理（结论=成立 RESOLVED / 不成立 REJECTED，说明必填） */
  resolveDispute(id: SnowflakeId | string, body: BillDisputeResolveRequest): Promise<BillDispute> {
    return request<BillDispute>({
      url: `/tenant/st/bill-disputes/${encodeURIComponent(String(id))}/resolve`,
      method: 'POST',
      data: body,
    })
  },
}
