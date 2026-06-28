package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RT 扫码进店页聚合视图（phase-1 B2 · 只读）。
 *
 * <p>这是给 D1b RT H5 前端的对外契约根对象。RT 用 storeId 或店铺码(code=tenantSimpleCode)
 * 进店，后端解析到 tenantId，再聚合：店铺基本信息 + 店内 ACTIVE 批发商 + 每个批发商的在售 SKU
 * （listed=true 且 库存 qty>0）+ 公开价 + 当前库存量。
 *
 * <p>所有 id（storeId/tenantId/wholesalerId/skuId）均以字符串序列化，避免 JS 大整数精度丢失。
 */
@Data
@Builder
public class StoreFrontVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 店铺码（= 租户简码 tenantSimpleCode，可作为进店 code 复用） */
    private String storeCode;

    private String storeName;

    private String intro;

    private String coverUrl;

    private String businessHours;

    private String status;

    /** 店内批发商（仅 ACTIVE），各自带在售 SKU 列表 */
    private List<StoreWholesalerVo> wholesalers;
}
