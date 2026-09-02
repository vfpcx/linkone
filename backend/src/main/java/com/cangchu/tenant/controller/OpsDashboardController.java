package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.tenant.service.OpsDashboardService;
import com.cangchu.tenant.vo.OpsDashboardVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPS 平台运营控制台（P5-C，21；替换 PlaceholderDashboard 占位页）。
 *
 * <p>鉴权：requireOps 在 OpsDashboardServiceImpl 内完成（Service 层 hasRole 校验，不信任客户端，
 * Blacklist 先例）。OPS 无租户上下文 → 平台级统计。
 */
@RestController
@RequestMapping("/api/v1/ops")
@RequiredArgsConstructor
public class OpsDashboardController {

    private final OpsDashboardService dashboardService;

    /** OPS 控制台真实数据（平台规模 + 待办 + 今日动态）。 */
    @GetMapping("/dashboard")
    public R<OpsDashboardVo> dashboard() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(dashboardService.dashboard(userId));
    }
}
