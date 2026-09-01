package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.tenant.dto.StorefrontFeatureSaveDto;
import com.cangchu.tenant.service.StorefrontFeatureService;
import com.cangchu.tenant.vo.StorefrontFeatureVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 店铺撮合配置 Controller（P5-A W4，18-p5-design §4.3）。
 * 路径前缀 /api/v1/tenant/storefront/featured，已被 SaInterceptor 登录拦截覆盖。
 * tenantId 一律取登录态推导的可信租户（TenantContext），不接受客户端传入。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/storefront/featured")
public class StorefrontFeatureController {

    private final StorefrontFeatureService storefrontFeatureService;

    /** 回显当前店铺主推商品 / 置顶批发商 id 列表（有序，按 sort_order） */
    @GetMapping
    public R<StorefrontFeatureVo> get() {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(storefrontFeatureService.getMyFeatured(tenantId, userId));
    }

    /** 覆盖保存主推商品 / 置顶批发商（先删后插，同事务；写前校验 50711-50714，幂等） */
    @PutMapping
    public R<Void> save(@RequestBody StorefrontFeatureSaveDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        storefrontFeatureService.saveMyFeatured(tenantId, userId, dto.getMainSkuIds(), dto.getPinWaIds());
        return R.ok();
    }

    /** 取登录态推导的可信租户；TA 未绑定租户时拒绝 */
    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户，请先完成建仓");
        }
        return tenantId;
    }
}
