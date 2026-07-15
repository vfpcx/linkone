/**
 * OPS 平台运营接口封装（admin · OPS 端）
 *
 * P2 入驻生态 · 黑名单（Wave1 契约，后端 feat/p2-onboarding 并行开发中）：
 *  - GET    /ops/blacklist?page=&size=   黑名单分页列表
 *  - POST   /ops/blacklist               加入黑名单（phone / license 至少一键 + reason 必填）
 *  - DELETE /ops/blacklist/{id}          移除黑名单
 *
 * 黑名单为平台级共享（不走 TenantLine）；入驻申请 + OPS 代建均必检，
 * 命中返回 50205（STATE_WA_BLACKLISTED）。需 OPS 登录态。
 */

import { request } from './http'
import type {
  BlacklistItem,
  BlacklistListQuery,
  CreateBlacklistRequest,
  PageData,
} from '@cangchu/api-types'

export const opsApi = {
  /** ✅ P2 · 黑名单分页列表 */
  listBlacklist: (params?: BlacklistListQuery) =>
    request<PageData<BlacklistItem>>({ method: 'GET', url: '/ops/blacklist', params }),

  /** ✅ P2 · 加入黑名单（phone / license 至少一键） */
  createBlacklist: (data: CreateBlacklistRequest) =>
    request<BlacklistItem>({ method: 'POST', url: '/ops/blacklist', data }),

  /** ✅ P2 · 移除黑名单 */
  removeBlacklist: (id: string) =>
    request<void>({ method: 'DELETE', url: `/ops/blacklist/${id}` }),
}
