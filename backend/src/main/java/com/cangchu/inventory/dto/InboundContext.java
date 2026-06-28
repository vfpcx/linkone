package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 入库上下文（B1 → 由 C document 的 InboundRequest.register 调用）。
 *
 * <p>契约：单 sku 维度入库。{@code addStock} 在单事务内库存 upsert 增量 + 写 INBOUND 流水。
 *
 * <ul>
 *   <li>wholesalerId  必填，商户 id（雪花 Long）。</li>
 *   <li>tenantId      必填，商户所属租户 id；流水/库存行落库以此为准（由 C 在登记时从单据带入，
 *                     不依赖线程 TenantContext，便于异步/系统态调用）。</li>
 *   <li>skuId         必填，商品 sku id。</li>
 *   <li>qty           必填，入库数量（>0）。</li>
 *   <li>palletQty     可空，本次托盘数（默认 0，累加进库存 pallet_qty）。</li>
 *   <li>refDocNo      可空，关联入库单号。</li>
 *   <li>operatorUserId 可空，操作人（WK）user id，写入流水。</li>
 * </ul>
 */
@Data
@Builder
public class InboundContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private Integer palletQty;
    private String refDocNo;
    private Long operatorUserId;
}
