package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.notify.service.AnnouncementService;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import com.cangchu.tenant.service.OpsDashboardService;
import com.cangchu.tenant.vo.OpsDashboardVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * OPS 平台运营控制台聚合实现（P5-C，21 §3/§4）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：Service 内 {@code authService.hasRole(userId,"OPS")} 校验（requireOps，Blacklist 先例），
 *       非 OPS → 42002，不信任客户端；</li>
 *   <li>平台级统计：OPS 无租户上下文，天然不进 TenantLine；tenant 域计数 mapper 直连（域内合规），
 *       document/notify 域计数经 Service 出口（G-S1/G-S2，同 TenantDashboardServiceImpl）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsDashboardServiceImpl implements OpsDashboardService {

    /** 租户状态（tenants.status；实体未下沉常量，沿用 TA dashboard 字面先例） */
    private static final String TENANT_ACTIVE = "ACTIVE";
    private static final String TENANT_PENDING = "PENDING";
    /** 入驻申请终态「已通过」（wholesaler_applications.status；与 TA 审批同值域） */
    private static final String WA_APPROVED = "APPROVED";
    /** 黑名单生效态 */
    private static final String BLACKLIST_ACTIVE = "ACTIVE";

    private final AuthService authService;
    private final TenantMapper tenantMapper;
    private final WholesalerApplicationMapper wholesalerApplicationMapper;
    private final BlacklistMapper blacklistMapper;
    private final ArbitrationService arbitrationService;
    private final AnnouncementService announcementService;

    @Override
    public OpsDashboardVo dashboard(Long userId) {
        requireOps(userId);
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        return OpsDashboardVo.builder()
                .platform(OpsDashboardVo.PlatformVo.builder()
                        .activeTenantCount(countTenantsByStatus(TENANT_ACTIVE))
                        .wholesalerBindingCount(countApprovedBindings())
                        .activeBlacklistCount(countBlacklistActive())
                        .build())
                .pending(OpsDashboardVo.PendingVo.builder()
                        .pendingTenantAudits(countTenantsByStatus(TENANT_PENDING))
                        .pendingComplaints(arbitrationService.countPendingForOps())
                        .draftAnnouncements(announcementService.countDrafts(userId))
                        .build())
                .today(OpsDashboardVo.TodayVo.builder()
                        .newTenantToday(countTenantsCreatedSince(todayStart))
                        .newComplaintsToday(arbitrationService.countComplaintsCreatedToday())
                        .build())
                .build();
    }

    // ==================== 内部 ====================

    /** tenant 域直连：按状态计数（ACTIVE 营业仓库 / PENDING 待审租户）。 */
    private long countTenantsByStatus(String status) {
        Long cnt = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getStatus, status));
        return cnt != null ? cnt : 0;
    }

    /** tenant 域直连：今日（0 点起）新注册/新入驻仓库数。 */
    private long countTenantsCreatedSince(LocalDateTime since) {
        Long cnt = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .ge(Tenant::getCreatedAt, since));
        return cnt != null ? cnt : 0;
    }

    /** tenant 域直连：入驻绑定数（APPROVED 申请；一账号入驻 N 仓计 N，多仓口径）。 */
    private long countApprovedBindings() {
        Long cnt = wholesalerApplicationMapper.selectCount(new LambdaQueryWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getStatus, WA_APPROVED));
        return cnt != null ? cnt : 0;
    }

    /** tenant 域直连：生效黑名单数。 */
    private long countBlacklistActive() {
        Long cnt = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getStatus, BLACKLIST_ACTIVE));
        return cnt != null ? cnt : 0;
    }

    /** 越权（21 §3）：OPS 控制台仅限平台 OPS（user_roles 登录态为唯一可信来源）。 */
    private void requireOps(Long userId) {
        if (!authService.hasRole(userId, "OPS")) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }
}
