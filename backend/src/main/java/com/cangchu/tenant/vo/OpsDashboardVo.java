package com.cangchu.tenant.vo;

import lombok.Builder;
import lombok.Data;

/**
 * OPS 平台运营控制台聚合出参（P5-C，21；对齐前端契约 OpsDashboardResponse）。
 *
 * <p>口径：全平台统计（OPS 无租户上下文，不进 TenantLine）。三块——
 * platform 平台规模 / pending 待办队列（对应既有管理页角标）/ today 今日动态。
 * 越权：非 OPS → 42002（requireOps）。产品口径见 15-p5c-ops-console.md（D-OPS-1~6）。
 */
@Data
@Builder
public class OpsDashboardVo {

    /** 平台规模（只读概览） */
    private PlatformVo platform;

    /** 待办队列（数字对应各管理页，点击跳转带筛选态） */
    private PendingVo pending;

    /** 今日动态（平台今日新增业务量） */
    private TodayVo today;

    /** 平台规模 */
    @Data
    @Builder
    public static class PlatformVo {
        /** 营业仓库数（tenants status=ACTIVE，自助 + OPS 代建合计） */
        private long activeTenantCount;
        /** 入驻绑定数（wholesaler_applications status=APPROVED；一账号入驻 N 仓计 N） */
        private long wholesalerBindingCount;
        /** 生效黑名单数（blacklist status=ACTIVE） */
        private long activeBlacklistCount;
    }

    /** 待办队列 */
    @Data
    @Builder
    public static class PendingVo {
        /** 待审租户（tenants status=PENDING → 租户审核页） */
        private long pendingTenantAudits;
        /** 待裁客诉（arbitrations OUTBOUND_COMPLAINT ∧ PENDING → 客诉仲裁页） */
        private long pendingComplaints;
        /** 公告草稿（announcements DRAFT → 公告管理页） */
        private long draftAnnouncements;
    }

    /** 今日动态 */
    @Data
    @Builder
    public static class TodayVo {
        /** 今日新入驻/新注册仓库（tenants created_at ≥ 今日 0 点） */
        private long newTenantToday;
        /** 今日新增客诉（arbitrations OUTBOUND_COMPLAINT，created_at ≥ 今日 0 点） */
        private long newComplaintsToday;
    }
}
