package com.cangchu.document.service;

import com.cangchu.document.dto.TodayCountsDto;

/**
 * 单据统计出口（P5-C，19 §3）：今日三单计数，供 TA 工作台编排合入。
 *
 * <p>跨域访问规约（G-S1/G-S2）：dashboard 聚合端不直连 document 域 mapper，
 * 一律经本服务出口取值。
 */
public interface DocumentStatsService {

    /**
     * 今日单据计数（created_at ≥ 今日 0 点，不限状态）。
     * 纯读、无鉴权——调用方（TenantDashboardService）已做 requireTa。
     */
    TodayCountsDto todayCounts(Long tenantId);
}
