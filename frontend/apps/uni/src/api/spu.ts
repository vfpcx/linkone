/**
 * 自建聚合商品（SPU）API（P6 · WA/TA；只读放宽 WK）。
 * 端点 /api/v1/tenant/spus（后端 MerchantSpuController；隔离在 service 内以登录态推导）。
 * 聚合 SPU 的 SKU 只能经「按规格模板批量生成」产生（50737 拒手动挂接）。
 */
import { request } from '../utils/request'
import type {
  CreateMerchantSpuRequest,
  GenerateSkuRequest,
  MerchantSpu,
  Sku,
  SnowflakeId,
  SpuCategoryGroup,
  UpdateMerchantSpuRequest,
} from '@cangchu/api-types'

export const spuApi = {
  /** 两级品类字典（登录即可读；与平台标品同源） */
  categories(): Promise<SpuCategoryGroup[]> {
    return request<SpuCategoryGroup[]>({ url: '/tenant/spus/spu-categories' })
  },

  /** 某商户的自建聚合 SPU 列表（含引用 SKU 数） */
  listMine(wholesalerId: SnowflakeId): Promise<MerchantSpu[]> {
    return request<MerchantSpu[]>({ url: '/tenant/spus', params: { wholesalerId: String(wholesalerId) } })
  },

  /** 详情（含规格模板与引用 SKU 数） */
  detail(id: SnowflakeId): Promise<MerchantSpu> {
    return request<MerchantSpu>({ url: `/tenant/spus/${encodeURIComponent(String(id))}` })
  },

  /** 创建（编码自动 TSPU-xxx；规格模板必填） */
  create(wholesalerId: SnowflakeId, body: CreateMerchantSpuRequest): Promise<MerchantSpu> {
    return request<MerchantSpu>({
      url: '/tenant/spus',
      method: 'POST',
      params: { wholesalerId: String(wholesalerId) },
      data: body,
    })
  },

  /** partial 更新（null/缺省 = 不改；specSchema 非空时整体替换模板） */
  update(id: SnowflakeId, body: UpdateMerchantSpuRequest): Promise<MerchantSpu> {
    return request<MerchantSpu>({
      url: `/tenant/spus/${encodeURIComponent(String(id))}`,
      method: 'PUT',
      data: body,
    })
  },

  /**
   * 按规格模板批量生成 SKU：items 为空 = 完整笛卡尔积 + 默认价；
   * 返回本次生成的 SKU 列表（与存量重复 50733 整单回滚）。
   */
  generateSkus(id: SnowflakeId, body: GenerateSkuRequest): Promise<Sku[]> {
    return request<Sku[]>({
      url: `/tenant/spus/${encodeURIComponent(String(id))}/generate-skus`,
      method: 'POST',
      data: body,
    })
  },

  /** 下架聚合 SPU 并级联下架其全部在售 SKU */
  offline(id: SnowflakeId): Promise<void> {
    return request<void>({ url: `/tenant/spus/${encodeURIComponent(String(id))}/offline`, method: 'POST' })
  },
}
