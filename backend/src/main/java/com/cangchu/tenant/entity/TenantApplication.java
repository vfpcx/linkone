package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tenant_applications")
public class TenantApplication {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantUserId;

    private String name;
    private String legalName;
    private String licenseNo;
    private String licenseUrl;
    private String contactPhone;
    private String addressText;
    private BigDecimal lng;
    private BigDecimal lat;
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    private LocalDateTime auditedAt;
    private String auditRemark;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
