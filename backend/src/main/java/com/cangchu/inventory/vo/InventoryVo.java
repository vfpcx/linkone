package com.cangchu.inventory.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存视图对象。
 *
 * <p>同时作为对外契约：B2 store-front / C document 拿 sku 维度库存（qty>0 即在售可发）。
 * id 为库存行 id；skuId 关联 SkuVo.id。
 */
@Data
@Builder
public class InventoryVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private Integer palletQty;

    private LocalDateTime updatedAt;
}
