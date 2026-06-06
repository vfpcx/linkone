package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 店铺/仓库
 */
@Data
@TableName("stores")
public class Store {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String name;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long addressId;

    private BigDecimal lng;

    private BigDecimal lat;

    private String coordinateSystem;

    private Integer totalCapacityQty;

    private Integer totalCapacityPallet;

    private String capacityVisibility;

    private String capacityPrecision;

    private String businessHours;

    private String intro;

    private String coverUrl;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
