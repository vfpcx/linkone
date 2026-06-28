package com.cangchu.inventory.controller;

import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.cangchu.common.response.R;

import java.util.List;

/**
 * 库存只读查询 Controller（phase-1 B1）。
 *
 * <p>路径前缀 {@code /api/v1/tenant/inventories}，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 仅登录态租户内查询：库存行受 TenantLine 兜底隔离（inventories 在白名单内），
 * 跨租户库存不可见。
 *
 * <p><b>不</b>暴露公开的加/扣库存 HTTP——入/出库一律由 C document 单据调 service，避免绕过单据。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/inventories")
public class InventoryController {

    private final InventoryService inventoryService;

    /** 查询本租户库存；wholesalerId / skuId 可选过滤。 */
    @GetMapping
    public R<List<InventoryVo>> query(@RequestParam(required = false) Long wholesalerId,
                                      @RequestParam(required = false) Long skuId) {
        return R.ok(inventoryService.queryInventory(wholesalerId, skuId));
    }

    /** 列出某商户当前有货（qty>0）的库存（供联调/前端展示）。 */
    @GetMapping("/in-stock")
    public R<List<InventoryVo>> inStock(@RequestParam Long wholesalerId) {
        return R.ok(inventoryService.listInStockSkusFor(wholesalerId));
    }
}
