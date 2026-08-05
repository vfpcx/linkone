package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 月度仓储费明细行（P4 W2，14 §1.3：SKU × 规则段一行；W3 出账 STORAGE 条目直接消费）。
 * skuName 冗余带出（L-5 口径，盘点详情带名先例）；未启用维 days/单价为 null。
 */
@Data
@Builder
public class StorageLineVo {

    private String skuId;

    /** SKU 名称冗余（L-5；SKU 已删/跨界不可见时 null，前端兜底展示 id） */
    private String skuName;

    /** 段内计费起（=max(规则段 from, 账期起点)） */
    private LocalDate periodStart;

    /** 段内计费止（=min(规则段 to, 月末)） */
    private LocalDate periodEnd;

    /** 件·天（件·天维未启用为 null） */
    private Long qtyDays;

    /** 托盘·天（托盘·天维未启用为 null） */
    private Long palletDays;

    /** 件·天单价快照（未启用 null） */
    private BigDecimal unitPriceQty;

    /** 托盘·天单价快照（未启用 null） */
    private BigDecimal unitPricePallet;

    /** 段金额（先算后舍 HALF_UP 到分） */
    private BigDecimal amount;
}
