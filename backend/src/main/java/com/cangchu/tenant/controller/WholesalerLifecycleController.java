package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.tenant.dto.ForceOfflineDto;
import com.cangchu.tenant.dto.WithdrawApplyDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.service.WholesalerLifecycleService;
import com.cangchu.tenant.vo.WithdrawApplicationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 批发商生命周期 Controller（P2 Wave2：R13 退驻 + R14 强制下架）。
 *
 * <p>路径分两段（均已被 SaInterceptor 登录拦截覆盖）：
 * <ul>
 *   <li>/api/v1/wholesaler/withdraw(**) — WA 侧（发起/mine/恢复），商户以登录态推导。</li>
 *   <li>/api/v1/tenant/wholesaler-withdraw-applications、/api/v1/tenant/wholesalers/{id}/force-offline
 *       — TA 侧，tenantId 一律 TenantContext 推导。</li>
 * </ul>
 * 注意：不存在任何 OFFLINE→ACTIVE 的端点（R14 不可原地恢复，状态机收口）。
 */
@RestController
@RequiredArgsConstructor
public class WholesalerLifecycleController {

    private final WholesalerLifecycleService lifecycleService;

    /** R13：WA 发起退驻申请（前置：库存 0 + 无未结单据；账单校验 P4 占位） */
    @PostMapping("/api/v1/wholesaler/withdraw")
    public R<Map<String, Object>> applyWithdraw(@Valid @RequestBody(required = false) WithdrawApplyDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(lifecycleService.applyWithdraw(userId, dto));
    }

    /** R13：退驻前置自查（只读三态清单：stockCleared / openDocs / billing 灰态占位） */
    @GetMapping("/api/v1/wholesaler/withdraw/precheck")
    public R<Map<String, Object>> precheckWithdraw() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(lifecycleService.precheckWithdraw(userId));
    }

    /** R13：WA 撤回本人 PENDING 退驻申请（仅 PENDING 可撤，CAS；撤回后可重新发起） */
    @PostMapping("/api/v1/wholesaler/withdraw/cancel")
    public R<Map<String, Object>> cancelWithdraw() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(lifecycleService.cancelWithdraw(userId));
    }

    /** R13：登录 WA 查询本人最近一次退驻申请（status/auditRemark/auditedAt；无申请返回空） */
    @GetMapping("/api/v1/wholesaler/withdraw/mine")
    public R<WithdrawApplicationVo> myWithdraw() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(lifecycleService.myWithdraw(userId));
    }

    /** R13：WA 60 天内恢复（SKU 保持下架，专属价不复活） */
    @PostMapping("/api/v1/wholesaler/withdraw/restore")
    public R<Map<String, Object>> restoreWithdraw() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(lifecycleService.restoreWithdraw(userId));
    }

    /** R13：TA 分页退驻申请列表（可按 status 过滤：PENDING/APPROVED/REJECTED） */
    @GetMapping("/api/v1/tenant/wholesaler-withdraw-applications")
    public R<Map<String, Object>> listWithdraw(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(required = false) String status) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(lifecycleService.pageWithdrawForTenant(tenantId, userId, page, size, status));
    }

    /** R13：TA 审批退驻（APPROVED 触发副作用链；驳回必填 remark） */
    @PostMapping("/api/v1/tenant/wholesaler-withdraw-applications/{id}/audit")
    public R<Map<String, Object>> auditWithdraw(@PathVariable Long id,
                                                @Valid @RequestBody WholesalerApplicationAuditDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(lifecycleService.auditWithdraw(tenantId, userId, id, dto));
    }

    /** R14：TA 单方即时强制下架（reason 必填；不可原地恢复） */
    @PostMapping("/api/v1/tenant/wholesalers/{id}/force-offline")
    public R<Map<String, Object>> forceOffline(@PathVariable Long id,
                                               @Valid @RequestBody ForceOfflineDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Long tenantId = requireTenant();
        return R.ok(lifecycleService.forceOffline(tenantId, userId, id, dto));
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
