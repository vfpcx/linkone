package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 账单头 VO（P4 W3；列表行 + 详情头共用）。id 一律字符串防 JS 精度；
 * wholesalerName 冗余带出（L-5 口径，前端不自行映射）。
 */
@Data
@Builder
public class BillVo {

    private String id;

    private String billNo;

    private String wholesalerId;

    /** 商户名冗余（已删/不可见 null，前端兜底展示 id） */
    private String wholesalerName;

    /** 账期月 yyyy-MM */
    private String billingMonth;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    /** 仓储费小计 */
    private BigDecimal subtotalAmount;

    /** 调整合计（可负） */
    private BigDecimal adjustAmount;

    /** 应收 */
    private BigDecimal totalAmount;

    /** 已收 */
    private BigDecimal paidAmount;

    /** 未收 = 应收 − 已收 */
    private BigDecimal outstandingAmount;

    /** DRAFT/DISPATCHED/PENDING_PAYMENT/PARTIAL_PAID/PAID/DISPUTED */
    private String status;

    private LocalDateTime dispatchAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;
}
