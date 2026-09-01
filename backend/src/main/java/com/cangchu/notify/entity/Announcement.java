package com.cangchu.notify.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台公告（P5-A W3，18-p5-design §2.2；归属 notify 域）。
 *
 * <p>平台级表（无 tenant_id 列）：不在 TenantLine 白名单，天然不做租户隔离，OPS 管辖。
 * 状态机：DRAFT → PUBLISHED → INACTIVE（不可逆；非法迁移 → 50502）。
 * 发布时按 target_roles 展开收件人（ALL/OPS/TA/WK_ST/WA_WE）→ 同事务批量写站内信
 * （TYPE_PLATFORM_ANNOUNCEMENT：通知中心「公告」分组常驻 + 登录弹窗 1 次）。
 */
@Data
@TableName("announcements")
public class Announcement {

    // ==================== 状态常量（状态机，18 §2.2） ====================
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_INACTIVE = "INACTIVE";

    // ==================== 角色组 KEY（target_roles 逗号分隔，发布时展开为具体角色） ====================
    public static final String GROUP_ALL = "ALL";
    public static final String GROUP_OPS = "OPS";
    public static final String GROUP_TA = "TA";
    public static final String GROUP_WK_ST = "WK_ST";
    public static final String GROUP_WA_WE = "WA_WE";

    /** 合法角色组集合（创建/发布校验用） */
    public static final java.util.Set<String> VALID_GROUPS =
            java.util.Set.of(GROUP_ALL, GROUP_OPS, GROUP_TA, GROUP_WK_ST, GROUP_WA_WE);

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 公告标题（≤128） */
    private String title;

    /** 公告正文（≤512） */
    private String content;

    /** 角色组 KEY 逗号分隔（GROUP_* 常量） */
    private String targetRoles;

    /** DRAFT / PUBLISHED / INACTIVE */
    private String status;

    /** 发布时刻（DRAFT 为空） */
    private LocalDateTime publishedAt;

    /** 发布人（OPS） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long publishedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
