package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 店铺撮合配置（P5-A W4，18-p5-design §2.2；归属 tenant 域）。
 *
 * <p>kind=MAIN_SKU（主推商品，ref_id=skuId）/ PIN_WA（置顶批发商，ref_id=wholesalerId）。
 * sort_order 为展示顺序（0 起）。覆盖写语义：按 (store_id, kind) 先删后插，同一时刻每 (kind, ref_id) 至多一行。
 * tenant_id 由 MetaObjectHandler 自动填充（登录态），RT 匿名场景由 Service 显式写入；纳入 TenantLine 白名单。
 */
@Data
@TableName("storefront_featured")
public class StorefrontFeature {

    public static final String KIND_MAIN_SKU = "MAIN_SKU";
    public static final String KIND_PIN_WA = "PIN_WA";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** MAIN_SKU / PIN_WA */
    private String kind;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refId;

    /** 展示顺序（0 起，越小越靠前） */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
