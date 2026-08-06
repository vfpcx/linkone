package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 快照重放覆写结果（P4 W2：快照仅缓存语义，recalc 端点可任意重放覆写）。
 */
@Data
@Builder
public class SnapshotRecalcResultVo {

    private LocalDate from;

    private LocalDate to;

    /** 覆写天数 */
    private Integer days;

    /** 覆写后落行总数（双 0 不落行后的实际行数） */
    private Integer rows;
}
