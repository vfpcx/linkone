package com.cangchu.pricing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.dto.UpdateCustomerPriceDto;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.pricing.vo.CustomerPriceVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客户专属价 Controller（P2 定价 Wave 1）。
 * 路径前缀 /api/v1/tenant/customer-prices，已被 SaInterceptor 登录拦截覆盖。
 * 写操作鉴权（WA 归属 / TA 同租户）在 PricingService 内以 user_roles 登录态推导，不信任客户端传参。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/customer-prices")
public class PricingController {

    private final PricingService pricingService;

    /** 设置客户专属价（upsert）。 */
    @PostMapping
    public R<CustomerPriceVo> set(@Valid @RequestBody SetCustomerPriceDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.setCustomerPrice(dto, userId));
    }

    /** 列出某商户的专属价。 */
    @GetMapping
    public R<List<CustomerPriceVo>> list(@RequestParam Long wholesalerId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.listCustomerPrices(wholesalerId, userId));
    }

    /** 部分更新专属价。 */
    @PatchMapping("/{id}")
    public R<CustomerPriceVo> update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerPriceDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.updateCustomerPrice(id, dto, userId));
    }

    /** 作废专属价（DISABLED）。 */
    @DeleteMapping("/{id}")
    public R<Void> revoke(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        pricingService.revokeCustomerPrice(id, userId);
        return R.ok();
    }
}
