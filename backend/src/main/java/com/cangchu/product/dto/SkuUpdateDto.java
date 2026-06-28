package com.cangchu.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新 SKU 请求（部分字段，null 表示不改）。
 * 提供的字段仍走 S2 范围校验；name/价格规则与创建一致。
 */
@Data
public class SkuUpdateDto {

    @Size(max = 128, message = "商品名称长度不能超过128")
    private String name;

    @Size(max = 256, message = "规格长度不能超过256")
    private String spec;

    @DecimalMin(value = "0", inclusive = false, message = "单价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "单价格式不正确")
    private BigDecimal unitPrice;

    @DecimalMin(value = "0", message = "起批价不能为负")
    @Digits(integer = 10, fraction = 2, message = "起批价格式不正确")
    private BigDecimal moqPrice;

    @Min(value = 1, message = "起批量必须大于等于1")
    private Integer moqQty;

    @Size(max = 512, message = "主图地址过长")
    private String mainImage;
}
