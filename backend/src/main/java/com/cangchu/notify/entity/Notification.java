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
 * 站内信（P3 BE-W1 最小版，12 §4.3）。
 *
 * <p>拉取式（列表 + 轮询 unread-count），与业务同事务写入（同回滚，不引入 MQ）。
 * tenant_id 由业务侧显式落值（Job 系统态写入无 TenantContext，从单据带入）；纳入 TenantLine 白名单。
 * 短信通道为可开关 TODO（Q-D06 未收口，业务短信模板/签名未备案），站内信为 P3 验收口径。
 */
@Data
@TableName("notifications")
public class Notification {

    // ==================== 类型常量（12 §4.3；BE-W2 补 OUTBOUND_* 触发） ====================
    /** WK 代建登记 → 通知归属 WA 确认 */
    public static final String TYPE_INBOUND_PENDING_CONFIRM = "INBOUND_PENDING_CONFIRM";
    /** 72h 超时自动确认 → 通知 WA */
    public static final String TYPE_INBOUND_AUTO_CONFIRMED = "INBOUND_AUTO_CONFIRMED";
    /** WA 异议成立仲裁单 → 通知 TA（审批中心角标）+ WK */
    public static final String TYPE_DISPUTE_CREATED = "DISPUTE_CREATED";
    /** 仲裁裁决 → 通知双方（WA+WK） */
    public static final String TYPE_ARBITRATION_DECIDED = "ARBITRATION_DECIDED";
    /** R4 撤回申请（BE-W2 启用） */
    public static final String TYPE_OUTBOUND_WITHDRAW_REQUESTED = "OUTBOUND_WITHDRAW_REQUESTED";
    /** 代建出库（BE-W2 启用） */
    public static final String TYPE_OUTBOUND_PROXY_CREATED = "OUTBOUND_PROXY_CREATED";
    /** 30 天客诉（BE-W2 启用） */
    public static final String TYPE_COMPLAINT_CREATED = "COMPLAINT_CREATED";

    // 跳转引用类型
    public static final String REF_INBOUND = "INBOUND";
    public static final String REF_OUTBOUND = "OUTBOUND";
    public static final String REF_ARBITRATION = "ARBITRATION";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long recipientUserId;

    private String type;

    private String title;

    private String content;

    /** 跳转引用类型（INBOUND/OUTBOUND/ARBITRATION） */
    private String refType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refId;

    /** 已读时刻（空=未读） */
    private LocalDateTime readAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
