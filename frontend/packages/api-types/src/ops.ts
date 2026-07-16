/**
 * OPS 平台运营接口 TS 类型（P2 入驻生态 · 黑名单）
 *
 * 权威来源：shared/architecture/10-onboarding-design.md §1.2/§2（据实现编写）：
 *  - GET    /api/v1/ops/blacklist?status=       黑名单列表（返回裸数组，可按 status 过滤）
 *  - POST   /api/v1/ops/blacklist               加入黑名单（body: {targetType, targetValue, reason}）
 *  - DELETE /api/v1/ops/blacklist/{id}          移除黑名单（置 REMOVED，保留追溯）
 *
 * 黑名单为平台级共享（不走 TenantLine），手机号 / 营业执照号双键，
 * `uk_blacklist_type_value(target_type,target_value)` 唯一；
 * 三条入驻路径（自助 / OPS 代建 / TA 自营）均必检，命中返回 50205。
 * 错误码：50310 条目已存在（重复加黑）/ 50311 条目不存在。
 */

import type { SnowflakeId } from './common'

/** 黑名单键类型：手机号 / 营业执照号 */
export type BlacklistTargetType = 'PHONE' | 'LICENSE_NO'

/** ACTIVE 生效中 / REMOVED 已解除（不物理删，保留追溯） */
export type BlacklistStatus = 'ACTIVE' | 'REMOVED'

/** 黑名单条目（对齐 blacklist 表） */
export interface BlacklistItem {
  id: SnowflakeId
  targetType: BlacklistTargetType
  /** 被拉黑的值（手机号或执照号） */
  targetValue: string
  reason: string
  /** OPS 操作人 */
  operatorUserId?: SnowflakeId
  status?: BlacklistStatus
  createdAt?: string
  /** 解除时间 */
  removedAt?: string | null
}

/** 加入黑名单入参（targetType + targetValue + reason 均必填） */
export interface CreateBlacklistRequest {
  targetType: BlacklistTargetType
  targetValue: string
  reason: string
}

/** 黑名单列表查询参数（无分页，返回裸数组） */
export interface BlacklistListQuery {
  status?: BlacklistStatus
}
