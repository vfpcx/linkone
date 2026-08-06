package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 按日下钻行（P4 W2，13-p4-prd §2.3 按日视角：每日快照 + 当段单价现算）。
 * 未设规则天数只有物理量没有金额（amount=null，PRD §2.1）。
 */
@Data
@Builder
public class DailyBreakdownRowVo {

    private LocalDate date;

    /** 当日计费件数（快照 qty 聚合；缺行即 0） */
    private Integer qty;

    /** 当日占用托盘（快照 pallet_qty 聚合） */
    private Integer palletQty;

    /** 当日金额（按 D 所在规则段现算，HALF_UP 到分）；该日无规则段为 null */
    private BigDecimal amount;
}
