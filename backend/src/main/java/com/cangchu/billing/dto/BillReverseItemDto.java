package com.cangchu.billing.dto;

import lombok.Data;

/**
 * 条目冲销入参（P4 W3，14 §3.3 R10；仅 DRAFT，仅 STORAGE/ADJUSTMENT 且未被冲销过）
 */
@Data
public class BillReverseItemDto {

    /** 被冲销条目 id */
    private Long itemId;
}
