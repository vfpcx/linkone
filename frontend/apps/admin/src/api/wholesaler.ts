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
  WithdrawSubmitResult,
  WithdrawCancelResult,
  WithdrawRestoreResult,
  WaEmployee,
  WaEmployeeInvite,
  CreateWaEmployeeInviteRequest,
  UpdateWaEmployeePermissionsRequest,
  DisableWaEmployeeResult,
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
   * ✅ P2 · 查询本人入驻申请列表（含 status/auditRemark；后端 Wave2 已落地，ONB-08）。
   * 字段同 TA 列表的 WholesalerApplication；网络失败时页面回退本地缓存（Apply.vue 优雅降级）。
   */
  listMine: () =>
    request<WholesalerApplication[]>({
      method: 'GET',
      url: '/wholesaler/applications',
    }),
}

/**
 * WA 端 · R13 退驻（P2 入驻生态 Wave2 · 后端已落地，契约见 10-onboarding-design.md §12）
 * 错误码：50312 库存未清 / 50314 未结单据 / 50316 重复申请 / 50315 不可撤 /
 *        50317 恢复窗口已过 / 50202 已退驻
 */
export const waWithdrawApi = {
  /** ✅ P2 · 发起退驻申请（reason 选填） */
  submit: (data: SubmitWithdrawRequest) =>
    request<WithdrawSubmitResult>({
      method: 'POST',
      url: '/wholesaler/withdraw',
      data,
    }),

  /** ✅ P2 · 本人最近一次退驻申请（含驳回理由 auditRemark；从未申请时 data 为 null） */
  mine: () =>
    request<WaWithdrawApplication | null>({
      method: 'GET',
      url: '/wholesaler/withdraw/mine',
    }),

  /** ✅ P2 · 60 天内申请恢复入驻（WITHDRAWN→ACTIVE；超窗/已归档 50317） */
  restore: () =>
    request<WithdrawRestoreResult>({
      method: 'POST',
      url: '/wholesaler/withdraw/restore',
    }),

  /** ✅ P2 · 退驻前置自查（只读，与提交校验同一份逻辑；billing.cleared 恒 null 灰态） */
  precheck: () =>
    request<WaWithdrawPrecheck>({
      method: 'GET',
      url: '/wholesaler/withdraw/precheck',
    }),

  /** ✅ P2 · 撤回本人 PENDING 退驻申请（已被审批 50315；撤回后可重新发起） */
  cancel: () =>
    request<WithdrawCancelResult>({
      method: 'POST',
      url: '/wholesaler/withdraw/cancel',
    }),
}

/**
 * WA 端 · WE 员工管理（P2 入驻生态 Wave3 · 后端最终 DTO 已落定）
 * targetRole 固定 WE；permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]
 * 员工端点 {id} 一律为角色绑定行 id（WaEmployee.id），不是 userId
 * 错误码：50319 授权项非法 / 50320 员工不存在或不属本商户 /
 *        50321 状态不允许（含重复禁用）/ 50322 恢复逾 30 天 / 42004 WE 未授权
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

  /** ✅ P2 · 改授权（switch 即时生效，整组覆盖式提交；空数组=收回全部）id=角色绑定行 id */
  updatePermissions: (id: string, data: UpdateWaEmployeePermissionsRequest) =>
    request<void>({
      method: 'PUT',
      url: `/wholesaler/employees/${id}/permissions`,
      data,
    }),

  /** ✅ P2 · R17 禁用员工（立即踢出登录 + 草稿单据作废；返回 restoreWindowDays=30）id=角色绑定行 id */
  disableEmployee: (id: string) =>
    request<DisableWaEmployeeResult>({
      method: 'POST',
      url: `/wholesaler/employees/${id}/disable`,
    }),

  /** ✅ P2 · 恢复被禁用员工（30 天内；授权保持禁用前设置）id=角色绑定行 id */
  restoreEmployee: (id: string) =>
    request<void>({ method: 'POST', url: `/wholesaler/employees/${id}/restore` }),
}
