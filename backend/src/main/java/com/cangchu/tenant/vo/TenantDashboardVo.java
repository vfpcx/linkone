package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * TA 租户工作台聚合出参（P5-C，19；对齐前端契约 TenantDashboardResponse）。
 *
 * <p>口径：TA 本人视角——容量展示精确值（不做 TIER 公示脱敏，ADR-009 语义不变，
 * 公示脱敏仅作用于对外公开 {@code getCapacity}）。KPI 与审批中心角标同口径。
 */
@Data
@Builder
public class TenantDashboardVo {

    /** 仓库名称 */
    private String storeName;

    /** 待处理计数 */
    private KpiVo kpi;

    /** 实时容量（精确值） */
    private DashboardCapacityVo capacity;

    /** 今日业务量 */
    private TodayVo today;

    /** 批次功能开关（1=启用；关闭时临期卡片不展示） */
    private Integer batchEnabled;

    /** 待处理计数（TA 审批中心四卡同口径） */
    @Data
    @Builder
    public static class KpiVo {
        /** 待审入驻申请 */
        private long pendingInbound;
        /** 待审盘点单 */
        private long pendingCount;
        /** 待审清库单 */
        private long pendingClearance;
        /** 待处理申诉（PENDING 全类型，与审批中心角标一致） */
        private long pendingDispute;
    }

    /** 容量（无快照时回退 store 默认：used=0、total=store 容量、utilization=0） */
    @Data
    @Builder
    public static class DashboardCapacityVo {
        private Integer usedQty;
        private Integer usedPallet;
        private Integer totalQty;
        private Integer totalPallet;
        /** 已用/总容量百分比（0-100，取整；与前端 CapacityBar 自算口径一致） */
        private Integer utilization;
        /** store 公示可见性（stores.capacity_visibility）：PRIVATE/WA_ONLY/PUBLIC */
        private String visibility;
        /** 快照时间（无快照时为当前时间） */
        private LocalDateTime snapshotAt;
    }

    /** 今日业务量 */
    @Data
    @Builder
    public static class TodayVo {
        /** 今日入库登记单数 */
        private long inboundCount;
        /** 今日出库单数 */
        private long outboundCount;
        /** 今日询价单数 */
        private long inquiryCount;
        /** 临期 3 天内批次数（批次未启用恒 0） */
        private long expiringBatches;
    }
}
