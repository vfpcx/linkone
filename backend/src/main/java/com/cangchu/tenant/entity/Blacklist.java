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

    /** W8-B1（V31）：AES-GCM(target_value) 密文影子列——仅 PHONE 行双写/回填，LICENSE_NO 行恒 NULL。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String targetValueCipher;

    private String reason;

    /** OPS 操作人（created_by 语义） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    /** ACTIVE / REMOVED */
    private String status;

    /**
     * 解除时间（remove 置 now；add 复活分支置 null）。updateStrategy=ALWAYS 允许 null 下发，
     * 否则复活分支的 setRemovedAt(null) 被 MP 空值跳过策略吞掉、残留旧解除时间（findings「B2 复活 removed_at 残留」）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime removedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
