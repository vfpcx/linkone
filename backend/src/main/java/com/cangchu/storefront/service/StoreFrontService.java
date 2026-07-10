package com.cangchu.storefront.service;

import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;

import java.util.List;

/**
 * RT 店铺前台聚合服务（phase-1 B2 · 只读浏览）。
 *
 * <p>RT 扫码进店无登录态/无 TenantContext，TenantLine 兜底不注入 tenant 条件，
 * 故本服务**先解析出 tenantId**，再以该 tenantId 显式过滤所有下游查询（复用 A2 的
 * {@code listByTenantForRt(tenantId,...)} 等带租户入参方法），杜绝跨店数据泄漏。
 *
 * <p>聚合口径：店内 WA（仅 ACTIVE）→ 每个 WA 的在售 SKU（listed=true 且 库存 qty>0）+ 公开价 + 当前库存。
 */
public interface StoreFrontService {

    /**
     * 进店页：按 storeId 或店铺码(tenantSimpleCode)解析到 tenant，聚合返回整页。
     * 匿名口径（无 RT 身份 → 仅公开价）。供 document 域 submitByRt 等匿名调用复用。
     *
     * @param storeId 店铺 id（与 code 二选一，storeId 优先）
     * @param code    店铺码（= 租户简码 tenantSimpleCode）
     */
    StoreFrontVo getStorePage(Long storeId, String code);

    /**
     * 进店页（P2 定价 Wave 3b · 可选鉴权）：附带 RT 身份解析专属价。
     * {@code rtPhone} 非空且命中有效专属价 → 各 SKU 的 {@code matchedPrice} 置为专属价（不同于公开价才置）；
     * {@code rtPhone} 为空（匿名）→ 全部走公开价，{@code matchedPrice} 恒 null。
     *
     * @param rtPhone 已登录 RT 的手机号；匿名传 null
     */
    StoreFrontVo getStorePage(Long storeId, String code, String rtPhone);

    /** 店内批发商列表（仅 ACTIVE），不含 SKU。供 /rt/wholesalers。 */
    List<StoreWholesalerVo> listWholesalers(Long storeId, String code);

    /** 某商户在售 SKU（含公开价 + 库存）。匿名口径（仅公开价）。供 /rt/skus 匿名访问。 */
    List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId);

    /**
     * 某商户在售 SKU（P2 定价 Wave 3b · 可选鉴权）：附带 RT 身份解析专属价。
     * 语义同 {@link #getStorePage(Long, String, String)} 的 matchedPrice 口径。供 /rt/skus 登录态访问。
     *
     * @param rtPhone 已登录 RT 的手机号；匿名传 null
     */
    List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId, String rtPhone);
}
