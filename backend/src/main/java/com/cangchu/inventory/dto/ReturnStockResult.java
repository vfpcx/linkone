package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 退货登记库存联动结果（P3b T3-W1）。
 */
@Getter
@Builder
public class ReturnStockResult {

    /** 实际释放托盘（默认建议值或 WK 覆盖，经在库托盘封顶后） */
    private final int palletReleased;

    /** RETURN 流水 id */
    private final Long movementId;

    /** 联动后剩余在库件数 */
    private final int remainingQty;

    /** 联动后剩余在库托盘 */
    private final int remainingPalletQty;
}
