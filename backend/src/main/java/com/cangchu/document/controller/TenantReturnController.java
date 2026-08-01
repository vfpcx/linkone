package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.ReturnRegisterDto;
import com.cangchu.document.service.ReturnRequestService;
import com.cangchu.document.vo.ReturnRequestVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户侧退货单 Controller（P3b T3-W1，13 §5.2：受理队列 / 受理 / 登记）。
 * 鉴权（WK 作业 / WK·TA 查看）在 Service 内以 user_roles 登录态推导；
 * tenantId 取登录态推导的可信租户（不信任客户端传参）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/return-requests")
public class TenantReturnController {

    private final ReturnRequestService returnRequestService;

    /** 退货队列（status=PENDING_ACCEPT 按创建升序先到先受理；受理/登记链路附在库与托盘建议值）。 */
    @GetMapping
    public R<List<ReturnRequestVo>> list(@RequestParam(required = false) Long wholesalerId,
                                         @RequestParam(required = false) String status) {
        return R.ok(returnRequestService.listByTenant(
                requireTenantId(), StpUtil.getLoginIdAsLong(), wholesalerId, status));
    }

    /** WK 受理（CAS PENDING_ACCEPT→ACCEPTED 锁单防撤回；仍不动库存——D-7）。 */
    @PostMapping("/{id}/accept")
    public R<ReturnRequestVo> accept(@PathVariable Long id) {
        return R.ok(returnRequestService.acceptByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /**
     * WK 现场出货登记（D-7 登记时扣）：actualQty 可按实覆写、palletRelease 覆盖默认建议值（含 0）。
     * 在库不足 → 50251（单据保持已受理，联系商户改单）。body 可省略=按申请件数+默认托盘。
     */
    @PostMapping("/{id}/register")
    public R<ReturnRequestVo> register(@PathVariable Long id,
                                       @RequestBody(required = false) ReturnRegisterDto dto) {
        return R.ok(returnRequestService.registerByWk(id, dto, StpUtil.getLoginIdAsLong()));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
