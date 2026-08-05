package com.cangchu.billing.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 快照重放覆写请求（P4 W2）。wholesalerId 可空=本租户全部有流水商户。
 */
@Data
public class SnapshotRecalcDto {

    private Long wholesalerId;

    /** 起始日（必填；≤ to） */
    private LocalDate from;

    /** 截止日（必填；≤ 昨日——今日快照依赖今日流水未闭合，不可预生成） */
    private LocalDate to;
}
