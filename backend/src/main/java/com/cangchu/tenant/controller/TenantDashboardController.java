package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.tenant.service.TenantDashboardService;
import com.cangchu.tenant.vo.TenantDashboardVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TA 租户工作台（P5-C，19；替换前端 mock）。
 *
 * <p>鉴权：requireTa 在 TenantDashboardServiceImpl 内完成（登录态推导，不取客户端 tenantId）。
 */
@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor
public class TenantDashboardController {

    private final TenantDashboardService dashboardService;

    /** TA 工作台真实数据（KPI 待处理 + 容量 + 今日业务量 + 批次开关）。 */
    @GetMapping("/dashboard")
    public R<TenantDashboardVo> dashboard() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(dashboardService.dashboard(userId));
    }
}
