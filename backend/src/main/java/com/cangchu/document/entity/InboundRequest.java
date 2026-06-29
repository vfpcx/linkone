package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库单（phase-1 C1：WK 代建登记，登记即 REGISTERED）。
 * tenant_id 由 MetaObjectHandler 自动填充；纳入 TenantLine 隔离白名单。
 * phase-1 不做 72h 异议/仲裁，status 仅 REGISTERED。
 */
@Data
@TableName("inbound_requests")
public class InboundRequest {

    /** 登记态：WK 直接登记入库即为该状态 */
    public static final String STATUS_REGISTERED = "REGISTERED";

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
