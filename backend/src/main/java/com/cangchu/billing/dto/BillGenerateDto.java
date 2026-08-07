package com.cangchu.billing.dto;

import lombok.Data;

/**
 * 手动补生成月账单入参（P4 W3，14 §3.2/§6.2；幂等，重复补跑返回既有单）
 */
@Data
public class BillGenerateDto {

    /** 账期月 yyyy-MM（须为已结束的月份） */
    private String month;
}
