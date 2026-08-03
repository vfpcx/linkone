package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 强制清库单（P3b T4-W2，13-p3b §3.4；V23 建表，D-6 清库并入 T4）。
 *
 * <p>口径（PRD 11 §3.5）：一单一批次；前置=批次「待清理」（PENDING_CLEARANCE）且推算剩余&gt;0
 * （违者 50365）；清库件数默认=推算剩余，WK <b>现场核数可改</b>（推算 ≤1 日误差的人工收口）；
 * 实物照片必填 ≥1 ≤3（50366，R19 刚性不受拍照开关影响）。审批通过（TA）按 SKU 池剩余在库封顶
 * （封顶口径家族第 4 处）：写 EXPIRY_CLEARANCE 流水（qty=applied、batch_id 落值、
 * biz_time=清库日仓储费当日截止、不计正常出库统计）、批次推算剩余清零转 CLEARED。
 *
 * <p>同批次在途（DRAFT/PENDING_APPROVAL）至多一张（pending_flag 部分唯一
 * uk_qk_batch_pending，V13/V21 先例；违者 50365）。
 * 迁移矩阵见 {@link com.cangchu.document.statemachine.DocStateMachine}（DocKind.CLEARANCE，与盘点同构）。
 *
 * <p>权限：建/编/提/删=WK；审批=TA。R14 有意不接（存量库存治理，商户下架/退驻中仍可清库——
 * 13 §3.6，防「无法清库→永远退不了驻」死锁）。
 */
@Data
@TableName("clearance_requests")
public class ClearanceRequest {

    /** 草稿（可编辑/删除；零库存零流水） */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 待审批（零库存零流水） */
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    /** 已驳回（保留记录不动账；可改回 DRAFT 重提） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已通过（终态不可逆；EXPIRY_CLEARANCE 流水已生成、批次已 CLEARED） */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 清库原因：过期 */
    public static final String REASON_EXPIRED = "EXPIRED";
    /** 清库原因：损坏 */
    public static final String REASON_DAMAGED = "DAMAGED";
    /** 清库原因：其他（reason_remark 必填） */
    public static final String REASON_OTHER = "OTHER";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** QK- 前缀单据号（DocType.CLEARANCE，A3 零改造启用） */
    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** 归属商户（随批次推导，不取客户端） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 待清理批次（一单一批次） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    /** 清库件数（现场核数；审批时按池剩余在库封顶） */
    private Integer qty;

    /** WK 释放托盘覆盖值（NULL=默认比例；含 0）；审批通过后回写实际释放值 */
    private Integer palletRelease;

    /** EXPIRED / DAMAGED / OTHER */
    private String reason;

    /** 原因补充（reason=OTHER 必填） */
    private String reasonRemark;

    /** 实物照片 ≥1 ≤3（JSON 数组，AttachmentUrls 编解码，N2 白名单；50366 刚性） */
    private String attachments;

    private String status;

    /**
     * 在途唯一位：DRAFT/PENDING_APPROVAL=1、REJECTED/APPROVED=NULL
     * （uk_qk_batch_pending 部分唯一；REJECTED→DRAFT 重提时回置 1，撞唯一 → 50365）。
     */
    private Integer pendingFlag;

    /** 发起·编辑·提交人（WK） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    /** 审批人（TA） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    /** 审批时刻（=EXPIRY_CLEARANCE 流水 biz_time 锚点，仓储费当日截止） */
    private LocalDateTime decidedAt;

    /** 驳回理由（REJECTED 必填） */
    private String rejectRemark;

    /** 备注选填；审批封顶差额自动追加 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
