/**
 * 批发商商户接口封装（admin · TA 端 + WA 端）
 *
 * 权威来源：backend/.../tenant/controller/WholesalerController.java
 *  - POST /tenant/wholesalers       创建（TA 自营建商户）
 *  - PUT  /tenant/wholesalers/{id}  改资料（intro / license）
 *  - GET  /tenant/wholesalers       列出本租户商户
 *
 * P2 入驻生态（Wave1 契约，后端 feat/p2-onboarding 并行开发中）：
 *  - POST /wholesaler/applications  WA 提交入驻申请
 *    错误码：50201 审核中（重复提交）/ 50204 重复入驻 / 50205 黑名单拦截
 *
 * 均需登录态；tenantId 由后端登录态推导，前端不传。
 */

import { request } from './http'
import type {
  Wholesaler,
  CreateWholesalerRequest,
  UpdateWholesalerRequest,
  SubmitWaApplicationRequest,
  SubmitWaApplicationResponse,
  WholesalerApplication,
  SubmitWithdrawRequest,
  WaWithdrawApplication,
  WaWithdrawPrecheck,
  WaEmployee,
  WaEmployeeInvite,
  CreateWaEmployeeInviteRequest,
  UpdateWaEmployeePermissionsRequest,
} from '@cangchu/api-types'

export const wholesalerApi = {
  /** 列出本租户商户 */
  list: () =>
    request<Wholesaler[]>({ method: 'GET', url: '/tenant/wholesalers' }),

  /** TA 自营创建批发商商户 */
  create: (data: CreateWholesalerRequest) =>
    request<Wholesaler>({ method: 'POST', url: '/tenant/wholesalers', data }),

  /** 修改商户资料（intro / license） */
  update: (id: string, data: UpdateWholesalerRequest) =>
    request<Wholesaler>({ method: 'PUT', url: `/tenant/wholesalers/${id}`, data }),
}

/** WA 端 · 入驻申请（P2 入驻生态） */
export const waApplicationApi = {
  /** ✅ P2 · WA 提交入驻申请 */
  submit: (data: SubmitWaApplicationRequest) =>
    request<SubmitWaApplicationResponse>({
      method: 'POST',
      url: '/wholesaler/applications',
      data,
    }),

  /**
   * ⚠️ 契约微调位 · 查询本人入驻申请（含驳回理由）。
   * Wave1 契约未显式列出该端点；后端未提供时调用失败，
   * 页面回退到本地缓存的提交记录展示（Apply.vue 已做优雅降级）。
   */
  listMine: () =>
    request<WholesalerApplication[]>({
      method: 'GET',
      url: '/wholesaler/applications',
    }),
}

/**
 * WA 端 · R13 退驻（P2 入驻生态 Wave2 契约，后端并行开发中）
 * 错误码：50312+ 前置不满足（50310-50329 段）/ 50202 已退驻
 */
export const waWithdrawApi = {
  /** ✅ P2 · 发起退驻申请（reason 选填） */
  submit: (data: SubmitWithdrawRequest) =>
    request<WaWithdrawApplication>({
      method: 'POST',
      url: '/wholesaler/withdraw',
      data,
    }),

  /** ✅ P2 · 本人退驻申请状态（含驳回理由；无申请时 data 为 null） */
  mine: () =>
    request<WaWithdrawApplication | null>({
      method: 'GET',
      url: '/wholesaler/withdraw/mine',
    }),

  /** ✅ P2 · 60 天内申请恢复入驻 */
  restore: () =>
    request<void>({ method: 'POST', url: '/wholesaler/withdraw/restore' }),

  /**
   * ⚠️ 契约微调位 · 退驻前置自查（库存清零 / 无未结单据）。
   * Wave2 契约未显式列出该端点；后端未提供时页面降级为
   * "提交时由后端复核"（提交报 50310-50329 段错误回填清单）。
   */
  precheck: () =>
    request<WaWithdrawPrecheck>({
      method: 'GET',
      url: '/wholesaler/withdraw/precheck',
    }),

  /**
   * ⚠️ 契约微调位 · 撤回待审批的退驻申请（06b §3.3 [撤回退驻申请]）。
   * Wave2 契约未显式列出该端点；后端未提供时按钮报错不影响其余功能。
   */
  cancel: () =>
    request<void>({ method: 'POST', url: '/wholesaler/withdraw/cancel' }),
}

/**
 * WA 端 · WE 员工管理（P2 入驻生态 Wave3 契约 · Team Lead 定稿 6 端点）
 * targetRole 固定 WE；permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]
 */
export const waEmployeeApi = {
  /** ✅ P2 · 生成 WE 员工注册码 */
  createInvite: (data: CreateWaEmployeeInviteRequest) =>
    request<WaEmployeeInvite>({
      method: 'POST',
      url: '/wholesaler/employee-invites',
      data,
    }),

  /** ✅ P2 · 本商户注册码列表 */
  listInvites: () =>
    request<WaEmployeeInvite[]>({
      method: 'GET',
      url: '/wholesaler/employee-invites',
    }),

  /** ✅ P2 · 作废注册码 */
  revokeInvite: (id: string) =>
    request<void>({ method: 'DELETE', url: `/wholesaler/employee-invites/${id}` }),

  /** ✅ P2 · 本商户 WE 员工列表（含授权位 / 状态 / 禁用时间） */
  listEmployees: () =>
    request<WaEmployee[]>({ method: 'GET', url: '/wholesaler/employees' }),

  /** ✅ P2 · 改授权（switch 即时生效，整组覆盖式提交） */
  updatePermissions: (id: string, data: UpdateWaEmployeePermissionsRequest) =>
    request<void>({
      method: 'PUT',
      url: `/wholesaler/employees/${id}/permissions`,
      data,
    }),

  /** ✅ P2 · R17 禁用员工（立即踢出登录 + 草稿单据作废，30 天内可恢复） */
  disableEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/wholesaler/employees/${id}/disable` }),

  /** ✅ P2 · 恢复被禁用员工（30 天内；授权保持禁用前设置） */
  restoreEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/wholesaler/employees/${id}/restore` }),
}
