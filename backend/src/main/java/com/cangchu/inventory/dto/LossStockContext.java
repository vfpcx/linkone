package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 盘亏上下文（P3b T3-W2，13 §2.2：D-10 盘亏封顶——由 document 盘点审批调用）。
 *
 * <p>契约：{@code lossStock} 在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行：
 * <pre>
 * applied  = min(qty, max(onhand, 0))   // onhand=审批时刻锁内重读（G9：等待期被出完按剩余封顶）
 * shortfall = qty − applied             // 差额写明细备注+站内信定责，禁止打负
 * </pre>
 * applied&gt;0 才写 LOSS 流水（qty=applied、biz_time=审批通过日计费当日截止、pallet_delta=−释放）；
 * applied=0（售罄）零冲销不写流水（CORRECTION_OUT/DISPUTE_REVERSAL 同构）。
 *
 * <ul>
 *   <li>qty                   必填 &gt;0（盘亏件数=|diff|，封顶前目标量）。</li>
 *   <li>palletReleaseOverride 可空：null=默认比例 ceil(池 pallet × applied / 变动前在库)
 *       （全出清零=全部释放）；非空=WK 覆盖（含 0）。一律 min(·, 在库托盘) 双重封顶。</li>
 * </ul>
 */
@Data
@Builder
public class LossStockContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private Integer palletReleaseOverride;
    private String refDocNo;
    private Long operatorUserId;
}
