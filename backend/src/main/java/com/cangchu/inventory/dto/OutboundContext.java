package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 出库上下文（B1 → 由 C document 的 OutboundRequest.register 调用）。
 *
 * <p>契约：单 sku 维度出库。{@code deductStock} 在 Redisson 锁
 * {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行：
 * 校验库存足够（不足抛 STOCK_NOT_ENOUGH，且不产生流水）→ 扣减 → 写 OUTBOUND 流水。
 *
 * <ul>
 *   <li>wholesalerId  必填，商户 id。</li>
 *   <li>tenantId      必填，商户所属租户 id（由 C 从单据带入）。</li>
 *   <li>skuId         必填，商品 sku id。</li>
 *   <li>qty           必填，出库数量（>0）。</li>
 *   <li>refDocNo      可空，关联出库单号。</li>
 *   <li>operatorUserId 可空，操作人（WK）user id。</li>
 * </ul>
 */
@Data
@Builder
public class OutboundContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private String refDocNo;
    private Long operatorUserId;
}
