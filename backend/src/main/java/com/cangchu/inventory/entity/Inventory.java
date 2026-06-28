package com.cangchu.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存（phase-1 B1：批次关闭，单 sku 维度）。
 * tenant_id 由 MetaObjectHandler 自动填充；唯一索引 (wholesaler_id, sku_id)。
 * 行同存 wholesaler_id + tenant_id，纳入 TenantLine 隔离白名单。
 */
@Data
@TableName("inventories")
public class Inventory {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 现存数量（>=0） */
    private Integer qty;

    /** 托盘数（phase-1 占位统计，默认 0） */
    private Integer palletQty;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
