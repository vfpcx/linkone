package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * RT 首页「搜商品找仓库」公开检索结果项（US-RT-06 后续 · 先搜货再进店）。
 *
 * <p>跨仓检索在售 SKU：公开价 + 当前库存 + 所属商户/仓库归属（tenantSimpleCode 可直接复用进店）。
 * 只携带公开字段——不含联系人等 PII（联系方式仍走询价确认后 /pii/phone-reveal，D-RT-01 口径）。
 *
 * <p>与 {@link StoreSkuVo} 的区别：那是「进店后某商户在售列表」（店内视角），本 VO 是
 * 「跨仓全局检索」（首页视角），额外携带商户名/店铺名/店铺码/距离，用于引导买家进对仓库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RtSkuSearchItemVo {

    /** SKU id（进店后提交询价用） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 商品名 */
    private String name;

    /** 规格文本 */
    private String spec;

    /** 主图 */
    private String mainImage;

    /** 公开价：单价 */
    private BigDecimal unitPrice;

    /** 公开价：起批价 */
    private BigDecimal moqPrice;

    /** 公开价：起批量 */
    private Integer moqQty;

    /** 当前库存量（检索口径 qty&gt;0） */
    private Integer stockQty;

    /** 所属批发商 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 所属批发商名称 */
    private String wholesalerName;

    /** 所属租户（仓库）id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 所属店铺 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    /** 所属店铺名 */
    private String storeName;

    /** 店铺码（= 租户简码 tenantSimpleCode，点击直接进店） */
    private String tenantSimpleCode;

    /**
     * 与当前请求坐标的直线距离（米）。
     * 当仓库未设坐标或请求未传坐标时为 null。
     */
    private Integer distanceMeters;
}
