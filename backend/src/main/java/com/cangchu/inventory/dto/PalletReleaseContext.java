package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 出库托盘释放上下文（P3b T3-W1，13 §2.4-3：D-8=A 出库处补齐）。
 *
 * <p>契约：件数=创建即扣不动（拍板二 B），托盘=登记出库（COMPLETED）时释放。
 * 因原 OUTBOUND 流水永不 update（12 §0 红线），登记同事务追加独立
 * {@code PALLET_RELEASE} 流水（qty=0 恒定、pallet_delta=−n、ref_doc_no=出库单、
 * biz_time=登记时刻）。释放数=0（池托盘 0 / WK 覆盖 0）时不写流水。
 *
 * <ul>
 *   <li>docQty 必填：出库单件数（创建时已扣；比例公式的「本次变动件数」）。
 *       默认建议值分母取「变动前在库」= 当前池 qty + docQty（05 §3.3 口径）。</li>
 *   <li>palletReleaseOverride 语义同 {@link ReturnStockContext}。</li>
 * </ul>
 */
@Data
@Builder
public class PalletReleaseContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer docQty;
    private Integer palletReleaseOverride;
    private String refDocNo;
    private Long operatorUserId;
}
