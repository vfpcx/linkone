package com.cangchu.pricing.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 价格变更日志视图对象（P2 定价 Wave 2，调价历史查询）。
 * 雪花 id/operatorUserId 序列化为 String（与其它 VO 一致）。
 */
@Data
@Builder
public class PriceChangeLogVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String batchNo;

    /** 变更类型 PUBLIC_PRICE/CUSTOMER_PRICE */
    private String changeType;

    /** 调整方式 PCT_UP/PCT_DOWN/SET_VALUE/DELTA/DISABLE/SET_EXPIRE */
    private String adjustMode;

    private Integer affectedCount;

    private String targetSummary;

    private LocalDateTime createdAt;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;
}
