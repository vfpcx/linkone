package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仲裁通过恢复上下文（P3 12 §2.6：qty += reversedQty，写 DISPUTE_RESTORE）。
 *
 * <ul>
 *   <li>wholesalerId/tenantId/skuId 必填。</li>
 *   <li>qty               必填，恢复件数 = 仲裁单 reversed_qty（差额部分货已离仓，不恢复）。</li>
 *   <li>refDocNo          必填，原入库单 doc_no（实现按此定位配对的 DISPUTE_REVERSAL 流水：
 *                         回指 reversal_of_id + 还原冲销托盘数）。</li>
 *   <li>originalInboundAt 必填，原入库单 created_at → DISPUTE_RESTORE 流水 biz_time（G10）。</li>
 *   <li>operatorUserId    裁决人（TA）。</li>
 * </ul>
 */
@Data
@Builder
public class DisputeRestoreContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private String refDocNo;
    private LocalDateTime originalInboundAt;
    private Long operatorUserId;
}
