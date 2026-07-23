/**
 * OPS 平台运营接口封装（admin · OPS 端）
 *
 * P2 入驻生态 · 黑名单（后端已落地，权威来源 10-onboarding-design.md §1.2/§2 + §31 Wave6 分页）：
 *  - GET    /ops/blacklist?page=&size=&status=&keyword=
 *        分页列表（{records,total,page,size}；keyword 模糊匹配 targetValue；createdAt 倒序）
 *  - POST   /ops/blacklist           加入黑名单（{targetType, targetValue, reason} 均必填，仍返回单条）
 *  - DELETE /ops/blacklist/{id}      移除黑名单（置 REMOVED，保留追溯）
 *
 * 黑名单为平台级共享（不走 TenantLine）；三条入驻路径（自助 / OPS 代建 / TA 自营）
 * 均必检，命中返回 50205。重复加黑 50310 / 条目不存在 50311。需 OPS 登录态。
 */

import { request } from './http'
import type {
  BlacklistItem,
  BlacklistListQuery,
  CreateBlacklistRequest,
  PageRecords,
} from '@cangchu/api-types'

export const opsApi = {
  /** ✅ P2 · 黑名单分页列表（DEF-6 · §31：{records,total,page,size}，keyword 键值搜索） */
  listBlacklist: (params?: BlacklistListQuery) =>
    request<PageRecords<BlacklistItem>>({ method: 'GET', url: '/ops/blacklist', params }),

  /** ✅ P2 · 加入黑名单（targetType + targetValue + reason 必填；重复 50310） */
  createBlacklist: (data: CreateBlacklistRequest) =>
    request<BlacklistItem>({ method: 'POST', url: '/ops/blacklist', data }),

  /** ✅ P2 · 移除黑名单（置 REMOVED；不存在 50311） */
  removeBlacklist: (id: string) =>
    request<void>({ method: 'DELETE', url: `/ops/blacklist/${id}` }),
}
