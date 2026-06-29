package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerUpdateDto;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 批发商商户 Controller（phase-1 A1：创建 / 改资料 / 列表）。
 * 路径前缀 /api/v1/tenant/wholesalers，已被 SaInterceptor 登录拦截覆盖。
 * tenantId 一律取登录态推导的可信租户（TenantContext），不接受客户端传入。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/wholesalers")
public class WholesalerController {

    private final WholesalerService wholesalerService;

    /** TA 自营创建批发商商户 */
    @PostMapping
    public R<WholesalerVo> create(@Valid @RequestBody WholesalerCreateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(wholesalerService.createSelfOperated(tenantId, dto, userId));
    }

    /** 修改商户资料（intro / license） */
    @PutMapping("/{id}")
    public R<WholesalerVo> update(@PathVariable Long id, @Valid @RequestBody WholesalerUpdateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(wholesalerService.updateProfile(id, userId, dto));
    }

    /** 列出本租户商户 */
    @GetMapping
    public R<List<WholesalerVo>> list() {
        Long tenantId = requireTenant();
        return R.ok(wholesalerService.listByTenant(tenantId));
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
