/**
 * 租户接口封装（admin 端）
 *
 * ⚠️ 后端只实现了下方"已对齐"的 8 个接口（commit c7d397a）
 *    其他接口（员工/邀请码/WA 审批/审批中心/账单总览/仲裁）后端尚未实现，
 *    标记为「⚠️ NOT IMPL」的方法调用会返回 500，前端先用 mock。
 */

import { request } from './http'
import type {
  ApprovalCenterResponse,
  ApproveWaRequest,
  ArbitrateInboundRequest,
  AuditWaApplicationRequest,
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
  WaApplicationListQuery,
  WaApplicationAuditResult,
  WholesalerApplication,
  WaWithdrawApplication,
  WaWithdrawListQuery,
  WithdrawAuditResult,
  ForceOfflineResult,
  PageData,
  PageRecords,
  AdminTenantItem,
  AdminTenantListQuery,
  AuditTenantRequest,
  TenantDirectoryItem,
  TenantDirectoryQuery,
} from '@cangchu/api-types'

// ============================================================
// 已对齐后端的接口（可以调用）
// ============================================================

export const tenantApi = {
  /** ✅ TA 自助注册仓库 */
  apply: (data: {
    name: string
    legalName?: string
    contactPhone: string
    addressText: string
    lng?: number
    lat?: number
  }) =>
    request<{ applicationId: string; tenantId: string; status: string }>({
      method: 'POST',
      url: '/tenant/apply',
      data,
    }),

  /** ✅ TA 查我的店铺设置 */
  getSettings: () =>
    request<TenantSettings>({ method: 'GET', url: '/tenant/me' }),

  /** ✅ TA 改店铺设置（含 5 个开关 + 地图坐标） */
  updateSettings: (data: UpdateTenantSettingsRequest) =>
    request<void>({ method: 'PUT', url: '/tenant/me', data }),

  /** ✅ TA 生成店铺二维码 */
  generateStoreQr: () =>
    request<{ tenantId: string; tenantSimpleCode: string; qrUrl: string }>({
      method: 'POST',
      url: '/tenant/store-qr',
    }),

  /** ✅ TA 生成员工注册码 */
  createInviteCode: (params: {
    targetRole: 'WK' | 'ST' | 'WA' | 'WE'
    maxUses?: number
    expireDays?: number
  }) =>
    request<{ inviteCodeId: string; code: string; expireAt: string }>({
      method: 'POST',
      url: '/tenant/invite-code',
      params,
    }),

  /** ✅ 实时容量查询（公开，无需 token） */
  getCapacity: (tenantId: string) =>
    request<{
      tenantId: string
      storeId: string
      visibility: string
      precision: string
      usedQty: number
      usedPallet: number
      utilization: number
      tier: string
      tierLabel: string
      snapshotAt: string
    }>({
      method: 'GET',
      url: '/tenant/capacity',
      params: { tenantId },
    }),

  /** ✅ OPS 审核入驻通过/驳回（REJECTED 时 remark 必填） */
  auditTenant: (id: string, data: AuditTenantRequest) =>
    request<void>({
      method: 'POST',
      url: `/admin/tenant/${id}/audit`,
      data,
    }),

  /**
   * ⚠️ 后端需补 · OPS 分页查租户（仓库）列表
   * 前端按合理契约先行：GET /api/v1/admin/tenants?status=&page=&size=
   * （后端 TenantController 目前无任何 admin 端 GET 列表端点，页面对未就绪端点已做优雅降级）
   */
  listTenantsByOps: (params?: AdminTenantListQuery) =>
    request<PageData<AdminTenantItem>>({
      method: 'GET',
      url: '/admin/tenants',
      params,
    }),

  /** ✅ OPS 代建租户（直接 ACTIVE + 短信临时密码） */
  createByOps: (data: {
    name: string
    legalName?: string
    contactPhone: string
    addressText: string
    lng?: number
    lat?: number
  }) =>
    request<{ tenantId: string; taUserId: string; isNewUser: boolean; status: string }>({
      method: 'POST',
      url: '/admin/tenant/create',
      data,
    }),

  // ============================================================
  // 以下接口后端未实现，前端调用会 500（90001）
  // 等后续后端模块补齐后启用
  // ============================================================

  /** ⚠️ NOT IMPL · 工作台聚合（前端暂用 mock） */
  getDashboard: () =>
    request<TenantDashboardResponse>({ method: 'GET', url: '/tenant/dashboard' }),

  /** ⚠️ NOT IMPL · 撮合店铺页 */
  getStoreFront: () =>
    request<StoreFront>({ method: 'GET', url: '/tenant/store-front' }),

  /** ⚠️ NOT IMPL · 更新撮合店铺页 */
  updateStoreFront: (data: Partial<StoreFront>) =>
    request<StoreFront>({ method: 'PATCH', url: '/tenant/store-front', data }),

  /** ⚠️ NOT IMPL · 员工列表 */
  listEmployees: () =>
    request<PageData<Employee>>({ method: 'GET', url: '/tenant/employees' }),

  /** ⚠️ NOT IMPL · 邀请员工 */
  inviteEmployee: (data: InviteEmployeeRequest) =>
    request<InviteCode>({ method: 'POST', url: '/tenant/employees', data }),

  /** ⚠️ NOT IMPL · 禁用员工 */
  disableEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/tenant/employees/${id}/disable` }),

  /** ⚠️ NOT IMPL · 恢复员工 */
  restoreEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/tenant/employees/${id}/restore` }),

  /** ⚠️ NOT IMPL · 邀请码列表 */
  listInviteCodes: () =>
    request<PageData<InviteCode>>({ method: 'GET', url: '/tenant/invite-codes' }),

  // ============================================================
  // P2 入驻生态 · Wave1 契约（后端 feat/p2-onboarding 并行开发中）
  // ============================================================

  /** ✅ P2 · WA 入驻申请分页列表（TA；status/page/size 均可选；返回 records 命名分页） */
  listWaApplications: (params?: WaApplicationListQuery) =>
    request<PageRecords<WholesalerApplication>>({
      method: 'GET',
      url: '/tenant/wholesaler-applications',
      params,
    }),

  /** ✅ P2 · TA 审批 WA 入驻（统一 audit 端点；REJECTED 时 remark 必填；不可审 50203） */
  auditWaApplication: (id: string, data: AuditWaApplicationRequest) =>
    request<WaApplicationAuditResult>({
      method: 'POST',
      url: `/tenant/wholesaler-applications/${id}/audit`,
      data,
    }),

  /** ✅ P2 · 批准 WA 入驻（audit 端点包装，保留旧签名兼容） */
  approveWa: (id: string, data?: ApproveWaRequest) =>
    request<WaApplicationAuditResult>({
      method: 'POST',
      url: `/tenant/wholesaler-applications/${id}/audit`,
      data: { action: 'APPROVED', remark: data?.remark },
    }),

  /** ✅ P2 · 驳回 WA 入驻（audit 端点包装；理由必填） */
  rejectWa: (id: string, data: RejectWaRequest) =>
    request<WaApplicationAuditResult>({
      method: 'POST',
      url: `/tenant/wholesaler-applications/${id}/audit`,
      data: { action: 'REJECTED', remark: data.reason },
    }),

  /** ✅ P2 · 创建自营 WA（D15 统一入驻链路，后端 Wave1 落地） */
  createSelfOperatedWa: (data: CreateSelfOperatedWaRequest) =>
    request<{ wholesalerId: string }>({
      method: 'POST',
      url: '/tenant/wholesalers/self-operated',
      data,
    }),

  /**
   * ✅ P2 · 强制下架 WA（R14 · Wave2 后端已落地）
   * TA 单方即时生效不走审批；reason 必填留痕并通知商户；不可原地恢复（OFFLINE 终态）
   */
  forceOfflineWa: (id: string, data: ForceOfflineWaRequest) =>
    request<ForceOfflineResult>({
      method: 'POST',
      url: `/tenant/wholesalers/${id}/force-offline`,
      data,
    }),

  /** ✅ P2 · WA 退驻申请分页列表（R13 TA 审批；返回 records 命名分页，含 wholesalerName 冗余） */
  listWaWithdrawApplications: (params?: WaWithdrawListQuery) =>
    request<PageRecords<WaWithdrawApplication>>({
      method: 'GET',
      url: '/tenant/wholesaler-withdraw-applications',
      params,
    }),

  /**
   * ✅ P2 · TA 审批退驻（APPROVED 瞬间：SKU 下架 + 店铺隐藏 + 专属价失效 + 踢 token；
   * REJECTED 时 remark 必填，原文展示给申请人；CAS 竞争败者 50315）
   */
  auditWaWithdrawApplication: (
    id: string,
    data: { action: 'APPROVED' | 'REJECTED'; remark?: string },
  ) =>
    request<WithdrawAuditResult>({
      method: 'POST',
      url: `/tenant/wholesaler-withdraw-applications/${id}/audit`,
      data,
    }),

  /** ⚠️ NOT IMPL · 审批中心 */
  getApprovalCenter: (params?: Record<string, unknown>) =>
    request<ApprovalCenterResponse>({
      method: 'GET',
      url: '/tenant/approval-center',
      params,
    }),

  /** ⚠️ NOT IMPL · 账单总览 */
  getBillsOverview: (params?: BillsOverviewQuery) =>
    request<BillsOverviewResponse>({
      method: 'GET',
      url: '/tenant/bills-overview',
      params,
    }),

  /** ⚠️ NOT IMPL · 代建入库异议仲裁 */
  arbitrateInbound: (id: string, data: ArbitrateInboundRequest) =>
    request<void>({
      method: 'POST',
      url: `/tenant/inbound-requests/${id}/arbitrate`,
      data,
    }),
}

// ============================================================
// 公开端点（匿名可访问，不需登录态）
// ============================================================

export const tenantDirectoryApi = {
  /**
   * ✅ Wave6 · DEF-1 · 公开租户目录（§32）：WA 注册页「选择想入驻的仓库」数据源。
   * 仅 ACTIVE 租户；元素恰 id/name 两字段；limit 默认 10、上限 20；
   * 同 IP 30 次/分钟，超限 43001（全局拦截器已 toast 友好提示）。
   */
  search: (params?: TenantDirectoryQuery) =>
    request<TenantDirectoryItem[]>({ method: 'GET', url: '/tenants/directory', params }),
}
