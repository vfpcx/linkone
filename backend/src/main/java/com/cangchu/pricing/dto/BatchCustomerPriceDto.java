package com.cangchu.pricing.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量调专属价请求（P2 定价 Wave 2）。
 *
 * <p>对某商户下的 customer_prices 行做批量调整。目标行有两种指定方式（二选一）：
 * <ul>
 *   <li>显式 {@code ids}（最多 500 个）；</li>
 *   <li>过滤条件 {@code skuId} / {@code rtPhone}（任一或组合，命中该商户下匹配行）。</li>
 * </ul>
 * 调整方式 {@code adjustMode} 六种全部适用：
 * PCT_UP/PCT_DOWN/SET_VALUE/DELTA 改 unit_price（结果须 &gt; 0，否则跳过）；
 * DISABLE 置 status=DISABLED；SET_EXPIRE 置 expire_at={@code expireAt}。
 */
@Data
public class BatchCustomerPriceDto {

    @NotNull(message = "批发商不能为空")
    private Long wholesalerId;

    /** 显式专属价 id 列表（与过滤条件二选一），最多 500 个。 */
    @Size(max = 500, message = "单次批量调价专属价数不能超过500")
    private List<Long> ids;

    /** 过滤：按 SKU（可空）。 */
    private Long skuId;

    /** 过滤：按客户手机号（可空）。 */
    private String rtPhone;

    /** 调整方式：PCT_UP/PCT_DOWN/SET_VALUE/DELTA/DISABLE/SET_EXPIRE。 */
    @NotBlank(message = "调整方式不能为空")
    private String adjustMode;

    /** 调整值：百分比或金额（价格类调整方式必填）。 */
    private BigDecimal value;

    /** 失效时间（SET_EXPIRE 必填）。 */
    private LocalDateTime expireAt;
}
