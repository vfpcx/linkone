package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 店铺撮合配置视图（P5-A W4，18-p5-design §4.3/§4.4；归属 tenant 域）。
 *
 * <p>id 以字符串序列化（沿项目先例：所有 id 均字符串，避免 JS 大整数精度丢失）。
 * 列表有序：按 sort_order 升序（覆盖写时即保存数组顺序）。
 * storefront 域经 Service 出口消费本对象，禁止跨域 mapper 直连。
 */
@Data
@Builder
public class StorefrontFeatureVo {

    /** 主推商品 id 序（MAIN_SKU，按 sort_order 升序） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> mainSkuIds;

    /** 置顶批发商 id 序（PIN_WA，按 sort_order 升序） */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> pinWaIds;
}
