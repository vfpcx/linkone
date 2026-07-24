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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
