package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日快照 Job 执行结果（P4 W2，14 §1.2；测试直驱断言用）。
 */
@Data
@Builder
public class SnapshotRunResultVo {

    /** 本次快照日（=昨日） */
    private LocalDate snapshotDate;

    /** 参与的 (tenant, wholesaler) 组合数 */
    private Integer pairCount;

    /** 快照日落行数（双 0 不落行后的实际行数） */
    private Integer rowCount;

    /** 本次回补的缺口日（≤7 日窗口内整日缺失才回补） */
    private List<LocalDate> backfilledDates;

    /** 对账哨兵不一致条数（>0 已记 ERROR 日志） */
    private Integer mismatchCount;
}
