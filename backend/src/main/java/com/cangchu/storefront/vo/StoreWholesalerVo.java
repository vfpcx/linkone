package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RT 进店页内的单个店内批发商（仅 ACTIVE）+ 其在售 SKU。
 *
 * <p>P5-A W4（18-p5-design §4.4）：{@link #pinned} 置顶标记；店铺批发商列表内置顶商户前置。
 */
@Data
@Builder
public class StoreWholesalerVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String name;

    private String intro;

    private String status;

    /** 该批发商在售 SKU（listed=true 且 库存 qty>0），含公开价 + 当前库存（主推 SKU 前置） */
    private List<StoreSkuVo> skus;

    /** 是否置顶批发商（P5-A W4 撮合配置 PIN_WA；该商户出现在 pinnedWholesalerIds 中） */
    private Boolean pinned;
}
