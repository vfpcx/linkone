package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批发商商户（phase-1 仅 TA 自营创建）。
 * tenant_id 由 MetaObjectHandler 自动填充；status 默认 ACTIVE、source 默认 SELF_OPERATED。
 */
@Data
@TableName("wholesalers")
public class Wholesaler {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String name;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerUserId;

    /** 营业资质（phase-1 占位，可空） */
    private String license;

    private String intro;

    private String status;

    private String source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
