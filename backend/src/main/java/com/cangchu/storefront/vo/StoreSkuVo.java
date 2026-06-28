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
}
