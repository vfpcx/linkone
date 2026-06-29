package com.cangchu.product.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SKU 视图对象。
 *
 * <p>同时作为 {@code listByTenantForRt} 给 B2 store-front 的对外契约：
 * phase-1 无专属价，price 字段直接是公开价（unitPrice/moqPrice/moqQty）。
 * RT 入口（B2）只会拿到 listed=true 的记录；wholesalerId 用于按商户聚合，
 * id（sku_id）用于 B1 inventory 关联库存。
 */
@Data
@Builder
public class SkuVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long spuId;

    private String name;

    private String spec;

    /** 公开价：单价 */
    private BigDecimal unitPrice;

    /** 公开价：起批价 */
    private BigDecimal moqPrice;

    /** 公开价：起批量 */
    private Integer moqQty;

    private Boolean listed;

    private String mainImage;

    private LocalDateTime createdAt;
}
