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

    private LocalDateTime createdAt;
}
