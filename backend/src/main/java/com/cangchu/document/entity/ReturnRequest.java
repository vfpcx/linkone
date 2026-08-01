package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退货单（P3b T3-W1，13-p3b §2.1；V20 建表）。
 *
 * <p>库存语义（D-7 拍板：<b>登记时扣</b>）：提交/受理不动库存——退货前货仍可售；
 * WK 现场出货登记（ACCEPTED→COMPLETED）瞬间才 returnStock 扣件数+释放托盘并写 RETURN 流水
 * （biz_time=登记日，计费当日截止锚点，零金额）。登记前撤回本就没扣 → 无回补流水类别。
 * 迁移矩阵见 {@link com.cangchu.document.statemachine.DocStateMachine}（DocKind.RETURN）。
 *
 * <p>权限（D-9）：发起/撤回=WA（<b>WE 不开放</b>，入口对员工隐藏）；受理/登记=WK。
 */
@Data
@TableName("return_requests")
public class ReturnRequest {

    /** 待受理（WA 提交，零库存零流水零计费） */
    public static final String STATUS_PENDING_ACCEPT = "PENDING_ACCEPT";
    /** 已撤回（终态；仅待受理可撤，reason 必填） */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    /** 已受理（WK 锁单；仍未扣库存） */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** 已退货（终态；登记瞬间已扣件数+释放托盘） */
    public static final String STATUS_COMPLETED = "COMPLETED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** RTN- 前缀单据号（DocType.RETURN，A3 零改造启用） */
    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 退货件数（提交落值；登记可按实覆写，remark 留痕） */
    private Integer qty;

    /** 登记时实际释放托盘（默认比例建议值 13 §2.4-2，WK 可覆盖，双重封顶不打负） */
    private Integer palletRelease;

    private String status;

    /** 撤回理由（R1 同构，撤回必填） */
    private String withdrawReason;

    /** 发起人（WA；D-9 不开放 WE） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long waUserId;

    /** 受理·登记人（WK） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    private LocalDateTime acceptedAt;

    /** 登记出货时刻（=RETURN 流水 biz_time，计费当日截止锚点） */
    private LocalDateTime completedAt;

    /** 备注（提交备注；登记按实覆写差异留痕） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
