package com.cangchu.tenant.service;

import com.cangchu.tenant.vo.TenantDashboardVo;

/**
 * TA 租户工作台聚合服务（P5-C，19；GET /api/v1/tenant/dashboard）。
 *
 * <p>纯读编排：跨域计数一律经各域 Service 出口（G-S1/G-S2），容量/入驻申请等
 * tenant 域数据在域内直连 mapper。requireTa 在进入本服务时先校验。
 */
public interface TenantDashboardService {

    /** TA 工作台真实数据（当前登录用户；非 TA → 42004/42001）。 */
    TenantDashboardVo dashboard(Long userId);
}
