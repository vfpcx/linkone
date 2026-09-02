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
  /**
   * 命中的客户专属价（P2）：当该买家（手机号）对本 SKU 存在生效中的专属价时下发，
   * 为客户专属单价；缺省/为 null 表示无专属价，按公开价 unitPrice 成交。
   * 后端 BigDecimal 序列化为 string（保两位小数）。
   */
  matchedPrice?: string | null
  /** 当前库存量（qty>0 才出现在列表中） */
  stockQty: number
  /** 是否主推商品（P5-A W4 · 撮合配置标记，店铺页「主推」标） */
  featured?: boolean
}

/** 店内批发商（仅 ACTIVE）+ 在售 SKU（StoreWholesalerVo） */
export interface RtStoreWholesaler {
  wholesalerId: SnowflakeId
  name: string
  intro: string | null
  status: string
  /** 该批发商在售 SKU（listed=true 且库存>0） */
  skus: RtStoreSku[]
  /** 是否置顶批发商（P5-A W4 · 撮合配置标记，店铺页前置 + 标） */
  pinned?: boolean
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
  /** 主推商品 SKU id 序（P5-A W4 · 撮合配置，服务端已按主推前置排序） */
  featuredSkuIds?: SnowflakeId[]
  /** 置顶批发商 id 序（P5-A W4 · 撮合配置，服务端已按置顶前置排序） */
  pinnedWholesalerIds?: SnowflakeId[]
}

// ============ C1 · RT「我的价目」（专属价复购，architecture/23-p5-c-c1 §4.1） ============

/**
 * 「我的价目」单行（RtPriceItemVo）：当前店为输入手机号维护的客户专属价。
 * customerPrice=专属价现值（主价）；unitPrice/moqPrice/moqQty=公开价对照；
 * listed=false 表示 SKU 已下架（行仍展示但置灰禁提交）；库存 0 不拦询价（可缺货询）。
 */
export interface RtPriceItem {
  skuId: SnowflakeId
  name: string
  spec: string | null
  mainImage: string | null
  /** 公开价：单价（对照，划线展示） */
  unitPrice: number
  /** 公开价：起批价（对照） */
  moqPrice: number
  /** 公开价：起批量（对照） */
  moqQty: number
  /** 当前库存量（0=缺货，可询） */
  stockQty: number
  /** 专属价现值（价目行主价） */
  customerPrice: number
  /** 专属价失效时间（空=永久有效） */
  expireAt: string | null
  /** 专属价来源：manual=商户设定 / from_inquiry=议价沉淀 */
  source: 'manual' | 'from_inquiry'
  /** false=SKU 已下架（置灰禁提交） */
  listed: boolean
}

/** 「我的价目」组（RtPriceGroupVo）：某批发商下该客户的有效专属价行 */
export interface RtPriceGroup {
  wholesalerId: SnowflakeId
  name: string
  /** 该客户在此商户的有效专属价行（createdAt 倒序；含下架行 listed=false） */
  items: RtPriceItem[]
}

/** 「我的价目」响应（RtPriceListVo）：仅回尾号作归属提示，不含明文手机号 */
export interface RtPriceList {
  /** 价目归属提示：手机号尾号 4 位 */
  rtPhoneLast4: string
  /** 有专属价目的店内批发商（无价目商户不出组） */
  wholesalers: RtPriceGroup[]
}

/** 「我的价目」查询入参（MyPriceListQueryDto）：手机号放 POST body，防明文落 GET 日志 */
export interface MyPriceListRequest {
  /** 店铺码（= 租户简码） */
  code: string
  /** RT 手机号 */
  rtPhone: string
}
