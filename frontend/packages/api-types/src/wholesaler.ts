/**
 * 批发商商户接口 TS 类型（phase-1 D1a 卖家侧）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../tenant/controller/WholesalerController.java
 *  - DTO：WholesalerCreateDto / WholesalerUpdateDto
 *  - VO：WholesalerVo
 *
 * 接口（均需 TA 登录态，tenantId 由后端登录态推导，前端不传）：
 *  - POST /api/v1/tenant/wholesalers          创建（TA 自营建商户）
 *  - PUT  /api/v1/tenant/wholesalers/{id}      改资料（仅 license / intro）
 *  - GET  /api/v1/tenant/wholesalers           列出本租户商户
 *
 * ⚠️ 雪花 ID 字段均为 string（后端 ToStringSerializer）。
 */

import type { SnowflakeId } from './common'

/** 商户视图对象（WholesalerVo） */
export interface Wholesaler {
  id: SnowflakeId
  tenantId: SnowflakeId
  name: string
  /** 商户负责人（创建者）用户 ID */
  ownerUserId: SnowflakeId
  license: string | null
  intro: string | null
  /** 状态：phase-1 后端创建即 ACTIVE（具体取值以后端为准） */
  status: string
  /** 来源：自营 SELF_OPERATED 等（以后端为准） */
  source: string
  /** WA 账号对应的 user_roles.id（开通 waPhone 时返回，否则 null） */
  waUserId: SnowflakeId | null
  createdAt: string
}

/** 创建商户请求（WholesalerCreateDto）：name @NotBlank 必填，其余可选 */
export interface CreateWholesalerRequest {
  name: string
  license?: string
  intro?: string
  /** 商户负责人手机号；传入则后端建/绑一个 WA 角色 */
  waPhone?: string
}

/** 改资料请求（WholesalerUpdateDto）：phase-1 仅 license / intro */
export interface UpdateWholesalerRequest {
  license?: string
  intro?: string
}
