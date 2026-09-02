package com.cangchu.document.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 今日单据计数（P5-C TA 工作台，19 §3）：created_at ≥ 今日 0 点、不限状态。
 */
@Data
@Builder
public class TodayCountsDto {
    /** 今日入库登记单数 */
    private long inboundCount;
    /** 今日出库单数 */
    private long outboundCount;
    /** 今日询价单数 */
    private long inquiryCount;
}
