package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 盘亏执行结果（P3b T3-W2，D-10 封顶）：
 * appliedQty=实际冲销件数（封顶后）；shortfallQty=差额（写明细备注+通知定责）；
 * palletReleased=实际释放托盘（双重封顶后）；movementId=LOSS 流水（applied=0 时 null）。
 */
@Data
@Builder
public class LossStockResult {
    private int appliedQty;
    private int shortfallQty;
    private int palletReleased;
    private Long movementId;
    private int remainingQty;
    private int remainingPalletQty;
}
