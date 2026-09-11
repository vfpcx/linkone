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

    /** 平台 SPU（phase-1 可空；P5-D D56 可挂接 ACTIVE 标品） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long spuId;

    /** 标品名称快照（挂接/合并时整体刷新，22 §2.3；以 OPS 标品为准） */
    private String spuName;

    /** 一级品类快照 */
    private String spuCategoryL1;

    /** 二级品类快照 */
    private String spuCategoryL2;

    private String name;

    /** 规格文本（历史自由文本；P6 批量生成的聚合 SKU 落规格摘要，展示用） */
    private String spec;

    /** 结构化规格键（P6/V42）：模板维度=取值 的有序紧凑 JSON，同 SPU 下唯一；历史/平台挂接为 NULL */
    private String specKey;

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
