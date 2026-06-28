package com.cangchu.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU（phase-1 A2：公开价三件套 + 上下架）。
 * tenant_id 由 MetaObjectHandler 自动填充；listed 默认 true。
 * spu_id 可空（phase-1 不强制平台 SPU，name+spec 自由录入）。
 */
@Data
@TableName("skus")
public class Sku {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** 平台 SPU（phase-1 可空，不强制） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long spuId;

    private String name;

    /** 规格文本 */
    private String spec;

    /** 单价（公开价，>0） */
    private BigDecimal unitPrice;

    /** 起批价（>=0） */
    private BigDecimal moqPrice;

    /** 起批量（>=1） */
    private Integer moqQty;

    /** 上下架（默认 true=在售） */
    private Boolean listed;

    /** 主图（phase-1 可空） */
    private String mainImage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
