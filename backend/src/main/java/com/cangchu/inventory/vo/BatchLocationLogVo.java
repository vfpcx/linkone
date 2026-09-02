package com.cangchu.inventory.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批次移库变更记录视图（P5-D C2，25-p5-c-c2 §4.4；GET /tenant/batches/{id}/location-logs）。
 */
@Data
@Builder
public class BatchLocationLogVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    /** 原货位（无旧值为 null） */
    private String fromLocation;

    /** 新货位（清空时为 null） */
    private String toLocation;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    private LocalDateTime createdAt;
}
