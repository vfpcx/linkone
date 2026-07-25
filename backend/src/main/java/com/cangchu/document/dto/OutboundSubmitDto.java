package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * WA 手动出库申请（P3 BE-W2，12 §1.3 WA_SUBMIT：提交瞬间扣库存，状态起点 PENDING_ACCEPT）。
 */
@Data
public class OutboundSubmitDto {

    @NotNull(message = "缺少批发商商户")
    private Long wholesalerId;

    @NotNull(message = "缺少商品 SKU")
    private Long skuId;

    @NotNull(message = "缺少出库数量")
    @Min(value = 1, message = "出库数量必须大于0")
    private Integer qty;

    /** 托盘数（可空，默认 0；撤回/作废回补按此还原） */
    private Integer palletQty;
}
