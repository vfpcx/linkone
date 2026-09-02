package com.cangchu.pricing.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户专属价跨域轻量出口（C1 RT 价目，23-p5-c-c1 §5.1）。
 *
 * <p>供 storefront 域在 RT 场景按 (wholesalerId, rtPhoneHmac) 盲查该客户的有效专属价行，
 * 不含任何手机号/尾号字段（RT 端不消费），只回组装价目行所需的最小列——对齐
 * {@code TenantBatchConfigVo} 跨域轻量 VO 先例（G-S1/G-S2：不跨域直连 CustomerPriceMapper、
 * 不跨域直用 entity）。
 */
@Data
@Builder
public class CustomerPriceRef {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 专属单价（>0） */
    private BigDecimal unitPrice;

    /** 来源 manual/from_inquiry */
    private String source;

    /** 失效时间（空=永久） */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
