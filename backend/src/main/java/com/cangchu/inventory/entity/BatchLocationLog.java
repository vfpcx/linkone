package com.cangchu.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批次移库变更记录（P5-D C2 US-WK-05，V40；25-p5-c-c2 §3.2）。
 *
 * <p>US-WK-05 验收：批次登记簿行内改货位 → 更新 {@code batches.location} + 落本表
 * （from/to/操作人/时间）。仅<b>差异</b>落行（新旧相同=幂等空转不记）；移库零记账副作用
 * （不动 initial_qty/remaining_qty/库存/流水——方案 C 铁律，D-C-1c/1d）。
 * tenant_id 纳入 TenantLine 兜底隔离白名单。
 */
@Data
@TableName("batch_location_logs")
public class BatchLocationLog {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    /** 原货位（无旧值为 NULL） */
    private String fromLocation;

    /** 新货位（清空时为 NULL） */
    private String toLocation;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
