package com.cangchu.inventory.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.inventory.dto.BatchBackfillDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.cangchu.inventory.vo.BatchVo;
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

    /** 默认批次补录 production/expiry（WK/TA；仅 source=DEFAULT 且未终态）。 */
    @PutMapping("/api/v1/tenant/batches/{id}")
    public R<BatchVo> backfill(@PathVariable Long id, @RequestBody BatchBackfillDto dto) {
        return R.ok(batchService.backfillDefaultBatch(id, dto, StpUtil.getLoginIdAsLong()));
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
