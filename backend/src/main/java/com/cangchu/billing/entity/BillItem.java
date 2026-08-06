package com.cangchu.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 账单明细行（P4 W3，14 §4 V26）。
 *
 * <p>四类条目（PRD 13-p4 §2.3）：STORAGE 仓储费（SKU×规则段，账单主体）/ ADJUSTMENT 调整
 * （折扣减免存负值 + 跨月回溯差额可正可负）/ REVERSAL 冲销（=−原值，回指不删原条目，R10）/
 * STOCKTAKE_IMPACT 盘点影响（金额恒 0 纯展示，费用已含于 STORAGE 行）。
 */
@Data
@TableName("bill_items")
public class BillItem {

    /** 仓储费（货品 × 规则段，账单主体） */
    public static final String TYPE_STORAGE = "STORAGE";
    /** 调整（ST 折扣/减免存负值；跨月回溯差额可正可负） */
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    /** 冲销（R10 反向条目 amount=−原值，reverse_of_item_id 回指） */
    public static final String TYPE_REVERSAL = "REVERSAL";
    /** 盘点影响（上月每笔盘盈/盘亏一行，amount 恒 0 纯展示） */
    public static final String TYPE_STOCKTAKE_IMPACT = "STOCKTAKE_IMPACT";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long billId;

    private String itemType;

    /** STORAGE/STOCKTAKE_IMPACT 落值 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** STORAGE 规则段起（R20 分段一段一行） */
    private LocalDate periodStart;

    /** STORAGE 规则段止 */
    private LocalDate periodEnd;

    /** 件·天（未启用维 NULL） */
    private Integer qtyDays;

    /** 托盘·天（未启用维 NULL） */
    private Integer palletDays;

    /** 段内件·天单价快照 */
    private BigDecimal unitPriceQty;

    /** 段内托盘·天单价快照 */
    private BigDecimal unitPricePallet;

    /** 条目金额（ADJUSTMENT 存负值；REVERSAL=−原值；STOCKTAKE_IMPACT 恒 0） */
    private BigDecimal amount;

    /** 说明（调整原因/跨月差额注明/盘盈亏展示文案） */
    private String description;

    /** REVERSAL 回指原条目（R10 冲销链，不删原条目） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reverseOfItemId;

    /** ST 调整/冲销留痕 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
