package com.cangchu.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.product.service.SpuService;
import com.cangchu.product.vo.SpuVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台标品目录（只读，登录态可见——非 OPS 专属，22 §3.1 扩展）。
 *
 * <p>US-OPS-02「平台可见」语义：TA/WA 建 SKU「选择标品」需在非 OPS 端点搜 ACTIVE 标品
 * （OPS 端点 requireOps → TA 调 42002），故独立 /api/v1/catalog/spus（SA-Token 登录即过，角色不限）。
 */
@RestController
@RequestMapping("/api/v1/catalog/spus")
@RequiredArgsConstructor
public class CatalogSpuController {

    private final SpuService spuService;

    /** 仅 ACTIVE 标品；keyword 名称/编码模糊。供各端下拉/选择器（选标品）。 */
    @GetMapping
    public R<Page<SpuVo>> search(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String keyword) {
        return R.ok(spuService.searchActive(page, size, keyword));
    }
}
