package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * RT 进店页可下单的在售 SKU 视图：公开价（单价/起批价/起批量）+ 当前库存量。
 *
 * <p>给 C2 询价对齐：RT 提交询价时以 (storeId, wholesalerId, skuId, qty) 为入参，
 * skuId 即本对象的 skuId，moqQty 为起批量（前端可据此做最小起订校验）。
 *
 * <p>P5-A W4（18-p5-design §4.4）：{@link #featured} 主推标记；某商户列表内主推 SKU 前置。
 */
@Data
@Builder
public class StoreSkuVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String name;

    private String spec;

    private String mainImage;

    /** 公开价：单价 */
    private BigDecimal unitPrice;

    /** 公开价：起批价 */
    private BigDecimal moqPrice;

    /** 公开价：起批量 */
    private Integer moqQty;

    /** 当前库存量（qty>0 才会出现在列表中） */
    private Integer stockQty;

    /**
     * 客户专属价（P2 定价 Wave 3b）：仅当访客为已登录 RT 且该 (RT手机号, sku) 命中有效专属价、
     * 且专属价 <b>不同于</b>公开单价 {@link #unitPrice} 时才有值；匿名访客 / 无专属价 / 专属价==公开价 → null。
     *
     * <p>语义：{@code unitPrice} 恒为公开单价（不因登录态变化）；{@code matchedPrice} 为叠加字段——
     * 前端有值即展示"您的专属价"，null 则仅展示公开价。qty 恒按 1 解析（进店浏览态无数量）。
     */
    private BigDecimal matchedPrice;

    /** 是否主推商品（P5-A W4 撮合配置 MAIN_SKU；该 SKU 出现在 featuredSkuIds 中） */
    private Boolean featured;
}
