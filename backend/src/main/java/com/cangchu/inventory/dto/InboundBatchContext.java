package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 入库批次登记上下文（P3b T4-W1，13 §3.1）。
 *
 * <p>由 document 域在入库登记事务（正向 registerForwardByWk / 代建 registerByWk）的
 * <b>addStock 之后</b>后置调用 {@code BatchService.registerInboundBatch}——
 * 只追加登记簿行并回填 INBOUND 流水 batch_id，不动库存事务（方案 C 零侵入）。
 */
@Getter
@Builder
public class InboundBatchContext {

    private final Long tenantId;
    private final Long wholesalerId;
    private final Long skuId;

    /** 批次号（uk 冲突 → 50362） */
    private final String batchNo;

    private final LocalDate productionDate;
    private final LocalDate expiryDate;

    /** 实登件数（=INBOUND 流水 qty） */
    private final Integer qty;

    /** 入库单号（用于回填该单 INBOUND 流水的 batch_id） */
    private final String refDocNo;

    /** 货位号（V40，C2：登记时写入 batches.location；可为 null=不写） */
    private final String location;
}
