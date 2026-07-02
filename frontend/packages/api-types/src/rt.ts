/**
 * RT 扫码进店（storefront）接口 TS 类型（phase-1 B2 · 公开只读）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../storefront/controller/RtStoreController.java
 *  - VO：StoreFrontVo / StoreWholesalerVo / StoreSkuVo
 *
 * 公开端点（无需登录，不在 SaInterceptor include；数据范围由 storeId/code→tenantId 解析）：
 *  - GET /api/v1/rt/store?storeId=&code=   进店页聚合（店铺 + ACTIVE 批发商 + 各自在售 SKU）
 *
 * 命名以 Rt 前缀，避免与 tenant.ts 里「撮合页 StoreFront」（另一概念）重名。
 *
 * ⚠️ 雪花 ID 字段（storeId/tenantId/wholesalerId/skuId）均为 string（后端 ToStringSerializer）。
 *    价格字段后端为 BigDecimal，序列化为 number，前端按 number 处理。
 */

import type { SnowflakeId } from './common'

/** 在售 SKU 视图（StoreSkuVo）：公开价 + 当前库存 */
export interface RtStoreSku {
  skuId: SnowflakeId
  wholesalerId: SnowflakeId
  name: string
  spec: string | null
  mainImage: string | null
  /** 公开价：单价 */
  unitPrice: number
  /** 公开价：起批价 */
  moqPrice: number
  /** 公开价：起批量 */
  moqQty: number
  /** 当前库存量（qty>0 才出现在列表中） */
  stockQty: number
}

/** 店内批发商（仅 ACTIVE）+ 在售 SKU（StoreWholesalerVo） */
export interface RtStoreWholesaler {
  wholesalerId: SnowflakeId
  name: string
  intro: string | null
  status: string
  /** 该批发商在售 SKU（listed=true 且库存>0） */
  skus: RtStoreSku[]
}

/** 进店页聚合视图（StoreFrontVo） */
export interface RtStoreFront {
  storeId: SnowflakeId
  tenantId: SnowflakeId
  /** 店铺码（= 租户简码 tenantSimpleCode，可作进店 code 复用） */
  storeCode: string
  storeName: string
  intro: string | null
  coverUrl: string | null
  businessHours: string | null
  status: string
  /** 店内批发商（仅 ACTIVE），各自带在售 SKU 列表 */
  wholesalers: RtStoreWholesaler[]
}
