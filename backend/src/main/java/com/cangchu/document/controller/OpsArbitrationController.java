package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
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
 * OPS 客诉仲裁 Controller（P3 BE-W2，12 §3.4/§6.1：客诉中心列表 + 四选结论裁决）。
 * 路径 /api/v1/ops/** 已在 SaTokenConfig checkLogin 段（P2 Wave1）；
 * OPS 平台角色校验在 Service 内显式 hasRole（BlacklistServiceImpl 先例），跨租户查询走
 * 无 TenantContext 不注入先例（MybatisPlusConfig）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/arbitrations")
public class OpsArbitrationController {

    private final ArbitrationService arbitrationService;

    /** 客诉仲裁列表（跨租户；bizType 仅 OUTBOUND_COMPLAINT；status 可选过滤，角标=PENDING total）。 */
    @GetMapping
    public R<Page<ArbitrationVo>> list(@RequestParam(required = false) String bizType,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(arbitrationService.listForOps(StpUtil.getLoginIdAsLong(), bizType, status, page, size));
    }

    /** 裁决（四选 WK_LIABLE/WA_LIABLE/NEGOTIATED/NO_LIABILITY，remark 必填；仅判责不动库存/账单 D43）。 */
    @PostMapping("/{id}/decide")
    public R<ArbitrationVo> decide(@PathVariable Long id, @Valid @RequestBody ArbitrationDecideDto dto) {
        return R.ok(arbitrationService.decideByOps(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
