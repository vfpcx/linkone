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

    /** 退驻申请时间（WA 发起退驻时刻，R13） */
    private LocalDateTime withdrawApplyAt;

    /** 退驻生效时间（TA 审批通过时刻=申请单 audited_at 快照；60 天恢复/归档窗口唯一时间基准，R13） */
    private LocalDateTime withdrawnAt;

    /** 强制下架时间（R14） */
    private LocalDateTime offlineAt;

    /** 强制下架原因（R14 必填留痕） */
    private String offlineReason;

    /** 归档时间（退驻超 60 天定时归档） */
    private LocalDateTime archivedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private Long createdBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
