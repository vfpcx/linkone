package com.cangchu.tenant.service;

import com.cangchu.tenant.vo.OpsDashboardVo;

/**
 * OPS 平台运营控制台服务（P5-C，21）。
 */
public interface OpsDashboardService {

    /**
     * OPS 控制台聚合（平台规模 + 待办队列 + 今日动态）。
     *
     * <p>鉴权：requireOps（authService.hasRole(userId,"OPS")，非 OPS → 42002）。
     * 平台级统计无租户上下文；tenant 域计数 mapper 直连，document/notify 域经 Service 出口（G-S1/G-S2）。
     */
    OpsDashboardVo dashboard(Long userId);
}
