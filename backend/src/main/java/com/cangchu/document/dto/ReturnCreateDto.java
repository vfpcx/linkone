package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * WA 发起退货申请（P3b T3-W1，13 §5.2）。
 * wholesalerId 由 sku 真实归属推导（S4，不取客户端）；tenantId 同理。
 */
@Data
public class ReturnCreateDto {

    @NotNull(message = "缺少商品 SKU")
    private Long skuId;

    @NotNull(message = "缺少退货件数")
    @Min(value = 1, message = "退货件数必须大于0")
    private Integer qty;

    /** 退货原因/备注（选填 ≤512） */
    private String remark;
}
