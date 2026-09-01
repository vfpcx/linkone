package com.cangchu.tenant.service;

import com.cangchu.tenant.vo.StorefrontFeatureVo;

import java.util.List;

/**
 * 店铺撮合配置服务（P5-A W4，18-p5-design §2.2/§3.1/§4.3/§6；归属 tenant 域）。
 *
 * <p>storefront_featured 仅 tenant 域读写；storefront 域只读消费经 {@link #getFeatured}（G-S2 服务出口），
 * 禁止跨域 mapper 直连、禁止跨域 entity 直用。
 */
public interface StorefrontFeatureService {

    /**
     * 读撮合配置（只读出口，供 storefront 域聚合消费 + tenant 回显内部复用；无鉴权——RT 匿名浏览路径）。
     * 调用方负责传入可信 tenantId（storefront 由 stores 真实归属解析）；隔离在 Service 内仍显式 eq(tenantId)。
     *
     * @return 有序主推商品 id 列表 + 置顶批发商 id 列表（按 sort_order 升序；无配置返回空列表）
     */
    StorefrontFeatureVo getFeatured(Long tenantId, Long storeId);

    /**
     * 回显当前店铺撮合配置（TA 鉴权）。
     * S4：operator 须为该 tenant 的 ACTIVE TA（user_roles 为唯一可信来源），且 tenant 已有店铺（store 1:1）。
     */
    StorefrontFeatureVo getMyFeatured(Long tenantId, Long operatorUserId);

    /**
     * 覆盖保存（TA 鉴权，{@code @Transactional} 同事务先删后插，幂等）。
     * 校验：mainSkuIds ≤ 20（50711）、pinWaIds ≤ 5（50712）、重复项（50713）、
     * 引用须为本店/本租户在售（listed=true）SKU 或本店入驻 ACTIVE 批发商（50714）；均写前校验。
     *
     * @param mainSkuIds 主推商品 id 有序列表（可空=清空）
     * @param pinWaIds   置顶批发商 id 有序列表（可空=清空）
     */
    void saveMyFeatured(Long tenantId, Long operatorUserId, List<Long> mainSkuIds, List<Long> pinWaIds);
}
