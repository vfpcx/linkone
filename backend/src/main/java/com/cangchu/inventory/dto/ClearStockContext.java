package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 清库上下文（P3b T4-W2，13 §3.4：QK 审批通过联动——由 document 清库审批调用）。
 *
 * <p>契约：{@code clearStock} 在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行：
 * <pre>
 * applied  = min(qty, max(onhand, 0))   // onhand=审批时刻锁内重读（封顶口径家族第 4 处）
 * shortfall = qty − applied             // 差额写单据备注定责，禁止打负
 * </pre>
 * applied&gt;0 才写 EXPIRY_CLEARANCE 流水（qty=applied、<b>batch_id 落值</b>（方案 C 定义，
 * FIFO 直扣不进分摊）、biz_time=清库日=仓储费当日截止锚点、pallet_delta=−释放、
 * 不计正常出库统计——P4 按 type 区分）；applied=0（售罄）零冲销不写流水（LOSS 同构）。
 *
 * <ul>
 *   <li>qty                   必填 &gt;0（WK 现场核数，封顶前目标量）。</li>
 *   <li>batchId               必填（流水批次标识；批次状态流转由调用方经 BatchService 处理）。</li>
 *   <li>palletReleaseOverride 可空：null=默认比例 ceil(池 pallet × applied / 变动前在库)
 *       （全出清零=全部释放）；非空=WK 覆盖（含 0）。一律 min(·, 在库托盘) 双重封顶。</li>
 * </ul>
 */
@Data
@Builder
public class ClearStockContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Long batchId;
    private Integer qty;
    private Integer palletReleaseOverride;
    private String refDocNo;
    private Long operatorUserId;
}
