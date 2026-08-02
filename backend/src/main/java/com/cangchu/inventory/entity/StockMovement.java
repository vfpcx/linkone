package com.cangchu.inventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水（phase-1 B1：仅 INBOUND/OUTBOUND）。
 * 与库存变动在同一事务内写入；tenant_id 自动填充，纳入 TenantLine 隔离白名单。
 */
@Data
@TableName("stock_movements")
public class StockMovement {

    /** 入库流水类型 */
    public static final String TYPE_INBOUND = "INBOUND";
    /** 出库流水类型 */
    public static final String TYPE_OUTBOUND = "OUTBOUND";
    /** 出库回补（+）：R4 撤回 / R8 作废联动（P3，reversal_of_id 指向原 OUTBOUND 流水） */
    public static final String TYPE_OUTBOUND_REVERSAL = "OUTBOUND_REVERSAL";
    /** 异议冲销（−）：WA 异议按剩余在库封顶（P3，biz_time=异议时刻，计费截止异议日 D39） */
    public static final String TYPE_DISPUTE_REVERSAL = "DISPUTE_REVERSAL";
    /** 仲裁恢复（+）：TA 仲裁通过（P3，biz_time=原入库单 created_at，G10） */
    public static final String TYPE_DISPUTE_RESTORE = "DISPUTE_RESTORE";
    /** R3 纠错改大补录（+）：P3b T1（D-4，biz_time=原 INBOUND biz_time、reversal_of_id=原 INBOUND 流水） */
    public static final String TYPE_CORRECTION_IN = "CORRECTION_IN";
    /** R3 纠错改小冲销（−）：P3b T1（D-4 同上；qty=封顶后 applied，售罄 0 冲销不写流水） */
    public static final String TYPE_CORRECTION_OUT = "CORRECTION_OUT";
    /** 退货（−）：P3b T3-W1（D-7 登记时扣；biz_time=登记日，计费当日截止；pallet_delta=−释放托盘） */
    public static final String TYPE_RETURN = "RETURN";
    /**
     * 出库托盘释放（P3b T3-W1，13 §2.4-3）：件数创建即扣不变，托盘在登记出库（COMPLETED）时释放。
     * qty=0 恒定（不进件数公式）、pallet_delta=−n、ref_doc_no=出库单、biz_time=登记时刻。
     */
    public static final String TYPE_PALLET_RELEASE = "PALLET_RELEASE";
    /** 盘盈（+）：P3b T3-W2（审批通过；biz_time=审批通过日，盘盈次日起算视同当日入库；pallet_delta=+M 可选） */
    public static final String TYPE_GAIN = "GAIN";
    /** 盘亏（−）：P3b T3-W2（D-10 封顶：qty=min(|diff|, 审批时刻在库)；biz_time=审批通过日计费当日截止；pallet_delta=−释放） */
    public static final String TYPE_LOSS = "LOSS";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    /** INBOUND / OUTBOUND */
    private String type;

    /** 变动数量（正数；方向由 type 表达） */
    private Integer qty;

    /** 关联单据号（入库/出库单，可空） */
    private String refDocNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;

    /** 计费语义时间锚点（V15，7.6 口径）：默认=created_at；DISPUTE_RESTORE=原入库时间戳（G10） */
    private LocalDateTime bizTime;

    /** 反向流水指向的原流水 id（V15：OUTBOUND_REVERSAL 必填；DISPUTE_RESTORE 回指配对 DISPUTE_REVERSAL） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reversalOfId;

    /** 流水备注（V15：冲销托盘数等审计信息） */
    private String remark;

    /**
     * 托盘变化（V20，D-8=A）：+占用 / −释放；inventories.pallet_qty ≡ Σ pallet_delta（05 §3.4）。
     * V20 前存量流水恒 0（不回填，13 §2.4-4）；DISPUTE_REVERSAL/RESTORE/CORRECTION_* 新流水
     * 与既有 remark 快照（palletReversed/palletAdjusted=N）双写，读侧优先本列。
     */
    private Integer palletDelta;

    /**
     * 批次标识（V22，方案 C 定义，13-p3b §3.1）：仅 INBOUND / EXPIRY_CLEARANCE / CORRECTION_IN/OUT
     * 落值，出库类流水恒 NULL（出库按 SKU 池扣减不感知批次，批次归属由 02:00 FIFO 离线推算）。
     * 落值方式为登记簿后置钩子回填（BatchService），交易路径（addStock 等）零改动。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
