/**
 * 租户接口封装（admin 端）
 * 对齐 shared/architecture/04-api-spec.md §4.3
 */

import { request } from './http'
import type {
  ApprovalCenterResponse,
  ApproveWaRequest,
  ArbitrateInboundRequest,
  BillsOverviewQuery,
  BillsOverviewResponse,
  CreateSelfOperatedWaRequest,
  Employee,
  ForceOfflineWaRequest,
  InviteCode,
  InviteEmployeeRequest,
  RejectWaRequest,
  StoreFront,
  TenantDashboardResponse,
  TenantSettings,
  UpdateTenantSettingsRequest,
  WholesalerApplication,
  PageData,
} from '@cangchu/api-types'

export const tenantApi = {
  // 店铺设置
  getSettings: () =>
    request<TenantSettings>({ method: 'GET', url: '/tenant/settings' }),

  updateSettings: (data: UpdateTenantSettingsRequest) =>
    request<TenantSettings>({ method: 'PATCH', url: '/tenant/settings', data }),

  // 工作台聚合（自定义）：实际后端可能拆为多个接口；前端聚合调用
  getDashboard: () =>
    request<TenantDashboardResponse>({ method: 'GET', url: '/tenant/dashboard' }),

  // 撮合页
  getStoreFront: () =>
    request<StoreFront>({ method: 'GET', url: '/tenant/store-front' }),

  updateStoreFront: (data: Partial<StoreFront>) =>
    request<StoreFront>({ method: 'PATCH', url: '/tenant/store-front', data }),

  // 员工
  listEmployees: () =>
    request<PageData<Employee>>({ method: 'GET', url: '/tenant/employees' }),

  inviteEmployee: (data: InviteEmployeeRequest) =>
    request<InviteCode>({ method: 'POST', url: '/tenant/employees', data }),

  disableEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/tenant/employees/${id}/disable` }),

  restoreEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/tenant/employees/${id}/restore` }),

  // 邀请码
  listInviteCodes: () =>
    request<PageData<InviteCode>>({ method: 'GET', url: '/tenant/invite-codes' }),

  createInviteCode: (data: InviteEmployeeRequest) =>
    request<InviteCode>({ method: 'POST', url: '/tenant/invite-codes', data }),

  // WA 入驻审批
  listWaApplications: () =>
    request<PageData<WholesalerApplication>>({
      method: 'GET',
      url: '/tenant/wholesaler-applications',
    }),

  approveWa: (id: string, data?: ApproveWaRequest) =>
    request<void>({
      method: 'POST',
      url: `/tenant/wholesaler-applications/${id}/approve`,
      data,
    }),

  rejectWa: (id: string, data: RejectWaRequest) =>
    request<void>({
      method: 'POST',
      url: `/tenant/wholesaler-applications/${id}/reject`,
      data,
    }),

  // 自营 WA + 强制下架
  createSelfOperatedWa: (data: CreateSelfOperatedWaRequest) =>
    request<{ wholesalerId: string }>({
      method: 'POST',
      url: '/tenant/wholesalers/self-operated',
      data,
    }),

  forceOfflineWa: (id: string, data: ForceOfflineWaRequest) =>
    request<void>({ method: 'POST', url: `/tenant/wholesalers/${id}/force-offline`, data }),

  // 审批中心
  getApprovalCenter: (params?: Record<string, unknown>) =>
    request<ApprovalCenterResponse>({
      method: 'GET',
      url: '/tenant/approval-center',
      params,
    }),

  // 账单总览
  getBillsOverview: (params?: BillsOverviewQuery) =>
    request<BillsOverviewResponse>({
      method: 'GET',
      url: '/tenant/bills-overview',
      params,
    }),

  // 代建入库异议仲裁
  arbitrateInbound: (id: string, data: ArbitrateInboundRequest) =>
    request<void>({
      method: 'POST',
      url: `/tenant/inbound-requests/${id}/arbitrate`,
      data,
    }),
}
