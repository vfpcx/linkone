package com.cangchu.pricing.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户专属价视图对象（P2 定价 Wave 1）。
 * 雪花 id 序列化为 String（与 SkuVo 一致）。
 */
@Data
@Builder
public class CustomerPriceVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private String rtPhone;

    private BigDecimal unitPrice;

    private String status;

    private String source;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
