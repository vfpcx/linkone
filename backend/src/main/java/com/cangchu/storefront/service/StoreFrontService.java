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
     *
     * @param storeId 店铺 id（与 code 二选一，storeId 优先）
     * @param code    店铺码（= 租户简码 tenantSimpleCode）
     */
    StoreFrontVo getStorePage(Long storeId, String code);

    /** 店内批发商列表（仅 ACTIVE），不含 SKU。供 /rt/wholesalers。 */
    List<StoreWholesalerVo> listWholesalers(Long storeId, String code);

    /** 某商户在售 SKU（含公开价 + 库存）。供 /rt/skus。 */
    List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId);
}
