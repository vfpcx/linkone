package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库回补上下文（P3 12 §1.5：R4 撤回 / R8 作废联动 → OUTBOUND_REVERSAL 流水）。
 *
 * <p>契约：锁内 {@code qty += ctx.qty}，写 OUTBOUND_REVERSAL 流水（qty 恒正，方向由 type 表达）。
 * <ul>
 *   <li>wholesalerId/tenantId/skuId/qty 必填（同 {@link OutboundContext} 语义）。</li>
 *   <li>palletQty        可空，回补托盘数（按出库单 pallet_qty 还原，默认 0）。</li>
 *   <li>refDocNo         关联出库单号。</li>
 *   <li>reversalOfId     可空（BE-W2）：空时锁内按 refDocNo+type=OUTBOUND 解析原流水（1:1）；
 *                        落库流水的 reversal_of_id 恒非空（P4 配对抵消）。</li>
 *   <li>bizTime          可空：空时沿用解析出的原 OUTBOUND 流水 biz_time（计费视同从未出库）。</li>
 *   <li>operatorUserId   操作人。</li>
 *   <li>remark           可空备注。</li>
 * </ul>
 */
@Data
@Builder
public class OutboundReversalContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private Integer palletQty;
    private String refDocNo;
    private Long reversalOfId;
    private LocalDateTime bizTime;
    private Long operatorUserId;
    private String remark;
}
