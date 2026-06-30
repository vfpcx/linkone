/**
 * 商品 SKU 接口 TS 类型（phase-1 D1a 卖家侧上架）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../product/controller/SkuController.java
 *  - DTO：SkuCreateDto / SkuUpdateDto
 *  - VO：SkuVo
 *
 * 接口（WA 或该租户 TA 登录态；归属在 service 内以 user_roles 推导）：
 *  - POST /api/v1/tenant/skus?wholesalerId=        创建（wholesalerId 走 query，非 body）
 *  - PUT  /api/v1/tenant/skus/{id}                 修改
 *  - PUT  /api/v1/tenant/skus/{id}/listing?on=     上下架（on=true 上架 / false 下架）
 *  - GET  /api/v1/tenant/skus?wholesalerId=        商户看自己的 SKU（含下架）
 *  - GET  /api/v1/tenant/skus/listed?wholesalerId= 只读：本租户在售（RT 视角，可选）
 *
 * ⚠️ 雪花 ID 字段均为 string（后端 ToStringSerializer）。
 *    价格字段后端为 BigDecimal，JSON 下发为 number/string；前端按 number 处理。
 */

import type { SnowflakeId } from './common'

/** SKU 视图对象（SkuVo） */
export interface Sku {
  id: SnowflakeId
  wholesalerId: SnowflakeId
  tenantId: SnowflakeId
  /** SPU（phase-1 可空） */
  spuId: SnowflakeId | null
  name: string
  spec: string | null
  /** 公开价：单价 */
  unitPrice: number
  /** 公开价：起批价 */
  moqPrice: number | null
  /** 公开价：起批量 */
  moqQty: number | null
  /** 是否在售（上架） */
  listed: boolean
  mainImage: string | null
  createdAt: string
}

/**
 * 创建 SKU 请求（SkuCreateDto，不含 wholesalerId —— 后端走 query 参数）。
 * 后端校验：name @NotBlank ≤128；unitPrice >0；moqPrice ≥0；moqQty ≥1。
 */
export interface CreateSkuRequest {
  name: string
  spec?: string
  unitPrice: number
  moqPrice?: number
  moqQty?: number
  mainImage?: string
}

/** 修改 SKU 请求（SkuUpdateDto，null/缺省表示不改） */
export interface UpdateSkuRequest {
  name?: string
  spec?: string
  unitPrice?: number
  moqPrice?: number
  moqQty?: number
  mainImage?: string
}
