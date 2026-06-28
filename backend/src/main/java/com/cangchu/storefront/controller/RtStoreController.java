package com.cangchu.storefront.controller;

import com.cangchu.common.response.R;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RT 扫码进店浏览 Controller（phase-1 B2 · 公开只读）。
 *
 * <p>路径前缀 {@code /api/v1/rt/**}：<b>不</b>在 {@code SaTokenConfig} 的 SaInterceptor include 列表内，
 * 故默认开放、无需登录（符合 G-1.2：用真实前缀声明开放归属）。RT 无登录态/无 TenantContext，
 * 数据范围完全由 service 内 storeId/code→tenantId 的解析 + 显式租户过滤决定，
 * <b>不</b>接受 X-Tenant-Id 决定数据（防跨店泄漏）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rt")
public class RtStoreController {

    private final StoreFrontService storeFrontService;

    /** 进店页：店铺信息 + 店内 ACTIVE 批发商 + 各自在售 SKU（含公开价+库存）。storeId 优先，否则用 code。 */
    @GetMapping("/store")
    public R<StoreFrontVo> store(@RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) String code) {
        return R.ok(storeFrontService.getStorePage(storeId, code));
    }

    /** 店内批发商列表（仅 ACTIVE，不含 SKU）。 */
    @GetMapping("/wholesalers")
    public R<List<StoreWholesalerVo>> wholesalers(@RequestParam(required = false) Long storeId,
                                                  @RequestParam(required = false) String code) {
        return R.ok(storeFrontService.listWholesalers(storeId, code));
    }

    /** 某商户在售 SKU（含公开价 + 当前库存）。 */
    @GetMapping("/skus")
    public R<List<StoreSkuVo>> skus(@RequestParam(required = false) Long storeId,
                                    @RequestParam(required = false) String code,
                                    @RequestParam Long wholesalerId) {
        return R.ok(storeFrontService.listSkus(storeId, code, wholesalerId));
    }
}
