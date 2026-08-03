package com.cangchu.inventory.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次视图（P3b T4-W1）。remaining_qty 为离线推算值（UI 标注「推算 · 截至今日 02:00」）。
 */
@Data
@Builder
public class BatchVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;

    /** 剩余天数（expiry − today；无到效期为 null，过期为负） */
    private Long remainingDays;

    private Integer initialQty;

    /** FIFO 推算剩余（非记账值） */
    private Integer remainingQty;

    private String status;
    private String source;
    private LocalDateTime clearedAt;
    private LocalDateTime createdAt;
}
