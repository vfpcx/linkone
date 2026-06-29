package com.cangchu.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水（phase-1 B1：仅 INBOUND/OUTBOUND）。
 * 与库存变动在同一事务内写入；tenant_id 自动填充，纳入 TenantLine 隔离白名单。
 */
@Data
@TableName("stock_movements")
public class StockMovement {

    /** 入库流水类型 */
    public static final String TYPE_INBOUND = "INBOUND";
    /** 出库流水类型 */
    public static final String TYPE_OUTBOUND = "OUTBOUND";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** INBOUND / OUTBOUND */
    private String type;

    /** 变动数量（正数；方向由 type 表达） */
    private Integer qty;

    /** 关联单据号（入库/出库单，可空） */
    private String refDocNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
