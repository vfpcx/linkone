package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * WK 代建出库（P3 BE-W2，12 §3.3 US-WK-02b：提交即扣库存，直达 COMPLETED）。
 *
 * <p>大额校验（06 §3.5b）：qty > 当前在库 × 50% 时须 {@code restatedQty == qty}（复述件数）；
 * {@code confirmed=true} 为前端显著二次确认弹窗的后端凭据——两者不满足均抛 50338。
 */
@Data
public class WkOutboundCreateDto {

    @NotNull(message = "缺少批发商商户")
    private Long wholesalerId;

    @NotNull(message = "缺少商品 SKU")
    private Long skuId;

    @NotNull(message = "缺少出库数量")
    @Min(value = 1, message = "出库数量必须大于0")
    private Integer qty;

    /** 托盘数（可空，默认 0） */
    private Integer palletQty;

    /** 二次确认凭据（必须显式 true，否则 50338） */
    private Boolean confirmed;

    /** 大额复述件数（qty > 在库×50% 时必填且须等于 qty，否则 50338） */
    private Integer restatedQty;
}
