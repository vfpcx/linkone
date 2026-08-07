package com.cangchu.billing.dto;

import lombok.Data;

/**
 * ST 申诉处理入参（P4 W3，14 §3.3；仅 PENDING 50376）
 */
@Data
public class BillDisputeResolveDto {

    /** RESOLVED 成立 / REJECTED 不成立 */
    private String conclusion;

    /** 处理说明（必填留痕） */
    private String resolution;
}
