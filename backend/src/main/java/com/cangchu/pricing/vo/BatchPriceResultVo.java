package com.cangchu.pricing.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量调价结果（P2 定价 Wave 2）。
 *
 * <p>{@code batchNo} 关联 price_change_logs 那一行；{@code affectedCount} 为实际生效条数；
 * {@code rejectedCount}/{@code rejectedIds} 为因调整后价格 &le; 0（或不属该商户/不存在）被跳过的目标。
 */
@Data
@Builder
public class BatchPriceResultVo {

    private String batchNo;

    private int affectedCount;

    private int rejectedCount;

    /** 被跳过的目标 id（公开价=skuId，专属价=customer_price id），序列化为字符串避免精度丢失。 */
    private List<String> rejectedIds;
}
