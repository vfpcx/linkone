package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 清库执行结果（P3b T4-W2，封顶口径家族第 4 处）：
 * appliedQty=实际清库件数（封顶后）；shortfallQty=差额（写单据备注定责）；
 * palletReleased=实际释放托盘（双重封顶后）；movementId=EXPIRY_CLEARANCE 流水（applied=0 时 null）。
 */
@Data
@Builder
public class ClearStockResult {
    private int appliedQty;
    private int shortfallQty;
    private int palletReleased;
    private Long movementId;
    private int remainingQty;
    private int remainingPalletQty;
}
