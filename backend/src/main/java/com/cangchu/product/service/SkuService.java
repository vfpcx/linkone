package com.cangchu.product.service;

import com.cangchu.product.dto.SkuCreateDto;
import com.cangchu.product.dto.SkuUpdateDto;
import com.cangchu.product.vo.SkuVo;

import java.util.List;

/**
 * 商品 SKU 服务（phase-1 A2）。
 */
public interface SkuService {

    /** WA（该商户）或 TA（该租户）创建 SKU；校验 wholesaler 属当前租户。 */
    SkuVo createSku(Long wholesalerId, SkuCreateDto dto, Long operatorUserId);

    /** 修改 SKU（归属校验：操作者须为该 SKU 所属商户的 WA 或租户 TA）。 */
    SkuVo updateSku(Long skuId, SkuUpdateDto dto, Long operatorUserId);

    /** 上/下架（归属校验同 update）。 */
    SkuVo toggleListing(Long skuId, boolean on, Long operatorUserId);

    /** 商户自己看自己的 SKU（含下架）。 */
    List<SkuVo> listByWholesaler(Long wholesalerId, Long operatorUserId);

    /**
     * 供 B2 store-front 调用：返回本租户**仅 listed=true** 的 SKU + 公开价。
     * phase-1 无专属价，直接公开价。
     *
     * @param tenantId    租户 id（由调用方从登录态推导后传入）
     * @param wholesalerId 可空；非空则只取该商户的在售 SKU
     */
    List<SkuVo> listByTenantForRt(Long tenantId, Long wholesalerId);
}
