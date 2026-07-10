package com.cangchu.pricing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 部分更新客户专属价请求（P2 定价 Wave 1）。字段全部可选，非空才改。
 */
@Data
public class UpdateCustomerPriceDto {

    /** 专属单价（传入则必须 > 0） */
    @DecimalMin(value = "0", inclusive = false, message = "专属价必须大于0")
    @Digits(integer = 10, fraction = 2, message = "专属价格式不正确")
    private BigDecimal unitPrice;

    /** 失效时间（传入则覆盖） */
    private LocalDateTime expireAt;

    /** 状态 ACTIVE/DISABLED/EXPIRED（传入则覆盖） */
    @Size(max = 16, message = "状态长度不能超过16")
    private String status;
}
