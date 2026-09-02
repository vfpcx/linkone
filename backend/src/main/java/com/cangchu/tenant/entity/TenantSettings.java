package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant_settings")
public class TenantSettings {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private Integer batchEnabled;

    /** 批次功能最近启用时刻（V22，FIFO 推算的流水切割时点，13-p3b §3.2；关后保留作历史锚点） */
    private LocalDateTime batchEnabledAt;

    /** 货位功能开关（V40，25-p5-c-c2 §3.1；默认 0=关闭；无副作用走通用店铺设置 PUT /tenant/me） */
    private Integer locationEnabled;

    private String photoMode;
    private String billingDim;
    private Integer expiryThresholdDays;
    private String displayImageSource;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long updatedBy;
}
