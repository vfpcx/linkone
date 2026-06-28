package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RT 进店页内的单个店内批发商（仅 ACTIVE）+ 其在售 SKU。
 */
@Data
@Builder
public class StoreWholesalerVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String name;

    private String intro;

    private String status;

    /** 该批发商在售 SKU（listed=true 且 库存 qty>0），含公开价 + 当前库存 */
    private List<StoreSkuVo> skus;
}
