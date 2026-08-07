package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单申诉 VO（P4 W3；申诉记录与处理结论永久展示，双方可见）
 */
@Data
@Builder
public class BillDisputeVo {

    private String id;

    private String billId;

    /** 账单号冗余（申诉队列展示） */
    private String billNo;

    /** 商户名冗余（申诉队列展示） */
    private String wholesalerName;

    private String reason;

    private List<String> disputedItemIds;

    private List<String> attachments;

    /** PENDING/RESOLVED/REJECTED */
    private String status;

    private String resolution;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;
}
