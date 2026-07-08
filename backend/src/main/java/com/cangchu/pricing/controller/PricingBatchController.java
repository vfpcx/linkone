package com.cangchu.pricing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.pricing.dto.BatchCustomerPriceDto;
import com.cangchu.pricing.dto.BatchPublicPriceDto;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.pricing.vo.BatchPriceResultVo;
import com.cangchu.pricing.vo.PriceChangeLogVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 批量调价 + 调价历史 Controller（P2 定价 Wave 2）。
 *
 * <p>三个端点跨 skus / customer-prices / price-change-logs 三个路径前缀，故独立成类
 * （避免与 Wave 1 {@link PricingController} 的类级 @RequestMapping 冲突），方法用绝对路径。
 * 均已被 SaInterceptor 登录拦截覆盖；归属/防重/并发护栏在 PricingService 内实现，不信任客户端传参。
 */
@RestController
@RequiredArgsConstructor
public class PricingBatchController {

    private final PricingService pricingService;

    /** 批量调公开价（PCT_UP/PCT_DOWN/SET_VALUE/DELTA，目标 unitPrice/moqPrice）。 */
    @PostMapping("/api/v1/tenant/skus/batch-price-update")
    public R<BatchPriceResultVo> batchPublic(@Valid @RequestBody BatchPublicPriceDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.batchUpdatePublicPrice(dto, userId));
    }

    /** 批量调专属价（六种 adjustMode 全适用）。 */
    @PostMapping("/api/v1/tenant/customer-prices/batch-update")
    public R<BatchPriceResultVo> batchCustomer(@Valid @RequestBody BatchCustomerPriceDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.batchUpdateCustomerPrice(dto, userId));
    }

    /** 调价历史查询（changeType 可空过滤，最新在前）。 */
    @GetMapping("/api/v1/tenant/price-change-logs")
    public R<List<PriceChangeLogVo>> logs(@RequestParam Long wholesalerId,
                                          @RequestParam(required = false) String changeType) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(pricingService.listPriceChangeLogs(changeType, wholesalerId, userId));
    }
}
