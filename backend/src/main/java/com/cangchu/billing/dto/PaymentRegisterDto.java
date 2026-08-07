package com.cangchu.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回款登记入参（P4 W3，14 §3.3 US-ST-04；仅 PENDING_PAYMENT/PARTIAL_PAID，不允许超收 50373）
 */
@Data
public class PaymentRegisterDto {

    /** 本次收款金额（>0，≤2 位小数，≤剩余应收） */
    private BigDecimal amount;

    /** 收款日期（必填，可过去日期） */
    private LocalDateTime payAt;

    /** BANK_TRANSFER/CASH/WX/ALIPAY/OTHER */
    private String payMethod;

    /** 转账凭证 ≤5 张（/files 白名单 50340） */
    private List<String> evidences;

    private String remark;
}
