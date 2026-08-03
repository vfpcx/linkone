package com.cangchu.inventory.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单租户 FIFO 推算结果摘要（P3b T4-W1，13 §3.2；T4-W2 BatchRecalcJob 02:00 契约）。
 *
 * <p>T4-W2 用法：{@code recalcAll()} 后对每租户的 {@code newlyExpiringBatchIds}
 * 逐批发 BATCH_EXPIRING 站内信（WK+WA 各一条）并落 expiring_notified_at（D-12 去重——
 * 集合本身已按 expiring_notified_at IS NULL 过滤，状态不变不重发天然成立）。
 */
@Data
@Builder
public class BatchRecalcResultVo {

    private Long tenantId;

    /** 本次参与推算的批次数（非 CLEARED/CLOSED） */
    private Integer scannedBatches;

    /** 推算后 SOLD_OUT 批次数 */
    private Integer soldOutCount;

    /** 推算后 EXPIRING 批次数 */
    private Integer expiringCount;

    /** 新进入 EXPIRING 且 expiring_notified_at IS NULL 的批次 id（T4-W2 首发通知集合） */
    private List<Long> newlyExpiringBatchIds;
}
