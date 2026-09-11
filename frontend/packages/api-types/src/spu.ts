/**
 * 商户自建聚合 SPU + 结构化规格接口 TS 类型（P6 商品规格模型，V42）
 *
 * 权威来源：后端实现（单一事实源）
 *  - Controller：backend/.../product/controller/MerchantSpuController.java
 *  - DTO：MerchantSpuCreateDto / MerchantSpuUpdateDto / SkuGenerateDto / SpuSpecItemDto
 *  - VO：SpuVo / SpecDimensionVo / SkuVo
 *
 * 接口（该商户 WA 或该租户 TA 登录态；只读放宽 WK；归属与跨租户隔离在 service 内推导）：
 *  - POST /api/v1/tenant/spus?wholesalerId=        创建自建聚合 SPU（编码自动 TSPU-xxx）
 *  - PUT  /api/v1/tenant/spus/{id}                 修改（partial；specSchema 非空时整体替换）
 *  - GET  /api/v1/tenant/spus?wholesalerId=        列表（含 referencedSkuCount）
 *  - GET  /api/v1/tenant/spus/{id}                 详情（含规格模板）
 *  - POST /api/v1/tenant/spus/{id}/generate-skus   按规格模板批量生成 SKU
 *  - POST /api/v1/tenant/spus/{id}/offline         下架并级联下架其 SKU
 *
 * ⚠️ 与平台标品互不影响：本组端点只操作 `ownerType='TENANT'` 行；
 *    `/api/v1/ops/spus` 与公开目录 `/api/v1/catalog/spus` 只出 `ownerType='PLATFORM'`。
 * ⚠️ 错误码：50730 规格模板缺失 · 50731 模板非法（维度/取值重复）· 50732 组合超上限（≤60）·
 *    50733 组合重复（含存量）· 50734 组合与模板不匹配 · 50735 聚合 SPU 不存在（跨租户防枚举）·
 *    50737 自建聚合 SPU 不可手动挂接 SKU（只能经批量生成）。
 * ⚠️ 库存/单据/交易链仍按 `skus.id` 流转：聚合只影响「商品主数据」，不影响既有履约链。
 */

import type { SnowflakeId } from './common'
import type { Spu } from './ops'

/** 聚合 SPU 归属：PLATFORM=OPS 平台标品 / TENANT=租户商户自建聚合 */
export type SpuOwnerType = 'PLATFORM' | 'TENANT'

/** 规格模板维度（SpecDimensionVo）：`{ name: '包装', options: ['5L/桶', '10L/桶'] }` */
export interface SpecDimension {
  /** 维度名（≤32，同一模板内唯一） */
  name: string
  /** 该维度取值（≤20 个，非空） */
  options: string[]
}

/** 单条规格组合：维度名 → 取值（须与模板完全匹配） */
export type SpecOptions = Record<string, string>

/** 批量生成单条组合（SpuSpecItemDto；价格三件套可空 → 取请求默认三件套） */
export interface SpuSpecItem {
  options: SpecOptions
  /** 覆盖单价（>0） */
  unitPrice?: number
  /** 覆盖起批价（≥0） */
  moqPrice?: number
  /** 覆盖起批量（≥1） */
  moqQty?: number
}

/** 创建自建聚合 SPU（wholesalerId 走后端 query，非 body） */
export interface CreateMerchantSpuRequest {
  name: string
  categoryL1: string
  categoryL2: string
  brand?: string
  standardImageUrl?: string
  note?: string
  /**
   * 规格模板（必填语义；缺失/空 → 50730）。
   * 上限：≤5 维、单维 ≤20 取值、组合总数 ≤60（50731/50732）。
   */
  specSchema: SpecDimension[]
}

/** 修改自建聚合 SPU（null/缺省 = 不改） */
export interface UpdateMerchantSpuRequest {
  name?: string
  categoryL1?: string
  categoryL2?: string
  brand?: string
  standardImageUrl?: string
  note?: string
  /** 非空时整体替换模板；不影响已生成 SKU 的 specKey（存量组合保留） */
  specSchema?: SpecDimension[]
}

/**
 * 按规格模板批量生成 SKU。
 * - `items` 为空/缺省：按模板做**完整笛卡尔积**，价格取默认三件套
 * - `items` 非空：只生成给定组合，支持**逐组合覆盖价格**（不同规格价格各自独立）
 * 两种模式下与存量组合重复（同 SPU 下 specKey 相同）一律 50733，整单回滚。
 */
export interface GenerateSkuRequest {
  unitPrice: number
  moqPrice?: number
  /** 缺省 1 */
  moqQty?: number
  /** 生成后是否上架（缺省 true） */
  listed?: boolean
  /** 主图（缺省沿用 SPU 标准图） */
  mainImage?: string
  /** 单次最多 60 条 */
  items?: SpuSpecItem[]
}

/**
 * 自建聚合 SPU 视图（后端与 OPS 标品同为 `SpuVo` 契约）。
 * 复用 `Spu`（ops.ts）基础字段以杜绝两处漂移；TENANT 专属字段恒非空。
 */
export type MerchantSpu = Spu & {
  ownerType: 'TENANT'
  tenantId: SnowflakeId
  wholesalerId: SnowflakeId
  specSchema: SpecDimension[]
}
