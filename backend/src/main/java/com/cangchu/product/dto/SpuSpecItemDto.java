package com.cangchu.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 批量生成 SKU 的单条规格组合（P6/V42）。价格三件套可空——空则取
 * {@link SkuGenerateDto} 的默认值，非空则覆盖（实现「不同规格价格各自独立」）。
 *
 * <p>options 为「维度名 → 取值」映射，须与 SPU 规格模板完全匹配（维度齐全且取值在模板内），
 * 否则 50734。
 */
@Data
public class SpuSpecItemDto {

    private Map<String, String> options;

    /** 覆盖单价（可空 → 用默认值；>0） */
    @DecimalMin(value = "0", inclusive = false, message = "单价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "单价格式不正确")
    private BigDecimal unitPrice;

    /** 覆盖起批价（可空 → 用默认值；>=0） */
    @DecimalMin(value = "0", message = "起批价不能为负")
    @Digits(integer = 10, fraction = 2, message = "起批价格式不正确")
    private BigDecimal moqPrice;

    /** 覆盖起批量（可空 → 用默认值；>=1） */
    @Min(value = 1, message = "起批量必须大于等于1")
    private Integer moqQty;
}
