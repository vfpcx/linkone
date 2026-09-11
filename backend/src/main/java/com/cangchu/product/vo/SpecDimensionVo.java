package com.cangchu.product.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规格模板维度视图（P6 商品规格模型，V42）：{@code {"name":"包装","options":["5L/桶","10L/桶"]}}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpecDimensionVo {

    private String name;

    private List<String> options;
}
