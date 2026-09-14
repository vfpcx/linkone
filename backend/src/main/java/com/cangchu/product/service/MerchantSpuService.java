package com.cangchu.product.service;

import com.cangchu.product.dto.MerchantSpuCreateDto;
import com.cangchu.product.dto.MerchantSpuUpdateDto;
import com.cangchu.product.dto.SkuGenerateDto;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.product.vo.SpuCategoryGroupVo;
import com.cangchu.product.vo.SpuVo;

import java.util.List;

/**
 * 商户自建聚合 SPU 服务（P6 商品规格模型，V42）。
 *
 * <p>归属模型：「混合 SPU」——OPS 平台标品（{@code owner_type='PLATFORM'}，P5-D D56 既有语义）
 * 与租户/商户自建聚合 SPU（{@code owner_type='TENANT'}，本服务）共存于 spus 表。
 * 库存/单据/交易链仍一律按 skus.id 流转，本模块只影响「商品档案」层。
 *
 * <p>安全规约：
 * <ul>
 *   <li>S4 归属鉴权：写路径要求该商户的 WA（{@code role=WA & wholesaler_id=目标 & ACTIVE}）
 *       或该商户所属租户的 TA；读路径放宽 WK（SkuServiceImpl 先例）。</li>
 *   <li>隔离：spus 为平台级表未纳入 TenantLine，故一律经
 *       {@code owner_type='TENANT' AND tenant_id=<可信租户>} 显式过滤；跨租户/平台标品
 *       一律以 50735 假装不存在（防枚举）。</li>
 * </ul>
 */
public interface MerchantSpuService {

    /**
     * 创建自建聚合 SPU（owner_type=TENANT；规格模板必填）。
     *
     * @param wholesalerId 所属商户（归属以其真实 tenant_id 为准，不信任客户端租户入参）
     * @param operatorId   操作人（该商户 WA 或该租户 TA）
     */
    SpuVo create(Long wholesalerId, MerchantSpuCreateDto dto, Long operatorId);

    /** 更新（partial：null 字段保持原值；规格模板整体替换）。非 ACTIVE → 50722。 */
    SpuVo update(Long spuId, MerchantSpuUpdateDto dto, Long operatorId);

    /** 某商户的自建聚合 SPU 列表（含引用 SKU 数）。 */
    List<SpuVo> list(Long wholesalerId, Long operatorId);

    /** 详情（含规格模板与引用 SKU 数）。 */
    SpuVo detail(Long spuId, Long operatorId);

    /**
     * 按规格模板批量生成 SKU：items 为空 → 完整笛卡尔积 + 默认价；items 非空 → 只生成指定组合
     * （可逐组合覆盖价格）。与存量组合重复 → 50733（整单回滚）。
     */
    List<SkuVo> generateSkus(Long spuId, SkuGenerateDto dto, Long operatorId);

    /** 下架自建聚合 SPU（ACTIVE → OFFLINE）并级联下架其全部 SKU。 */
    void offline(Long spuId, Long operatorId);

    /**
     * 两级品类字典（复用平台标品 SpuCatalog 唯一事实源，保持与标品同口径）。
     * 登录即可读（预置公开口径数据，无敏感性；商户端建 SPU 两级联动下拉用）。
     */
    List<SpuCategoryGroupVo> categories();
}
