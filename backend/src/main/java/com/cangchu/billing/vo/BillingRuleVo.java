package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计费规则版本视图（P4 W1，14 §2.2 RuleVo）。
 * effectiveTo=null 表示当前生效版本。
 */
@Data
@Builder
public class BillingRuleVo {

    private String id;

    /** 件·天计费启用 */
    private Boolean qtyEnabled;

    /** 件·天单价（未启用为 null） */
    private BigDecimal pricePerQtyDay;

    /** 托盘·天计费启用 */
    private Boolean palletEnabled;

    /** 托盘·天单价（未启用为 null） */
    private BigDecimal pricePerPalletDay;

    /** 生效日（首版=首次保存日；变更日按新规则） */
    private LocalDate effectiveFrom;

    /** 失效日（含）；null=当前生效 */
    private LocalDate effectiveTo;

    /** 版本号（自 1 递增；同日覆写不递增） */
    private Integer version;
}
