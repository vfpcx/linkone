package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点单（P3b T3-W2，13-p3b §2.2；V21 建表）。
 *
 * <p>口径（PRD 11 §2.2）：系统数=账面库存（已扣后）——实物 &gt; 账面是正常现象不是盘盈；
 * 差异=实物−账面（提交时刻快照定格，不做在途还原折算）；生效量以<b>审批时刻</b>在库封顶
 * （D-10，两时点语义分离）。审批通过逐 SKU 锁内 GAIN/LOSS 流水（biz_time=审批通过日——
 * 盘亏当日截止、盘盈次日起算视同当日入库，零金额锚点）。
 *
 * <p>同商户在途（DRAFT/PENDING_APPROVAL）至多一张（pending_flag 部分唯一
 * uk_cs_ws_pending，V13 先例；违者 50356，防双重盈亏）。
 * 迁移矩阵见 {@link com.cangchu.document.statemachine.DocStateMachine}（DocKind.STOCKTAKE）。
 *
 * <p>权限：建/编/提/删=WK；审批=TA（全量审批无自动通过阈值，D23）。
 */
@Data
@TableName("count_sheets")
public class CountSheet {

    /** 草稿（可编辑/删除；零库存零流水） */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 待审批（提交时明细 system_qty 快照定格；零库存零流水） */
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    /** 已驳回（保留记录不动账；可改回 DRAFT 重提） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已通过（终态不可逆；GAIN/LOSS 流水已生成） */
    public static final String STATUS_APPROVED = "APPROVED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** PD- 前缀单据号（DocType.STOCKTAKE，A3 零改造启用） */
    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** 被盘商户（一张盘点单盘一个商户） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String status;

    /**
     * 在途唯一位：DRAFT/PENDING_APPROVAL=1、REJECTED/APPROVED=NULL
     * （uk_cs_ws_pending 部分唯一；REJECTED→DRAFT 重提时回置 1，撞唯一 → 50356）。
     */
    private Integer pendingFlag;

    /** 发起·编辑·提交人（WK，T3-9 留痕） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 审批人（TA） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    /** 审批时刻（=GAIN/LOSS 流水 biz_time 锚点） */
    private LocalDateTime decidedAt;

    /** 驳回理由（REJECTED 必填） */
    private String rejectRemark;

    /** 盘点说明；审批封顶差额汇总自动追加 */
    private String remark;

    /** 现场照片 ≤5（JSON 数组，AttachmentUrls 编解码，N2 白名单） */
    private String attachments;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
