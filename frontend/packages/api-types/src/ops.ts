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

// ============================================================
// OPS 租户（仓库）入驻审核（P0 遗留缺口补齐）
// ============================================================
/**
 * 契约：
 *  - POST /api/v1/admin/tenant/{id}/audit  ✅ 后端已实现（TenantController.audit，
 *      body: { action: 'APPROVED' | 'REJECTED', remark? }，REJECTED 时 remark 必填）
 *  - GET  /api/v1/admin/tenants?status=&page=&size=  ⚠️ 后端需补（前端按合理契约先行，
 *      OPS 分页查租户列表；status 缺省查全部）
 *
 * 租户状态机：TA 自助注册 → PENDING；OPS 审核通过 → ACTIVE（可营业，WA 入驻前置）；
 * 驳回 → REJECTED（remark 记录驳回理由）。
 */

/** 租户审核状态（tenant.status；通过后为 ACTIVE 而非 APPROVED） */
export type AdminTenantStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'

/** OPS 视角的租户（仓库）列表项 */
export interface AdminTenantItem {
  tenantId: SnowflakeId
  /** 仓库名 */
  name: string
  /** 主体名称（营业执照上的公司名，可空） */
  legalName?: string
  /** 申请人（TA 账号昵称/姓名，可空） */
  applicantName?: string
  /** 联系电话 */
  contactPhone: string
  /** 仓库地址（文本） */
  addressText?: string
  status: AdminTenantStatus
  /** 申请时间（tenant 创建时间） */
  appliedAt: string
  /** 审核时间（未审核为空） */
  auditedAt?: string | null
  /** 审核备注（驳回理由） */
  auditRemark?: string | null
}

/** OPS 租户列表分页查询参数 */
export interface AdminTenantListQuery {
  status?: AdminTenantStatus
  page?: number
  size?: number
}

/** OPS 审核租户入参（对齐后端 TenantAuditDto） */
export interface AuditTenantRequest {
  action: 'APPROVED' | 'REJECTED'
  /** REJECTED 时必填（驳回理由） */
  remark?: string
}
