package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * TA 账单总览（P4 W5 补口，14 §6.3 US-TA-08）：按月（可缺省=全部）应收/已收/未收汇总
 * + 全局状态分布 + 逐商户行（requireTa）。
 */
@Data
@Builder
public class BillsOverviewVo {

    /** 账期月 yyyy-MM；null=全部月份 */
    private String month;

    /** 应收合计 Σtotal_amount */
    private BigDecimal receivable;

    /** 已收合计 Σpaid_amount */
    private BigDecimal received;

    /** 未收合计 = 应收 − 已收 */
    private BigDecimal outstanding;

    /** 账单总张数 */
    private Long billCount;

    /** 全局状态分布：状态码 → 张数（仅出现的状态有键） */
    private Map<String, Long> statusCounts;

    /** 逐商户汇总行（未收降序，同值按商户 id 升序稳定） */
    private List<BillsOverviewRowVo> rows;
}
