package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库单（P3 BE-W1 改造，12 §2：代建登记产 PENDING_WA_CONFIRM + 72h 确认链）。
 * tenant_id 由 MetaObjectHandler 自动填充；纳入 TenantLine 隔离白名单。
 * 迁移矩阵：PENDING_WA_CONFIRM→CONFIRMED|DISPUTED；DISPUTED→CONFIRMED|REVOKED；其余不可达。
 */
@Data
@TableName("inbound_requests")
public class InboundRequest {

    /**
     * P1 登记态（已废弃）：V16 已将存量一次性回填为 CONFIRMED（P1 语义=登记即认）。
     * 仅供历史语义参照，新代码勿再写入。
     */
    @Deprecated
    public static final String STATUS_REGISTERED = "REGISTERED";

    /** 待 WA 确认（登记即生效：库存已加、计费已起算、可售——拍板一 B 不冻结） */
    public static final String STATUS_PENDING_WA_CONFIRM = "PENDING_WA_CONFIRM";
    /** 已确认（WA 接受 / 72h 自动 auto_accepted=1 / 仲裁通过） */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 争议中（WA 异议，冲销已执行，等 TA 仲裁） */
    public static final String STATUS_DISPUTED = "DISPUTED";
    /** 已撤销（仲裁驳回，冲销保留，货线下处理） */
    public static final String STATUS_REVOKED = "REVOKED";

    // ==================== P3b T1 正向申请链 4 值（13 §1.1，命名 12 §2.1 冻结） ====================
    /** 待受理（WA/WE 提交，source=WA_SUBMIT；零库存/零流水/零计费） */
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    /** 已撤回（R1，仅 SUBMITTED 可撤，withdraw_reason 必填；终态） */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    /** 已驳回（R2，reason 单选+remark 必填+附件可选；终态，WA 一键复制重建） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已受理（WK 锁单防撤回；仍零库存，登记时才 addStock） */
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    /** 来源：WK 代建（P1/本波唯一来源） */
    public static final String SOURCE_WK_CREATED = "WK_CREATED";
    /** 来源：WA 自主申请（R1-R3 波启用） */
    public static final String SOURCE_WA_SUBMIT = "WA_SUBMIT";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 单据号（DocumentNumberService 生成，doc_no 唯一索引兜底） */
    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 入库数量（>0） */
    private Integer qty;

    /** 本次托盘数（默认 0） */
    private Integer palletQty;

    private String status;

    /** 登记操作人（WK）user id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 来源（V16）：WK_CREATED / WA_SUBMIT */
    private String source;

    /** 代建 72h 确认截止（V16，登记时=created_at+72h 显式落列，Job 用数据库时间比较） */
    private LocalDateTime waConfirmDeadline;

    /** WA 确认时刻（手动/自动/仲裁通过） */
    private LocalDateTime waConfirmAt;

    /** 1=72h 超时自动确认 */
    private Integer autoAccepted;

    /** WA 异议时刻 */
    private LocalDateTime disputedAt;

    // ==================== P3b T1 正向申请链列（V19） ====================

    /** 申请件数（正向链提交落值后不可变；代建链 NULL。qty=实登件数，登记时覆写） */
    private Integer requestedQty;

    /** R2 驳回原因单选：QTY/QUALITY/BATCH/OTHER */
    private String rejectReason;

    /** R2 驳回备注（驳回必填） */
    private String rejectRemark;

    /** R2 驳回举证附件（JSON 数组 URL ≤5，独立于登记照片） */
    private String rejectAttachments;

    /** R1 撤回理由（撤回必填） */
    private String withdrawReason;

    /** 登记照片（JSON 数组 URL ≤5，D-2 最小版复用 /files） */
    private String attachments;

    /** 最近打印时刻（T1-7，非状态节点） */
    private LocalDateTime printedAt;

    /** 打印次数（补打 count++） */
    private Integer printCount;

    /** 登记时刻（R3 纠错 24h 窗口锚点，SQL 内比数据库时间） */
    private LocalDateTime registeredAt;

    /** 同批提交共享雪花 id（D-5 多行拆单打印聚合） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchSubmitId;

    /** 提交人（WA 或被授权 WE） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long waUserId;

    /** 备注（提交行备注；登记差异非 0 时必填的差异备注覆写此列） */
    private String remark;

    // ==================== P3b T4-W1 批次三字段（V22，批次开关启用时提交/登记必填） ====================

    /** 批次号（≤64；(商户,SKU,批次号) 唯一，冲突 50362；登记时写入登记簿） */
    private String batchNo;

    /** 生产日期（≤今天，40205） */
    private java.time.LocalDate productionDate;

    /** 到效期（>生产日期，40206；≤今天登记需二次确认 50364，临期仅警告放行） */
    private java.time.LocalDate expiryDate;

    /** 货位号（V40，C2 25-p5-c-c2 §3.1：登记时落单留痕；货位开关启用时必填 50822） */
    private String location;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
