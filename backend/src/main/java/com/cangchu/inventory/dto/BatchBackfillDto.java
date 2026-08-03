package com.cangchu.inventory.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 默认批次补录入参（P3b T4-W1，13 §5.3：PUT /api/v1/tenant/batches/{id}）。
 * 仅 source=DEFAULT 且未终态（非 CLEARED/CLOSED）的批次可补录 production/expiry。
 */
@Data
public class BatchBackfillDto {

    /** 生产日期（≤今天，40205；可空=不改） */
    private LocalDate productionDate;

    /** 到效期（>生产日期，40206；可空=不改） */
    private LocalDate expiryDate;
}
