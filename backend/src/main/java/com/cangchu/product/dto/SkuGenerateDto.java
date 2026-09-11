package com.cangchu.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按规格模板批量生成 SKU（P6/V42）。
 *
 * <p>缺省模式（items 为空）：按 SPU 规格模板做<b>完整笛卡尔积</b>，每个组合生成一个独立 SKU，
 * 价格取本对象默认三件套。
 *
 * <p>指定模式（items 非空）：只生成给定组合（须与模板匹配），支持逐组合覆盖价格。
 * 两种模式下，与存量组合（同 SPU 下 spec_key 相同）重复一律拒绝 50733（整单回滚）。
 */
@Data
public class SkuGenerateDto {

    /** 默认单价（>0，必填） */
    @NotNull(message = "单价不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "单价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "单价格式不正确")
    private BigDecimal unitPrice;

    /** 默认起批价（可空 → 0） */
    @DecimalMin(value = "0", message = "起批价不能为负")
    @Digits(integer = 10, fraction = 2, message = "起批价格式不正确")
    private BigDecimal moqPrice;

    /** 默认起批量（可空 → 1） */
    @Min(value = 1, message = "起批量必须大于等于1")
    private Integer moqQty;

    /** 生成后是否上架（可空 → true） */
    private Boolean listed;

    /** 主图（可空；缺省沿用 SPU 标准图） */
    @Size(max = 512, message = "主图地址过长")
    private String mainImage;

    /** 指定组合（可空 = 完整笛卡尔积）；每条可覆盖价格 */
    @Valid
    @Size(max = 60, message = "单次最多生成 60 个规格组合")
    private List<SpuSpecItemDto> items;
}
