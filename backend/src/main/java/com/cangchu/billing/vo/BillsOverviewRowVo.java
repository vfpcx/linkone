package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * TA 账单总览逐商户行（P4 W5 补口，14 §6.3 US-TA-08）：
 * 应收/已收/未收/账单数/状态分布，供总览页下钻单 WA。
 */
@Data
@Builder
public class BillsOverviewRowVo {

    private String wholesalerId;

    private String wholesalerName;

    /** 应收合计 Σtotal_amount */
    private BigDecimal receivable;

    /** 已收合计 Σpaid_amount */
    private BigDecimal received;

    /** 未收合计 = 应收 − 已收 */
    private BigDecimal outstanding;

    /** 账单张数 */
    private Long billCount;

    /** 状态分布：状态码（DRAFT/DISPATCHED/...）→ 张数（仅出现的状态有键） */
    private Map<String, Long> statusCounts;
}
