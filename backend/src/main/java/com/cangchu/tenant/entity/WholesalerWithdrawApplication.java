package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批发商退驻申请（P2 Wave2 R13）。
 * 双轨模式沿用 Wave1：申请表承载审批流，通过后翻转 wholesalers 主体状态（WITHDRAWN）。
 * audited_at（通过时刻）是 60 天恢复/归档窗口的唯一时间基准（同步快照到 wholesalers.withdrawn_at）。
 */
@Data
@TableName("wholesaler_withdraw_applications")
public class WholesalerWithdrawApplication {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** WA 主动撤回（仅 PENDING 可撤；撤回后可重新发起） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantUserId;

    /** 退驻原因（选填） */
    private String reason;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    /** 审核时间（通过时刻=60 天窗口起点） */
    private LocalDateTime auditedAt;

    /** 审核意见（驳回必填） */
    private String auditRemark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
