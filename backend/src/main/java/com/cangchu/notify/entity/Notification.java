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
    /** P3b 收口 L-2：商户撤回入库申请（R1 仅 SUBMITTED 档）→ 通知库管免白跑备货 */
    public static final String TYPE_INBOUND_WITHDRAWN = "INBOUND_WITHDRAWN";
    /** P3b T1：R3 纠错待审 → 通知租户管理员（审批中心） */
    public static final String TYPE_CORRECTION_PENDING = "CORRECTION_PENDING";
    /** P3b T1：R3 纠错结论 → 通知发起库管 */
    public static final String TYPE_CORRECTION_DECIDED = "CORRECTION_DECIDED";
    /** P3b T3-W1：退货申请发起 → 通知库管受理（13 §2.1/§5.3） */
    public static final String TYPE_RETURN_CREATED = "RETURN_CREATED";
    /** P3b T3-W1：退货登记完成 → 通知商户（含实退件数、释放托盘） */
    public static final String TYPE_RETURN_COMPLETED = "RETURN_COMPLETED";
    /** P3b 收口 L-2：商户撤回退货申请（待受理档，D-7 登记前未扣库存）→ 通知库管免白跑 */
    public static final String TYPE_RETURN_WITHDRAWN = "RETURN_WITHDRAWN";
    /** P3b T3-W2：盘点提交待审 → 通知租户管理员（审批中心角标先例） */
    public static final String TYPE_STOCKTAKE_PENDING = "STOCKTAKE_PENDING";
    /** P3b T3-W2：盘点审批结论（通过含封顶差额/驳回）→ 通知发起库管；封顶差额另发 TA（D-10） */
    public static final String TYPE_STOCKTAKE_DECIDED = "STOCKTAKE_DECIDED";
    /** P3b T4-W2：批次临期（02:00 Job 首发 D-12 去重 / WK 手动一键 24h 限 1）→ WK+WA（13 §3.3） */
    public static final String TYPE_BATCH_EXPIRING = "BATCH_EXPIRING";
    /** P3b T4-W2：批次到期归零标记「待清理」（02:30 Job）→ 通知库管发起清库 */
    public static final String TYPE_BATCH_EXPIRED = "BATCH_EXPIRED";
    /** P3b T4-W2：清库单提交待审 → 通知租户管理员（审批中心角标先例） */
    public static final String TYPE_CLEARANCE_PENDING = "CLEARANCE_PENDING";
    /** P3b T4-W2：清库审批结论（通过含照片凭证→商户；结论→发起库管） */
    public static final String TYPE_CLEARANCE_DECIDED = "CLEARANCE_DECIDED";
    /** P4 W1：计费规则保存/变更（R20）→ 全部在驻批发商管理员（14 §2.1-4，type 零 DDL 先例） */
    public static final String TYPE_BILLING_RULE_CHANGED = "BILLING_RULE_CHANGED";
    /** P4 W3：月账单生成 → 本仓库全部结算员（14 §3.6；应收 0 直落已结清的也发——生成可追溯） */
    public static final String TYPE_BILL_GENERATED = "BILL_GENERATED";
    /** P4 W3：账单下发 → 批发商管理员（站内信真发+短信 mock） */
    public static final String TYPE_BILL_DISPATCHED = "BILL_DISPATCHED";
    /** P4 W3：R11 账单撤回 → 批发商管理员 */
    public static final String TYPE_BILL_WITHDRAWN = "BILL_WITHDRAWN";
    /** P4 W3：回款登记 → 批发商管理员（含金额与累计进度） */
    public static final String TYPE_PAYMENT_REGISTERED = "PAYMENT_REGISTERED";
    /** P4 W3：R12 回款冲销 → 批发商管理员（含原因） */
    public static final String TYPE_PAYMENT_REVERSED = "PAYMENT_REVERSED";
    /** P4 W3：WA 申诉提交 → 本仓库全部结算员 */
    public static final String TYPE_BILL_DISPUTE_SUBMITTED = "BILL_DISPUTE_SUBMITTED";
    /** P4 W3：申诉处理结果 → 批发商管理员 */
    public static final String TYPE_BILL_DISPUTE_RESOLVED = "BILL_DISPUTE_RESOLVED";
    /** P4 W3：R14 强制下架未结账单标争议中 → 本仓库全部结算员 */
    public static final String TYPE_BILL_DISPUTED_MARKED = "BILL_DISPUTED_MARKED";
    /** P5-A W3：平台公告（18-p5-design §2.2/§4.4）→ 目标角色（通知中心「公告」分组常驻 + 登录弹窗 1 次） */
    public static final String TYPE_PLATFORM_ANNOUNCEMENT = "PLATFORM_ANNOUNCEMENT";
    /** P5-D C3：客户跟进提醒（24-p5-c-c3 §5，FollowupReminderJob 到点 → 创建人 WE，无跳转） */
    public static final String TYPE_CUSTOMER_FOLLOWUP = "CUSTOMER_FOLLOWUP";

    // 跳转引用类型
    public static final String REF_INBOUND = "INBOUND";
    /** P3b T3-W1：退货单跳转 */
    public static final String REF_RETURN = "RETURN";
    /** P3b T3-W2：盘点单跳转 */
    public static final String REF_STOCKTAKE = "STOCKTAKE";
    public static final String REF_OUTBOUND = "OUTBOUND";
    public static final String REF_ARBITRATION = "ARBITRATION";
    /** P5-A W3：平台公告跳转（消息中心公告分组；refId=公告 id，无详情落地页） */
    public static final String REF_ANNOUNCEMENT = "ANNOUNCEMENT";
    /** R8 意向单作废通知跳转（BE-W2） */
    public static final String REF_INQUIRY = "INQUIRY";
    /** P3b T4-W2：批次下钻跳转（临期/到期通知） */
    public static final String REF_BATCH = "BATCH";
    /** P3b T4-W2：清库单跳转 */
    public static final String REF_CLEARANCE = "CLEARANCE";
    /** P4 W3：账单跳转 */
    public static final String REF_BILL = "BILL";
    /** P4 W3：回款记录跳转（落账单详情回款区） */
    public static final String REF_PAYMENT = "PAYMENT";
    /** P4 W3：账单申诉跳转 */
    public static final String REF_BILL_DISPUTE = "BILL_DISPUTE";

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
