package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.vo.ArbitrationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TA 仲裁 Controller（P3 BE-W1，12 §2.6/§6.1：审批中心列表 + 裁决）。
 * 路径 /api/v1/tenant/** 已在 SaTokenConfig checkLogin 段；TA 角色鉴权在 Service 内。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/arbitrations")
public class TenantArbitrationController {

    private final ArbitrationService arbitrationService;

    /** 本店仲裁列表（bizType/status 可选过滤；审批中心角标=status=PENDING 的 total）。 */
    @GetMapping
    public R<Page<ArbitrationVo>> list(@RequestParam(required = false) String bizType,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return R.ok(arbitrationService.listForTa(tenantId, StpUtil.getLoginIdAsLong(), bizType, status, page, size));
    }

    /** 裁决（APPROVED=通过·恢复流水 / REJECTED=驳回·保留冲销；liability 三态校验 50342）。 */
    @PostMapping("/{id}/decide")
    public R<ArbitrationVo> decide(@PathVariable Long id, @Valid @RequestBody ArbitrationDecideDto dto) {
        return R.ok(arbitrationService.decideByTa(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
