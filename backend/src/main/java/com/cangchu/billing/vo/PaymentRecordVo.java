package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回款记录 VO（P4 W3；REVERSED 保留可见不删，R12 留痕）
 */
@Data
@Builder
public class PaymentRecordVo {

    private String id;

    private String billId;

    private BigDecimal amount;

    private LocalDateTime payAt;

    /** BANK_TRANSFER/CASH/WX/ALIPAY/OTHER */
    private String payMethod;

    private List<String> evidences;

    private String remark;

    /** EFFECTIVE/REVERSED */
    private String status;

    private String reverseReason;

    private LocalDateTime reverseAt;

    private LocalDateTime createdAt;
}
