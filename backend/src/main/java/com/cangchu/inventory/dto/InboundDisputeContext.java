package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 入库异议冲销上下文（P3 12 §2.4：按剩余在库封顶，写 DISPUTE_REVERSAL）。
 *
 * <ul>
 *   <li>wholesalerId/tenantId/skuId 必填。</li>
 *   <li>registeredQty  必填，该入库单登记件数 Q。</li>
 *   <li>palletQty      可空，该入库单登记托盘数（冲销托盘按比例、双重封顶）。</li>
 *   <li>refDocNo       必填，被异议入库单 doc_no（DISPUTE_REVERSAL 流水与仲裁恢复按此配对）。</li>
 *   <li>operatorUserId 异议发起人。</li>
 * </ul>
 */
@Data
@Builder
public class InboundDisputeContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer registeredQty;
    private Integer palletQty;
    private String refDocNo;
    private Long operatorUserId;
}
