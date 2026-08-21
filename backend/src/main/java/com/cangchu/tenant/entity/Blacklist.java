package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台黑名单（PLATFORM_TABLE，P2 Wave1）。
 *
 * <p>手机号/营业执照号双键（target_type=PHONE/LICENSE_NO + target_value 唯一）。
 * 平台级共享，不做租户隔离——已按决策 O-6 排除在 TenantLine 白名单之外。
 */
@Data
@TableName("blacklist")
public class Blacklist {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** PHONE / LICENSE_NO */
    private String targetType;

    private String targetValue;

    /**
     * PII 硬化阶段 0（V27）：HMAC-SHA256 盲索引影子列——仅 PHONE 行双写/回填，
     * LICENSE_NO 行恒 NULL（15 §2-1）。读路径（isBlacklisted/查重/LIKE 检索）仍走
     * targetValue，不消费本列。JsonIgnore 保证 page records 实体直出的响应形状零变化。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String targetValueHmac;

    private String reason;

    /** OPS 操作人（created_by 语义） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    /** ACTIVE / REMOVED */
    private String status;

    private LocalDateTime removedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
