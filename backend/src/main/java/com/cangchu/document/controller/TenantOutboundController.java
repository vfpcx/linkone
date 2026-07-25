package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.vo.OutboundRequestVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户侧出库作业 Controller（P3 BE-W2，12 §1.2/§3.1/§3.3/§6.1）。
 * 路径 /api/v1/tenant/** 已被 SaInterceptor 登录拦截覆盖；WK/TA 角色鉴权在 Service 内。
 * 代建出库端点 /api/v1/tenant/wk/outbound-requests 按 12 §6.1 定稿路径单列。
 */
@RestController
@RequiredArgsConstructor
public class TenantOutboundController {

    private final OutboundRequestService outboundRequestService;

    /** 出库作业列表（WK/TA；status 可选过滤）。 */
    @GetMapping("/api/v1/tenant/outbound-requests")
    public R<Page<OutboundRequestVo>> list(@RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return R.ok(outboundRequestService.listByTenant(requireTenant(), StpUtil.getLoginIdAsLong(),
                status, page, size));
    }

    /** WK 打印：PENDING_ACCEPT→PRINTED（首打 printed_at）；补打 count++ 不迁移状态。 */
    @PostMapping("/api/v1/tenant/outbound-requests/{id}/print")
    public R<OutboundRequestVo> print(@PathVariable Long id) {
        return R.ok(outboundRequestService.printByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** WK 重新核对回退：PRINTED→PENDING_ACCEPT。 */
    @PostMapping("/api/v1/tenant/outbound-requests/{id}/revert-to-pending")
    public R<OutboundRequestVo> revertToPending(@PathVariable Long id) {
        return R.ok(outboundRequestService.revertToPendingByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** WK 登记出库：PRINTED→COMPLETED（+询价终态联动）。 */
    @PostMapping("/api/v1/tenant/outbound-requests/{id}/register")
    public R<OutboundRequestVo> register(@PathVariable Long id) {
        return R.ok(outboundRequestService.registerByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** R4 已打印撤回二次确认：PRINTED→CANCELLED + 回补（无 flag → 50336）。 */
    @PostMapping("/api/v1/tenant/outbound-requests/{id}/confirm-withdraw")
    public R<OutboundRequestVo> confirmWithdraw(@PathVariable Long id) {
        return R.ok(outboundRequestService.confirmWithdrawByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** R4 拒绝撤回：清 flag 通知 WA，单据继续履约。 */
    @PostMapping("/api/v1/tenant/outbound-requests/{id}/reject-withdraw")
    public R<OutboundRequestVo> rejectWithdraw(@PathVariable Long id) {
        return R.ok(outboundRequestService.rejectWithdrawByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** WK 代建出库（直达 COMPLETED；confirmed=true 凭据 + 大额>50% 复述件数，50338）。 */
    @PostMapping("/api/v1/tenant/wk/outbound-requests")
    public R<OutboundRequestVo> createByWk(@Valid @RequestBody WkOutboundCreateDto dto) {
        return R.ok(outboundRequestService.createByWk(dto, StpUtil.getLoginIdAsLong()));
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
