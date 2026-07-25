package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库单视图（P3 BE-W2，12 §6.1：WA 出库列表 / WK 作业列表 / 详情共用）。
 */
@Data
@Builder
public class OutboundRequestVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inquiryId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 商户名（列表展示，页内缓存填充） */
    private String wholesalerName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private Integer palletQty;

    /** PENDING_ACCEPT/PRINTED/COMPLETED/WITHDRAWN/CANCELLED/COMPLAINED */
    private String status;

    /** INQUIRY_AUTO/WA_SUBMIT/WK_CREATED（「已确认（代建）」队列=按 WK_CREATED 过滤） */
    private String source;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    private LocalDateTime printedAt;

    private Integer printCount;

    private LocalDateTime completedAt;

    /** 1=已申请撤回待 WK 二次确认（仅 PRINTED 态有意义） */
    private Integer withdrawRequested;

    private LocalDateTime withdrawRequestedAt;

    private LocalDateTime createdAt;
}
