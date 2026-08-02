package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 盘盈上下文（P3b T3-W2，13 §2.2：审批通过逐 SKU 锁内 gainStock——由 document 盘点审批调用）。
 *
 * <p>契约：{@code gainStock} 在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行：
 * qty += diff（首次可 upsert 建行，与 addStock 同构——盘出账外货可能无库存行）→ 写 GAIN 流水
 * （biz_time=审批通过日，盘盈次日起算视同当日入库锚点；pallet_delta=+M 可选，零金额）。
 * 盘盈并入 SKU 池（无批次期口径，US-TA-12）。
 *
 * <ul>
 *   <li>qty          必填 &gt;0（盘盈件数=diff，无封顶语义）。</li>
 *   <li>palletDelta  可空 ≥0：WK 选填的盘盈占用托盘 +M；null=0。</li>
 * </ul>
 */
@Data
@Builder
public class GainStockContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private Integer palletDelta;
    private String refDocNo;
    private Long operatorUserId;
}
