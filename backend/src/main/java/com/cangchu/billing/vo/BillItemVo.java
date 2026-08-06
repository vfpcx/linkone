package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 账单明细行 VO（P4 W3）。skuName 冗余带出（L-5）；reversed=该条目已被冲销（R10 链前端标灰）。
 */
@Data
@Builder
public class BillItemVo {

    private String id;

    /** STORAGE/ADJUSTMENT/REVERSAL/STOCKTAKE_IMPACT */
    private String itemType;

    private String skuId;

    private String skuName;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    private Integer qtyDays;

    private Integer palletDays;

    private BigDecimal unitPriceQty;

    private BigDecimal unitPricePallet;

    private BigDecimal amount;

    private String description;

    /** REVERSAL 回指原条目 */
    private String reverseOfItemId;

    /** 该条目已被冲销（存在回指它的 REVERSAL） */
    private Boolean reversed;
}
