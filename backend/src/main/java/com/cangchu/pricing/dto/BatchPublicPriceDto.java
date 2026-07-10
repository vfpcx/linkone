package com.cangchu.pricing.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量调公开价请求（P2 定价 Wave 2）。
 *
 * <p>对某商户下一批 SKU 的公开价做批量调整。目标字段 {@code targetField}
 * 默认 unitPrice，可选 moqPrice。调整方式 {@code adjustMode} 仅支持
 * PCT_UP/PCT_DOWN/SET_VALUE/DELTA（DISABLE/SET_EXPIRE 为专属价专用，公开价拒绝）。
 * 调整后价格必须 &gt; 0，否则该 SKU 被跳过并计入 rejected。
 */
@Data
public class BatchPublicPriceDto {

    @NotNull(message = "批发商不能为空")
    private Long wholesalerId;

    /** 目标 SKU 列表，最多 200 个。 */
    @NotEmpty(message = "SKU 列表不能为空")
    @Size(max = 200, message = "单次批量调价 SKU 数不能超过200")
    private List<Long> skuIds;

    /** 调整方式：PCT_UP/PCT_DOWN/SET_VALUE/DELTA（公开价仅此四种）。 */
    @NotBlank(message = "调整方式不能为空")
    private String adjustMode;

    /** 调整值：百分比（PCT_UP/PCT_DOWN，如 10 表示 10%）或金额（SET_VALUE/DELTA）。 */
    private BigDecimal value;

    /** 目标价格字段：unitPrice(默认)/moqPrice。 */
    private String targetField;
}
