package com.cangchu.pricing.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设置客户专属价请求（P2 定价 Wave 1）。
 * 按唯一键 (wholesalerId, rtPhone, skuId) upsert；unitPrice 必须 > 0。
 */
@Data
public class SetCustomerPriceDto {

    @NotNull(message = "批发商不能为空")
    private Long wholesalerId;

    @NotNull(message = "SKU 不能为空")
    private Long skuId;

    @NotBlank(message = "客户手机号不能为空")
    @Size(max = 32, message = "客户手机号长度不能超过32")
    private String rtPhone;

    /** 专属单价（必须 > 0） */
    @NotNull(message = "专属价不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "专属价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "专属价格式不正确")
    private BigDecimal unitPrice;

    /** 失效时间（可空=永久） */
    private LocalDateTime expireAt;
}
