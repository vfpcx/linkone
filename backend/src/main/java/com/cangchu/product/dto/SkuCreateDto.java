package com.cangchu.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建 SKU 请求（phase-1 公开价三件套）。
 * S2 校验：name 必填且长度上限；unit_price>0、moq_price>=0、moq_qty>=1。
 */
@Data
public class SkuCreateDto {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称长度不能超过128")
    private String name;

    @Size(max = 256, message = "规格长度不能超过256")
    private String spec;

    /** 单价（公开价，必须 > 0） */
    @NotNull(message = "单价不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "单价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "单价格式不正确")
    private BigDecimal unitPrice;

    /** 起批价（>=0，可空时默认 0） */
    @DecimalMin(value = "0", message = "起批价不能为负")
    @Digits(integer = 10, fraction = 2, message = "起批价格式不正确")
    private BigDecimal moqPrice;

    /** 起批量（>=1，可空时默认 1） */
    @Min(value = 1, message = "起批量必须大于等于1")
    private Integer moqQty;

    @Size(max = 512, message = "主图地址过长")
    private String mainImage;

    /** SPU（phase-1 可空，不强制） */
    private Long spuId;
}
