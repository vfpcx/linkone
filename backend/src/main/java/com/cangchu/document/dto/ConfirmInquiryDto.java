package com.cangchu.document.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * WA 确认询价请求体（P2 定价 Wave 3a：议价 + 议价沉淀）。
 *
 * <p>全字段可空容错：无请求体 / 空体沿用 phase-1 行为（成交价=公开价快照，不沉淀）。
 * <ul>
 *   <li>{@code items}：逐条覆盖成交价（按 inquiryItemId 匹配），dealPrice 必须 &gt;0。</li>
 *   <li>{@code settleAsCustomerPrice}：true 时，凡有效成交价≠提交时公开价快照的明细，
 *       在同一确认事务内沉淀为该 (wholesaler, rtPhone, sku) 的客户专属价（source=from_inquiry）。
 *       后续库存不足回滚会一并撤销沉淀。</li>
 * </ul>
 */
@Data
public class ConfirmInquiryDto {

    /** 逐条议价（可空/可缺项）。 */
    private List<Item> items;

    /** 是否将议价结果沉淀为客户专属价。 */
    private boolean settleAsCustomerPrice;

    @Data
    public static class Item {
        /** 目标 inquiry_items.id。 */
        private Long inquiryItemId;
        /** 议定成交价（>0）。 */
        private BigDecimal dealPrice;
    }
}
