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
  Announcement,
  AnnouncementListQuery,
  BlacklistItem,
  BlacklistListQuery,
  CreateAnnouncementRequest,
  CreateBlacklistRequest,
  CreateSpuRequest,
  OpsDashboardResponse,
  PageRecords,
  Spu,
  SpuCategoryGroup,
  SpuQuery,
} from '@cangchu/api-types'

export const opsApi = {
  /**
   * ✅ P5-C · OPS 平台运营控制台（21-p5c-ops-console-design §5；GET /ops/dashboard，requireOps，
   * 非 OPS → 42002）：platform 平台规模 / pending 待办队列 / today 今日动态。
   */
  getDashboard: () =>
    request<OpsDashboardResponse>({ method: 'GET', url: '/ops/dashboard' }),

  // ---------------- P5-D D56 · 平台标品库（22 §3.1，requireOps 42002） ----------------

  /** OPS 标品分页（keyword 名称/编码模糊 + 品类/状态过滤；含引用 SKU 数） */
  listSpus: (params: SpuQuery) =>
    request<PageRecords<Spu>>({ method: 'GET', url: '/ops/spus', params }),

  /** 新增标品（ACTIVE；spuCode 空则自动生成） */
  createSpu: (data: CreateSpuRequest) =>
    request<Spu>({ method: 'POST', url: '/ops/spus', data }),

  /** 下架（ACTIVE→OFFLINE；存量 SKU 引用保留） */
  offlineSpu: (id: string) =>
    request<void>({ method: 'POST', url: `/ops/spus/${id}/offline` }),

  /** 合并 source→target（同事务重指引用 SKU） */
  mergeSpu: (id: string, targetSpuId: string) =>
    request<void>({
      method: 'POST',
      url: `/ops/spus/${id}/merge`,
      params: { targetSpuId },
    }),

  /** 两级品类字典（新增弹窗下拉同源） */
  listSpuCategories: () =>
    request<SpuCategoryGroup[]>({ method: 'GET', url: '/ops/spus/spu-categories' }),

  /** ✅ P2 · 黑名单分页列表（DEF-6 · §31：{records,total,page,size}，keyword 键值搜索） */
  listBlacklist: (params?: BlacklistListQuery) =>
    request<PageRecords<BlacklistItem>>({ method: 'GET', url: '/ops/blacklist', params }),

  /** ✅ P2 · 加入黑名单（targetType + targetValue + reason 必填；重复 50310） */
  createBlacklist: (data: CreateBlacklistRequest) =>
    request<BlacklistItem>({ method: 'POST', url: '/ops/blacklist', data }),

  /** ✅ P2 · 移除黑名单（置 REMOVED；不存在 50311） */
  removeBlacklist: (id: string) =>
    request<void>({ method: 'DELETE', url: `/ops/blacklist/${id}` }),

  // ============================================================
  // P5-A W3 · 平台公告管理（18-p5-design §4.2，OPS 登录态）
  // ============================================================

  /** 公告列表（可 status 过滤；records/total 分页） */
  listAnnouncements: (params?: AnnouncementListQuery) =>
    request<PageRecords<Announcement>>({
      method: 'GET',
      url: '/ops/announcements',
      params,
    }),

  /** 公告详情 */
  getAnnouncement: (id: string) =>
    request<Announcement>({ method: 'GET', url: `/ops/announcements/${id}` }),

  /** 创建公告草稿（title ≤128 / content ≤512 / targetRoles 至少一个；落 DRAFT；返回新公告 id） */
  createAnnouncement: (data: CreateAnnouncementRequest) =>
    request<{ id: string | number }>({
      method: 'POST',
      url: '/ops/announcements',
      data,
    }),

  /** 发布公告（DRAFT→PUBLISHED；同事务批量写目标角色站内信；非法迁移 50702） */
  publishAnnouncement: (id: string) =>
    request<void>({ method: 'POST', url: `/ops/announcements/${id}/publish` }),

  /** 下架公告（PUBLISHED→INACTIVE；已发站内信保留） */
  inactivateAnnouncement: (id: string) =>
    request<void>({ method: 'POST', url: `/ops/announcements/${id}/inactivate` }),
}
