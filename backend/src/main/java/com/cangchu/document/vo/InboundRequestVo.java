package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库单出参（phase-1 C1）。
 */
@Data
@Builder
public class InboundRequestVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private Integer palletQty;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 登记后该 sku 最新库存（便于前端回显） */
    private Integer currentStock;

    // ==================== P3 BE-W1（12 §2）确认链字段 ====================

    /** 来源：WK_CREATED / WA_SUBMIT */
    private String source;

    /** 72h 确认截止（WA 队列按此升序倒计时） */
    private LocalDateTime waConfirmDeadline;

    private LocalDateTime waConfirmAt;

    /** 1=72h 超时自动确认 */
    private Integer autoAccepted;

    private LocalDateTime disputedAt;

    private LocalDateTime createdAt;
}
