package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 异议冲销结果（P3 12 §2.4 封顶口径的精确输出）。
 *
 * <ul>
 *   <li>reversedQty    实际冲销件数 = min(Q, max(onhand, 0))。</li>
 *   <li>shortfallQty   已售差额 = Q − reversedQty（只落仲裁单，不动库存）。</li>
 *   <li>palletReversed 冲销托盘 = min(ceil(pallet_qty × reversedQty / Q), 在库托盘)。</li>
 *   <li>movementId     DISPUTE_REVERSAL 流水 id（reversedQty=0 时不写流水，为 null）。</li>
 *   <li>remainingQty   冲销后该 (wholesaler, sku) 池剩余在库。</li>
 * </ul>
 */
@Data
@Builder
public class DisputeReversalResult {
    private int reversedQty;
    private int shortfallQty;
    private int palletReversed;
    private Long movementId;
    private int remainingQty;
}
