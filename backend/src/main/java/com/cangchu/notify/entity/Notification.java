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
    /** R4 撤回申请（BE-W2：已打印单 WA 申请撤回 → 通知 WK 二次确认） */
    public static final String TYPE_OUTBOUND_WITHDRAW_REQUESTED = "OUTBOUND_WITHDRAW_REQUESTED";
    /** 代建出库（BE-W2 启用） */
    public static final String TYPE_OUTBOUND_PROXY_CREATED = "OUTBOUND_PROXY_CREATED";
    /** 30 天客诉（BE-W2 启用） */
    public static final String TYPE_COMPLAINT_CREATED = "COMPLAINT_CREATED";
    /** R4 撤回/撤销生效（BE-W2：直撤或 WK 确认撤回，已回补流水） */
    public static final String TYPE_OUTBOUND_WITHDRAWN = "OUTBOUND_WITHDRAWN";
    /** R4 撤回被 WK 拒绝（BE-W2：flag 清除，单据继续履约） */
    public static final String TYPE_OUTBOUND_WITHDRAW_REJECTED = "OUTBOUND_WITHDRAW_REJECTED";
    /** R8 意向单作废联动（BE-W2：整单出库撤销+回补） */
    public static final String TYPE_INQUIRY_VOIDED = "INQUIRY_VOIDED";
    /** P3b T1：入库申请提交 → 通知库管（13 §1.4） */
    public static final String TYPE_INBOUND_SUBMITTED = "INBOUND_SUBMITTED";
    /** P3b T1：申请受理 → 通知商户 */
    public static final String TYPE_INBOUND_ACCEPTED = "INBOUND_ACCEPTED";
    /** P3b T1：申请驳回（R2）→ 通知商户（可一键复制重建） */
    public static final String TYPE_INBOUND_REJECTED = "INBOUND_REJECTED";
    /** P3b T1：登记完成（直落已入库）→ 通知商户 */
    public static final String TYPE_INBOUND_REGISTERED = "INBOUND_REGISTERED";
    /** P3b T1：R3 纠错待审 → 通知租户管理员（审批中心） */
    public static final String TYPE_CORRECTION_PENDING = "CORRECTION_PENDING";
    /** P3b T1：R3 纠错结论 → 通知发起库管 */
    public static final String TYPE_CORRECTION_DECIDED = "CORRECTION_DECIDED";
    /** P3b T3-W1：退货申请发起 → 通知库管受理（13 §2.1/§5.3） */
    public static final String TYPE_RETURN_CREATED = "RETURN_CREATED";
    /** P3b T3-W1：退货登记完成 → 通知商户（含实退件数、释放托盘） */
    public static final String TYPE_RETURN_COMPLETED = "RETURN_COMPLETED";

    // 跳转引用类型
    public static final String REF_INBOUND = "INBOUND";
    /** P3b T3-W1：退货单跳转 */
    public static final String REF_RETURN = "RETURN";
    public static final String REF_OUTBOUND = "OUTBOUND";
    public static final String REF_ARBITRATION = "ARBITRATION";
    /** R8 意向单作废通知跳转（BE-W2） */
    public static final String REF_INQUIRY = "INQUIRY";

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
