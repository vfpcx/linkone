package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户主表
 */
@Data
@TableName("tenants")
public class Tenant {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String tenantSimpleCode;

    private String name;

    private String legalName;

    private String licenseNo;

    private String licenseUrl;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long contactUserId;

    /** W8-B1（V31）：AES-GCM(contact_phone) 密文影子列。W8-B3 起为联系人手机号唯一载体（明文列已下线）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String contactPhoneCipher;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    private LocalDateTime auditedAt;

    private String auditRemark;

    private Integer createdByOps;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
