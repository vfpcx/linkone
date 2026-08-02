package com.cangchu.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次登记簿（P3b T4-W1，V22；D-11=C：批次登记 + FIFO 离线推算）。
 *
 * <p>方案 C 语义：库存仍按 SKU 池记账（交易路径零改动），本表是「登记簿」——
 * 记录每批入库量与保质期；{@code remaining_qty} 为每日 02:00 离线 FIFO 推算值
 * （{@code BatchService.recalcTenant}），<b>非记账值</b>，UI 须标注「推算」。
 * tenant_id 纳入 TenantLine 隔离白名单。
 */
@Data
@TableName("batches")
public class Batch {

    /** 在库（推算剩余>0 且未临期） */
    public static final String STATUS_IN_STOCK = "IN_STOCK";
    /** 已售罄（推算剩余=0，不再产生预警） */
    public static final String STATUS_SOLD_OUT = "SOLD_OUT";
    /** 临期（到效期−今日 ≤ 阈值，02:00 判定） */
    public static final String STATUS_EXPIRING = "EXPIRING";
    /** 待清理（到效期已过且推算剩余>0，02:30 归零标记，T4-W2） */
    public static final String STATUS_PENDING_CLEARANCE = "PENDING_CLEARANCE";
    /** 已清库（QK 审批通过，终态，T4-W2） */
    public static final String STATUS_CLEARED = "CLEARED";
    /** 已冻结（批次开关 启→关 时非终态批次统一标记；再启用不复活，终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 来源：入库登记 */
    public static final String SOURCE_INBOUND = "INBOUND";
    /** 来源：开关启用生成的默认批次（吸收启用时刻存量在库） */
    public static final String SOURCE_DEFAULT = "DEFAULT";

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

    /** 批次号（uk_bat_ws_sku_no 唯一，冲突 50362；默认批次 DEFAULT-{YYYYMMDD}） */
    private String batchNo;

    /** 生产日期（默认批次可补录） */
    private LocalDate productionDate;

    /** 到效期（NULL=不参与临期/归零扫描；默认批次可补录） */
    private LocalDate expiryDate;

    /** 批次累计入库件数（默认批次=启用时刻池 qty 快照） */
    private Integer initialQty;

    /** FIFO 推算剩余（02:00 覆写；非记账值） */
    private Integer remainingQty;

    private String status;

    /** INBOUND / DEFAULT */
    private String source;

    /** D-12 去重锚点：进入 EXPIRING 首次通知落值（T4-W2） */
    private LocalDateTime expiringNotifiedAt;

    /** WK 一键通知 24h 限 1 比对锚点（50367，T4-W2） */
    private LocalDateTime manualNotifiedAt;

    /** 清库时刻（T4-W2） */
    private LocalDateTime clearedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
