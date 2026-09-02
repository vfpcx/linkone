package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户跟进提醒（C3 · US-WE-04，24-p5-c-c3 §3.2，document 域）。
 *
 * <p>reminded_at 为空 = 未触发；FollowupReminderJob 每 5 分钟扫 {@code remind_at <= now AND reminded_at IS NULL}
 * 到点站内信给 created_by（同事务 CAS 置 reminded_at 防重）。行同存 tenant_id/wholesaler_id：
 * tenant_id 进 TenantLine 白名单；wholesaler_id 冗余供收敛校验与 Job 直取。
 */
@Data
@TableName("followup_reminders")
public class FollowupReminder {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonIgnore
    private Long wholesalerId;

    @JsonIgnore
    private Long customerFollowupId;

    /** 提醒内容（≤200，必填） */
    private String content;

    /** 提醒时点（须晚于 now，K-4） */
    private LocalDateTime remindAt;

    /** 站内信触发时刻（空=未触发；CAS 防重位） */
    @JsonIgnore
    private LocalDateTime remindedAt;

    /** 创建人（=站内信收件人） */
    @JsonIgnore
    private Long createdBy;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
