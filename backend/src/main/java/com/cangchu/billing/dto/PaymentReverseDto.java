package com.cangchu.billing.dto;

import lombok.Data;

/**
 * 回款冲销入参（P4 W3，14 §3.3 R12；理由必填 + confirmed 二次确认凭据 40003）
 */
@Data
public class PaymentReverseDto {

    /** 冲销理由（必填留痕） */
    private String reason;

    /** 二次确认凭据（缺失 40003，batch-toggle 先例） */
    private Boolean confirmed;
}
