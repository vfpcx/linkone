package com.cangchu.billing.entity;

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
 * 账单申诉（P4 W3，14 §3.3/§4 V26；US-WA-08）。
 *
 * <p>窗口=下发后 7 天（50378）；同账单至多一张 PENDING（pending_flag 部分唯一 uk_bd_bill_pending，
 * V13 先例，50382）；<b>申诉不冻结账单</b>（ST 决定是否 R11 撤回调整）。
 * 与出库客诉边界（D43）：BillDispute 与 arbitrations 两套实体互不落表。
 */
@Data
@TableName("bill_disputes")
public class BillDispute {

    public static final String STATUS_PENDING = "PENDING";
    /** 申诉成立 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 申诉不成立 */
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long billId;

    /** WA 提交人 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submitUserId;

    /** 申诉理由（必填） */
    private String reason;

    /** 争议条目 id JSON 数组（字符串 id 防 JS 精度） */
    private String disputedItemIds;

    /** 附图 ≤5（/files 白名单 50340，JSON 数组文本） */
    private String attachments;

    /** PENDING/RESOLVED/REJECTED */
    private String status;

    /** PENDING=1/终态 NULL（uk_bd_bill_pending 部分唯一，50382 兜底） */
    private Integer pendingFlag;

    /** ST 处理说明（必填留痕） */
    private String resolution;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long resolverUserId;

    private LocalDateTime resolvedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
