/**
 * 价格管理接口 TS 类型（P2 · 客户专属价 / 批量调价 / 调价历史）
 *
 * 权威来源：后端实现（单一事实源，Waves 1&2 已完成）
 *  - 客户专属价：CustomerPriceController
 *      GET    /tenant/customer-prices?wholesalerId=          列出
 *      POST   /tenant/customer-prices                        设置
 *      PATCH  /tenant/customer-prices/{id}                   编辑
 *      DELETE /tenant/customer-prices/{id}                   撤销（→ DISABLED）
 *  - 批量调价：
 *      POST   /tenant/skus/batch-price-update                公开价批量
 *      POST   /tenant/customer-prices/batch-update           专属价批量
 *  - 调价历史：
 *      GET    /tenant/price-change-logs?wholesalerId=&changeType=
 *
 * ⚠️ 雪花 ID 字段均为 string（http.ts safeJsonParse 已防精度丢失）。
 *    金额字段两位小数，后端下发 number/string，前端按 number 处理。
 */

import type { SnowflakeId } from './common'

/** 专属价状态 */
export type PriceStatus = 'ACTIVE' | 'DISABLED' | 'EXPIRED'

/** 专属价来源：手工设置 / 由询价生成 */
export type PriceSource = 'manual' | 'from_inquiry'

/**
 * 调价模式：
 *  - PCT_UP / PCT_DOWN  百分比涨/跌，value=percent（10 表示 ±10%）
 *  - SET_VALUE / DELTA  设为定值 / 增减定额，value=amount
 *  - DISABLE / SET_EXPIRE  仅专属价批量可用（禁用 / 设置过期时间）
 */
export type AdjustMode =
  | 'PCT_UP'
  | 'PCT_DOWN'
  | 'SET_VALUE'
  | 'DELTA'
  | 'DISABLE'
  | 'SET_EXPIRE'

/** 调价历史类型：公开价 / 客户专属价 */
export type ChangeType = 'PUBLIC_PRICE' | 'CUSTOMER_PRICE'

/** 公开价批量调价目标字段 */
export type PriceTargetField = 'unitPrice' | 'moqPrice'

/** 客户专属价视图对象（CustomerPriceVo） */
export interface CustomerPriceVo {
  id: SnowflakeId
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  /** RT 手机号（专属价绑定对象） */
  rtPhone: string
  /** 专属单价 */
  unitPrice: number
  status: PriceStatus
  source: PriceSource
  /** 过期时间（ISO，可空表示长期有效） */
  expireAt?: string
  createdAt: string
}

/** 设置专属价请求（POST /tenant/customer-prices） */
export interface SetCustomerPriceRequest {
  wholesalerId: SnowflakeId
  skuId: SnowflakeId
  rtPhone: string
  unitPrice: number
  expireAt?: string
}

/** 编辑专属价请求（PATCH /tenant/customer-prices/{id}，缺省表示不改） */
export interface UpdateCustomerPriceRequest {
  unitPrice?: number
  expireAt?: string
  status?: PriceStatus
}

/** 公开价批量调价请求（POST /tenant/skus/batch-price-update） */
export interface BatchPublicPriceRequest {
  wholesalerId: SnowflakeId
  /** 目标 SKU（1..200） */
  skuIds: SnowflakeId[]
  adjustMode: Extract<AdjustMode, 'PCT_UP' | 'PCT_DOWN' | 'SET_VALUE' | 'DELTA'>
  value: number
  /** 调整字段，默认 unitPrice */
  targetField?: PriceTargetField
}

/** 客户专属价批量更新请求（POST /tenant/customer-prices/batch-update） */
export interface BatchCustomerPriceRequest {
  wholesalerId: SnowflakeId
  /** 显式指定专属价 id（1..500）；或用 skuId / rtPhone 圈选 */
  ids?: SnowflakeId[]
  skuId?: SnowflakeId
  rtPhone?: string
  adjustMode: AdjustMode
  /** PCT_UP·PCT_DOWN·SET_VALUE·DELTA 需要；DISABLE 不需要 */
  value?: number
  /** SET_EXPIRE 需要 */
  expireAt?: string
}

/** 批量调价结果（BatchPriceResultVo） */
export interface BatchPriceResultVo {
  batchNo: string
  affectedCount: number
  rejectedCount: number
  rejectedIds: SnowflakeId[]
}

/** 调价历史视图对象（PriceChangeLogVo） */
export interface PriceChangeLogVo {
  id: SnowflakeId
  batchNo: string
  changeType: ChangeType
  adjustMode: AdjustMode
  affectedCount: number
  /** 目标摘要（如"12 个 SKU" / "手机号 138****"） */
  targetSummary: string
  createdAt: string
  operatorUserId: SnowflakeId
}
