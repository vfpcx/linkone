package com.cangchu.inventory.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计费回放流水视图（P4 W2，14 §1.1 跨域只读出口 G-S1）：
 * billing 域经 {@code InventoryService.listMovementsForBilling} 取锚点字段，
 * 不直连 StockMovementMapper。仅暴露回放公式消费的五锚点 + created_at（§1.5 跨月扫描用）。
 *
 * @param skuId       SKU
 * @param type        12 类流水类型（StockMovement.TYPE_*）
 * @param qty         变动数量（正数，方向由 type 表达；PALLET_RELEASE 恒 0）
 * @param bizTime     计费语义时间锚点（空值防御：读取侧已回退 created_at）
 * @param palletDelta 托盘变化（V20 前存量恒 0，D-P4-5=A 基线）
 * @param createdAt   流水落库时间（跨月锚点差额扫描：created_at ∈ 本期 ∧ bizDate < 期初）
 */
public record BillingMovementView(Long skuId, String type, int qty, LocalDateTime bizTime,
                                  int palletDelta, LocalDateTime createdAt) {

    /** 计费自然日（05 §12：biz_time 所在 UTC+8 自然日；库内 LocalDateTime 即业务时区语义） */
    public LocalDate bizDate() {
        return bizTime.toLocalDate();
    }
}
