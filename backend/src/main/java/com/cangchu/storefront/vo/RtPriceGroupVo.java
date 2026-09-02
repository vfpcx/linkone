package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RT「我的价目」中的单个店内批发商组（C1，23-p5-c-c1 §4.1）。
 */
@Data
@Builder
public class RtPriceGroupVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String name;

    /** 该客户在此商户的有效专属价行（createdAt 倒序；含已下架 SKU，listed=false 置灰展示） */
    private List<RtPriceItemVo> items;
}
