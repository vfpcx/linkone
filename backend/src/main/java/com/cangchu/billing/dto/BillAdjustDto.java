package com.cangchu.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 账单调整入参（P4 W3，14 §3.3 US-ST-02；仅 DRAFT，入账为负值）
 */
@Data
public class BillAdjustDto {

    /** DISCOUNT 折扣 / RELIEF 减免 */
    private String type;

    /** 调整金额（>0，≤2 位小数；入账存 −amount） */
    private BigDecimal amount;

    /** 原因（必填） */
    private String remark;
}
