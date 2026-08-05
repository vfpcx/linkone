package com.cangchu.inventory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.service.ClearanceRequestService;
import com.cangchu.inventory.dto.BatchBackfillDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.ExpiryDashboardVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.cangchu.inventory.vo.BatchVo;
import com.cangchu.tenant.vo.TenantBatchConfigVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次登记簿 Controller（P3b T4-W1，13 §5.3）。
 * 鉴权（TA 开关 / WK·TA 查看补录 / WA·WE 只读）在 Service 内以 user_roles 登录态推导；
 * 路径 /api/v1/tenant/** 与 /api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段。
 */
@RestController
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;
    private final ClearanceRequestService clearanceRequestService;

    /**
     * TA 批次开关专用端点（D-13 解除后的唯一改动入口；通用店铺设置接口仍拒改 50360）。
     * {enable, confirmed:true}；24h ≤2 次（50361）；关→启生成默认批次吸收存量。
     */
    @PostMapping("/api/v1/tenant/settings/batch-toggle")
    public R<BatchToggleVo> toggle(@Valid @RequestBody BatchToggleDto dto) {
        return R.ok(batchService.toggle(StpUtil.getLoginIdAsLong(), dto));
    }

    /** 租户侧批次列表/下钻（WK/TA；wholesalerId+skuId 齐时附「无批次在池量」行数据）。 */
    @GetMapping("/api/v1/tenant/batches")
    public R<BatchListVo> listForTenant(@RequestParam(required = false) Long wholesalerId,
                                        @RequestParam(required = false) Long skuId,
                                        @RequestParam(required = false) String status) {
        return R.ok(batchService.listForTenant(
                requireTenantId(), StpUtil.getLoginIdAsLong(), wholesalerId, skuId, status));
    }

    /**
     * 临期预警列表（WK/TA，T4-W2）：EXPIRING ∪ PENDING_CLEARANCE，剩余天数升序；
     * 行含 manualNotifiedAt 供一键通知按钮 24h 冷却展示。
     */
    @GetMapping("/api/v1/tenant/batches/expiring")
    public R<BatchListVo> listExpiring() {
        return R.ok(batchService.listExpiring(requireTenantId(), StpUtil.getLoginIdAsLong()));
    }

    /**
     * WK 一键通知商户（T4-W2，D-12 手动侧）：仅临期/待清理批次；同批次 24h 限 1（50367）；
     * 站内信不发短信。
     */
    @PostMapping("/api/v1/tenant/batches/{id}/notify-wholesaler")
    public R<Void> notifyWholesaler(@PathVariable Long id) {
        batchService.notifyWholesalerManually(id, StpUtil.getLoginIdAsLong());
        return R.ok(null);
    }

    /**
     * TA 临期看板（T4-W2，PRD §3.6-A）：四卡汇总 + 按 SKU 分组；
     * 清库单待审批数经 document 域出口编排合入（G-S1：不跨域直连 Mapper）。
     */
    @GetMapping("/api/v1/tenant/batches/expiry-dashboard")
    public R<ExpiryDashboardVo> expiryDashboard() {
        Long tenantId = requireTenantId();
        ExpiryDashboardVo vo = batchService.expiryDashboard(tenantId, StpUtil.getLoginIdAsLong());
        vo.setPendingClearanceDocCount(clearanceRequestService.countPendingApprovalForTenant(tenantId));
        return R.ok(vo);
    }

    /** 默认批次补录 production/expiry（WK/TA；仅 source=DEFAULT 且未终态）。 */
    @PutMapping("/api/v1/tenant/batches/{id}")
    public R<BatchVo> backfill(@PathVariable Long id, @RequestBody BatchBackfillDto dto) {
        return R.ok(batchService.backfillDefaultBatch(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 租户批次配置只读（P3b 收口 L-1）：该租户下持任一 ACTIVE 角色（含商户侧 WA/WE）即可读；
     * 返回 {batchEnabled, batchEnabledAt, expiryThresholdDays}，供前端按开关隐藏关闭档批次字段。
     */
    @GetMapping("/api/v1/wholesaler/tenants/{tenantId}/batch-config")
    public R<TenantBatchConfigVo> batchConfig(@PathVariable Long tenantId) {
        return R.ok(batchService.getConfigForMember(tenantId, StpUtil.getLoginIdAsLong()));
    }

    /** 商户侧批次下钻/临期卡（WA/WE 只读）。 */
    @GetMapping("/api/v1/wholesaler/batches")
    public R<BatchListVo> listForWholesaler(@RequestParam(required = false) Long skuId,
                                            @RequestParam(required = false) String status) {
        return R.ok(batchService.listForWholesaler(StpUtil.getLoginIdAsLong(), skuId, status));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
