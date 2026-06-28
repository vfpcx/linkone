package com.cangchu.product.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.product.dto.SkuCreateDto;
import com.cangchu.product.dto.SkuUpdateDto;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品 SKU Controller（phase-1 A2：创建 / 改 / 上下架 / 商户列表）。
 * 路径前缀 /api/v1/tenant/skus，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 写操作鉴权（WA 归属 / TA 同租户）在 SkuService 内以 user_roles 登录态推导，不信任客户端传参。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/skus")
public class SkuController {

    private final SkuService skuService;

    /** WA/TA 创建 SKU。wholesalerId 由查询参数指定，归属在 service 内校验。 */
    @PostMapping
    public R<SkuVo> create(@RequestParam Long wholesalerId, @Valid @RequestBody SkuCreateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(skuService.createSku(wholesalerId, dto, userId));
    }

    /** 修改 SKU。 */
    @PutMapping("/{id}")
    public R<SkuVo> update(@PathVariable Long id, @Valid @RequestBody SkuUpdateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(skuService.updateSku(id, dto, userId));
    }

    /** 上下架（on=true 上架 / false 下架）。 */
    @PutMapping("/{id}/listing")
    public R<SkuVo> toggleListing(@PathVariable Long id, @RequestParam boolean on) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(skuService.toggleListing(id, on, userId));
    }

    /** 商户自己看自己的 SKU（含下架）。 */
    @GetMapping
    public R<List<SkuVo>> listByWholesaler(@RequestParam Long wholesalerId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(skuService.listByWholesaler(wholesalerId, userId));
    }

    /**
     * 只读：返回本租户在售 SKU（listed=true）+ 公开价。
     * phase-1 顺手提供；正式 RT 入口由 B2 store-front 切片接入（直接调 service.listByTenantForRt）。
     * 此端点取登录态推导租户，wholesalerId 可选过滤。
     */
    @GetMapping("/listed")
    public R<List<SkuVo>> listedForRt(@RequestParam(required = false) Long wholesalerId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return R.ok(skuService.listByTenantForRt(tenantId, wholesalerId));
    }
}
