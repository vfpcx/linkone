/**
 * OPS 平台运营接口 TS 类型（P2 入驻生态 · 黑名单）
 *
 * 权威来源：shared/architecture/10-onboarding-design.md §1.2/§2 + §31（Wave6 分页改造，据实现编写）：
 *  - GET    /api/v1/ops/blacklist?page=&size=&status=&keyword=
 *        黑名单分页列表（Wave6 起返回 PageRecords 分页对象，records 元素即 BlacklistItem；
 *        keyword 模糊匹配 targetValue；createdAt 倒序，同刻按 id 倒序）
 *  - POST   /api/v1/ops/blacklist               加入黑名单（body: {targetType, targetValue, reason}，仍返回单条）
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

/**
 * 黑名单列表查询参数（DEF-6 · §31 分页契约，均可选）：
 * page 默认 1（>=1）/ size 默认 10（1..100 钳制）/ status 缺省查全部 /
 * keyword 模糊匹配 targetValue（手机号/执照号同列）
 */
export interface BlacklistListQuery {
  page?: number
  size?: number
  status?: BlacklistStatus
  keyword?: string
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

// ============================================================
// P5-C · OPS 平台运营控制台（21-p5c-ops-console-design；产品口径 15-p5c-ops-console）
//   GET /api/v1/ops/dashboard，requireOps，非 OPS → 42002
//   平台级统计（OPS 无租户上下文，不进 TenantLine）
// ============================================================

/** OPS 控制台聚合响应：平台规模 / 待办队列 / 今日动态 */
export interface OpsDashboardResponse {
  /** 平台规模（只读概览） */
  platform: {
    /** 营业仓库数（tenants ACTIVE，自助 + OPS 代建合计） */
    activeTenantCount: number
    /** 入驻绑定数（APPROVED 申请；一账号入驻 N 仓计 N） */
    wholesalerBindingCount: number
    /** 生效黑名单数 */
    activeBlacklistCount: number
  }
  /** 待办队列（数字与各管理页角标同口径） */
  pending: {
    /** 待审租户（→ 租户审核页） */
    pendingTenantAudits: number
    /** 待裁客诉（→ 客诉仲裁页） */
    pendingComplaints: number
    /** 公告草稿（→ 公告管理页） */
    draftAnnouncements: number
  }
  /** 今日动态（今日 0 点起） */
  today: {
    /** 今日新入驻/新注册仓库 */
    newTenantToday: number
    /** 今日新增客诉 */
    newComplaintsToday: number
  }
}

// ============================================================
// P5-D D56 · 平台标品库（16-p5-d56-catalog §4 / 22 §3）
//   管理：/api/v1/ops/spus*（requireOps，非 OPS → 42002）
//   只读目录：/api/v1/catalog/spus（登录态可见，仅 ACTIVE）
// ============================================================

/** 两级品类字典项（预置 seed，SpuCatalog；OPS 新增弹窗两级联动下拉） */
export interface SpuCategoryGroup {
  /** 一级品类（中文文本） */
  l1: string
  /** 该一级下的二级品类 */
  l2s: string[]
}

export interface Spu {
  id: string
  /** 平台编码（OPS 填 / 自动 GSPU-xxx） */
  spuCode: string
  name: string
  categoryL1: string
  categoryL2: string
  brand: string | null
  standardImageUrl: string | null
  note: string | null
  /** ACTIVE / OFFLINE / MERGED */
  status: 'ACTIVE' | 'OFFLINE' | 'MERGED'
  /** 合并源指向的新主标品（仅 MERGED 非空） */
  mergedToSpuId: string | null
  /** 引用该标品的在库 SKU 数 */
  referencedSkuCount: number
  createdAt: string
}

export interface SpuQuery {
  page: number
  size: number
  /** 名称/编码模糊 */
  keyword?: string
  categoryL1?: string
  categoryL2?: string
  status?: 'ACTIVE' | 'OFFLINE' | 'MERGED'
}

export interface CreateSpuRequest {
  name: string
  categoryL1: string
  categoryL2: string
  brand?: string
  standardImageUrl?: string
  note?: string
  /** 平台编码（可空，空则自动生成） */
  spuCode?: string
}
