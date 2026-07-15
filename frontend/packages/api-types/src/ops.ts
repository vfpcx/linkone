/**
 * OPS 平台运营接口 TS 类型（P2 入驻生态 · 黑名单）
 *
 * 契约（shared/architecture/04-api-spec.md §331-333 + 03-database-schema.sql blacklist）：
 *  - GET    /api/v1/ops/blacklist?page=&size=   黑名单分页列表
 *  - POST   /api/v1/ops/blacklist               加入黑名单（body: phone?/license?/reason，phone 与 license 至少一键）
 *  - DELETE /api/v1/ops/blacklist/{id}          移除黑名单
 *
 * 黑名单为平台级共享（不走 TenantLine），手机号 / 营业执照号双键；
 * 入驻申请 + OPS 代建均必检，命中返回 50205（STATE_WA_BLACKLISTED）。
 */

import type { SnowflakeId } from './common'

/** 黑名单键类型：手机号 / 营业执照号 */
export type BlacklistTargetType = 'PHONE' | 'LICENSE_NO'

export type BlacklistStatus = 'ACTIVE' | 'REMOVED'

/** 黑名单条目（对齐 blacklist 表） */
export interface BlacklistItem {
  id: SnowflakeId
  targetType: BlacklistTargetType
  targetValue: string
  reason: string
  /** 证据 OSS URL 列表（后端 JSON 数组，可空） */
  evidenceUrls?: string[]
  /** OPS 操作人 */
  operatorUserId?: SnowflakeId
  status?: BlacklistStatus
  createdAt: string
  removedAt?: string | null
}

/** 加入黑名单入参：phone / license 至少填一个，reason 必填 */
export interface CreateBlacklistRequest {
  phone?: string
  license?: string
  reason: string
}

/** 黑名单分页查询参数 */
export interface BlacklistListQuery {
  page?: number
  size?: number
}
