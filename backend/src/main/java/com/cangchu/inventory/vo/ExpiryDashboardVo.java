package com.cangchu.inventory.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * TA 临期看板汇总（P3b T4-W2，PRD 11 §3.6-A）：
 * 顶部四卡（临期批次/涉及件数/已过期待清理/清库单待审批）+ 按 SKU 分组明细。
 * 件数均为 FIFO 推算值（截至 02:00，UI 标注「推算」）；批次明细行下钻走
 * GET /tenant/batches?status= 与 GET /tenant/batches/expiring。
 */
@Data
@Builder
public class ExpiryDashboardVo {

    /** 临期阈值天数（tenant_settings.expiry_threshold_days） */
    private Integer thresholdDays;

    /** 临期批次数（EXPIRING） */
    private long expiringBatchCount;

    /** 临期涉及件数（Σ EXPIRING 推算剩余） */
    private long expiringQtyTotal;

    /** 已过期待清理批次数（PENDING_CLEARANCE） */
    private long expiredBatchCount;

    /** 待清理涉及件数（Σ PENDING_CLEARANCE 推算剩余） */
    private long expiredQtyTotal;

    /** 已清库批次数（CLEARED，历史累计） */
    private long clearedBatchCount;

    /** 清库单待审批数（document 域出口 ClearanceRequestService，Controller 编排填充） */
    private Long pendingClearanceDocCount;

    /** 按 SKU 分组（临期∪待清理批次） */
    private List<SkuGroup> bySku;

    /** SKU 分组行（skuId/wholesalerId 字符串键防 JS 精度，in-transit-hint 先例） */
    @Data
    @Builder
    public static class SkuGroup {
        private String skuId;
        private String skuName;
        private String wholesalerId;
        /** 临期批次数（EXPIRING） */
        private long expiringBatchCount;
        /** 已过期待清理批次数（PENDING_CLEARANCE） */
        private long expiredBatchCount;
        /** Σ推算剩余（临期∪待清理） */
        private long remainingQtyTotal;
        /** 最近到效期（组内最小 expiry_date） */
        private LocalDate nearestExpiryDate;
    }
}
