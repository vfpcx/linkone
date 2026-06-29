package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 入库单 Controller（phase-1 C1：WK 登记入库 + 本租户入库单查询）。
 * 路径前缀 /api/v1/tenant/inbound，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 登记鉴权（该租户 WK）在 InboundRequestService 内以 user_roles 登录态推导，不信任客户端传参；
 * tenantId 由 wholesaler 真实归属推导，列表查询取登录态推导的可信租户。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/inbound")
public class InboundController {

    private final InboundRequestService inboundRequestService;

    /** WK 登记入库（单事务：建单 + 增库存）。 */
    @PostMapping
    public R<InboundRequestVo> register(@Valid @RequestBody InboundRegisterDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(inboundRequestService.registerByWk(dto, userId));
    }

    /** 列出本租户入库单（wholesalerId 可选过滤）。 */
    @GetMapping
    public R<List<InboundRequestVo>> list(@RequestParam(required = false) Long wholesalerId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return R.ok(inboundRequestService.listByTenant(tenantId, wholesalerId));
    }
}
