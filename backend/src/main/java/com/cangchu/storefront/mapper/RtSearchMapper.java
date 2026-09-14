package com.cangchu.storefront.mapper;

import com.cangchu.storefront.vo.RtSkuSearchItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * RT 首页「搜商品找仓库」跨仓公开检索（US-RT-06 后续 · 先搜货再进店）。
 *
 * <p>storefront 是聚合域（G-S2：聚合查询落在本域 mapper，不侵入各业务域 mapper）——
 * 本查询跨 skus/inventories/wholesalers/tenants/stores 五表只读 JOIN，
 * 与 {@link com.cangchu.storefront.service.impl.StoreFrontServiceImpl} 的内存聚合同源同责。
 *
 * <p>安全口径（G-1.2/G-2.1）：公开端点无 TenantContext，TenantLine 兜底不注入条件（本就全平台可见）；
 * 结果只含公开字段（公开价/库存/店铺归属），不含任何 PII——联系方式仍走询价确认后的 phone-reveal。
 *
 * <p>检索范围：已上架(listed) 且 有货(qty&gt;0) 的 SKU，且其商户 ACTIVE、租户 ACTIVE
 * （与进店浏览 {@code buildOnSaleSkus} 的在售口径一致）。
 * LIKE 通配符经 service 层转义，SQL 侧固定 ESCAPE '!'（MySQL / H2(MySQL 模式) 通用，
 * 不用反斜杠——两库对字符串字面量反斜杠语义不同）。
 */
@Mapper
public interface RtSearchMapper {

    /**
     * 跨仓按关键词检索在售 SKU（名称/规格/标品名快照模糊匹配）。
     *
     * @param kw    已转义的关键词（service 层保证非空）
     * @param lat   当前纬度（可空=不按距离排序，distanceMeters 为 null）
     * @param lng   当前经度（可空）
     * @param limit 返回上限
     */
    @Select("""
            SELECT
                k.id AS skuId,
                k.name AS name,
                k.spec AS spec,
                k.main_image AS mainImage,
                k.unit_price AS unitPrice,
                k.moq_price AS moqPrice,
                k.moq_qty AS moqQty,
                i.qty AS stockQty,
                w.id AS wholesalerId,
                w.name AS wholesalerName,
                t.id AS tenantId,
                s.id AS storeId,
                s.name AS storeName,
                t.tenant_simple_code AS tenantSimpleCode,
                CASE
                    WHEN s.lng IS NOT NULL AND s.lat IS NOT NULL AND #{lng} IS NOT NULL AND #{lat} IS NOT NULL THEN
                        ROUND(6371000 * 2 * ASIN(SQRT(
                            POWER(SIN((s.lat - #{lat}) * PI() / 360), 2) +
                            COS(#{lat} * PI() / 180) * COS(s.lat * PI() / 180) *
                            POWER(SIN((s.lng - #{lng}) * PI() / 360), 2)
                        )))
                    ELSE NULL
                END AS distanceMeters
            FROM skus k
            JOIN inventories i ON i.sku_id = k.id
                AND i.wholesaler_id = k.wholesaler_id
                AND i.tenant_id = k.tenant_id
                AND i.qty > 0
            JOIN wholesalers w ON w.id = k.wholesaler_id
                AND w.tenant_id = k.tenant_id
                AND w.status = 'ACTIVE'
                AND w.deleted_at IS NULL
            JOIN tenants t ON t.id = k.tenant_id
                AND t.status = 'ACTIVE'
                AND t.deleted_at IS NULL
            JOIN stores s ON s.tenant_id = t.id
                AND s.deleted_at IS NULL
            WHERE k.deleted_at IS NULL
              AND k.listed = TRUE
              AND (
                    k.name LIKE CONCAT('%', #{kw}, '%') ESCAPE '!'
                 OR k.spec LIKE CONCAT('%', #{kw}, '%') ESCAPE '!'
                 OR k.spu_name LIKE CONCAT('%', #{kw}, '%') ESCAPE '!'
              )
            ORDER BY
                CASE
                    WHEN s.lng IS NOT NULL AND s.lat IS NOT NULL AND #{lng} IS NOT NULL AND #{lat} IS NOT NULL THEN 0
                    ELSE 1
                END,
                distanceMeters ASC,
                k.created_at DESC
            LIMIT #{limit}
            """)
    List<RtSkuSearchItemVo> selectRtSkuSearch(@Param("kw") String kw,
                                              @Param("lat") BigDecimal lat,
                                              @Param("lng") BigDecimal lng,
                                              @Param("limit") int limit);
}
