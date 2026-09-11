package com.cangchu.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 规格模板维度（P6 商品规格模型，V42）。
 *
 * <p>示例：{@code {"name":"包装","options":["5L/桶","10L/桶"]}}。维度名同 SPU 内唯一；
 * 取值同维度内唯一。数量上限（维度≤5、单维取值≤20、组合≤60）在
 * {@code SpuSpecSchemaSupport} 内统一校验（ErrorCode 50731/50732）。
 */
@Data
public class SpecDimensionDto {

    @NotBlank(message = "规格维度名不能为空")
    @Size(max = 16, message = "规格维度名长度不能超过16")
    private String name;

    @NotEmpty(message = "规格维度取值不能为空")
    private List<@NotBlank(message = "规格取值不能为空")
            @Size(max = 24, message = "规格取值长度不能超过24") String> options;
}
