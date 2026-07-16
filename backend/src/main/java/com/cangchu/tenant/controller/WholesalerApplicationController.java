package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.tenant.dto.OpsWholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.dto.WholesalerApplyDto;
import com.cangchu.tenant.service.WholesalerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 批发商入驻申请 Controller（P2 Wave1）。
 *
 * <p>路径分三段（均已被 SaInterceptor 登录拦截覆盖）：
 * <ul>
 *   <li>/api/v1/wholesaler/applications — WA 自助申请（申请人=登录用户，无租户上下文）。</li>
 *   <li>/api/v1/tenant/wholesaler-applications — TA 审批侧，tenantId 一律 TenantContext 推导。</li>
 *   <li>/api/v1/admin/wholesalers — OPS 代建（服务层 hasRole OPS 显式校验）。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class WholesalerApplicationController {

    private final WholesalerApplicationService applicationService;

    /** WA 自助入驻申请 */
    @PostMapping("/api/v1/wholesaler/applications")
    public R<Map<String, Object>> apply(@Valid @RequestBody WholesalerApplyDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(applicationService.selfApply(userId, dto));
    }

    /** 登录 WA 查询本人的入驻申请列表（含 status/auditRemark；仅本人可见，P2 Wave2 契约对齐） */
    @GetMapping("/api/v1/wholesaler/applications")
    public R<java.util.List<com.cangchu.tenant.vo.WholesalerApplicationVo>> listMine() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(applicationService.listMine(userId));
    }

    /** TA 分页列表（可按 status 过滤：PENDING/APPROVED/REJECTED） */
    @GetMapping("/api/v1/tenant/wholesaler-applications")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(required = false) String status) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(applicationService.pageForTenant(tenantId, userId, page, size, status));
    }

    /** TA 审批（APPROVED/REJECTED；驳回必填 remark） */
    @PostMapping("/api/v1/tenant/wholesaler-applications/{id}/audit")
    public R<Map<String, Object>> audit(@PathVariable Long id,
                                        @Valid @RequestBody WholesalerApplicationAuditDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(applicationService.audit(tenantId, userId, id, dto));
    }

    /** OPS 代建批发商（authBasis 必填留痕；黑名单同样拦截，决策 O-2） */
    @PostMapping("/api/v1/admin/wholesalers")
    public R<Map<String, Object>> createByOps(@Valid @RequestBody OpsWholesalerCreateDto dto) {
        Long opsUserId = StpUtil.getLoginIdAsLong();
        return R.ok(applicationService.createByOps(opsUserId, dto));
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
