package com.cangchu.document.entity;

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
 * R3 登记纠错单（P3b T1-BE，13 §1.3；V19 建表）。
 *
 * <p>适用：source=WA_SUBMIT ∧ status=CONFIRMED 的正向链入库单；发起=WK（登记后 ≤24h，
 * SQL 内比数据库时间），审批=TA 单级。不入 DocType 单号体系（内部审批件，以 id 引用，
 * 与 wholesaler_applications 同类先例）。pending_flag 部分唯一 uk_corr_req_pending
 * （V13 先例）保证同单至多一张在途纠错（50353）。纳入 TenantLine 白名单。
 */
@Data
@TableName("inbound_corrections")
public class InboundCorrection {

    /** 待 TA 审批 */
    public static final String STATUS_PENDING = "PENDING";
    /** 审批通过（库存联动已执行：CORRECTION_IN/OUT 流水） */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 审批驳回（decide_remark 必填，零库存影响） */
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 被纠错入库单 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long inboundRequestId;

    /** 冗余入库单号（列表展示免 join，创建时快照） */
    private String refDocNo;

    /** 纠错前实登件数（发起时快照留痕） */
    private Integer oldQty;

    /** 纠错后件数（≥0，≠old_qty） */
    private Integer newQty;

    /** 纠错理由（WK 发起必填） */
    private String reason;

    private String status;

    /** PENDING=1 / 终态 NULL（uk_corr_req_pending 部分唯一，50353 兜底） */
    private Integer pendingFlag;

    /** APPROVED 实际生效变动量（改小按 12 §2.4 封顶后；改大=|delta|） */
    private Integer appliedQty;

    /** 改小遇已售差额 = |delta| − applied（线下定责，禁止打负） */
    private Integer shortfallQty;

    /** 差额备注等（封顶差额自动写入） */
    private String remark;

    /** TA 结论备注（REJECTED 必填） */
    private String decideRemark;

    /** 发起人（WK） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 审批人（TA） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    /** 审批时刻 */
    private LocalDateTime decidedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
