package com.cangchu.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月度账单（P4 W3，14 §3/§4 V26）。
 *
 * <p>6 态生命周期（DocKind.BILL 矩阵，14 §3.1）：DRAFT 待核对 → DISPATCHED 已下发 →
 * PENDING_PAYMENT 待回款 → PARTIAL_PAID 部分回款 → PAID 已结清；DISPUTED 争议中（R14 联动，
 * P4 无出边）。金额恒等式：total = subtotal + adjust ≥ 0；Σ条目 = 三金额（14 §8 不变量）。
 * 幂等键 bill:{t}:{ws}:{yyyyMM}（先查后写 + uk 兜底，月度 Job 重跑/并发恰一单）。
 * tenant_id 由写入方显式设置（Job 无 TenantContext 不能依赖自动填充）。
 */
@Data
@TableName("bills")
public class Bill {

    // ==================== 状态枚举（14 §3.1；界面中文对照见 PRD 13-p4 §3.1） ====================
    /** 待核对（月初生成落点；唯一可调整/冲销态） */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已下发（dispatch_at 落值；0 回款且未确认可 R11 撤回） */
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    /** 待回款（WA 确认 或 下发满 1 日自动，confirmed_at 落值） */
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    /** 部分回款（0 < paid < total） */
    public static final String STATUS_PARTIAL_PAID = "PARTIAL_PAID";
    /** 已结清（paid = total；应收 0 账单生成即此态） */
    public static final String STATUS_PAID = "PAID";
    /** 争议中（R14 联动位；P4 冻结全部写操作 50381，OPS 闭环 P5） */
    public static final String STATUS_DISPUTED = "DISPUTED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 账单号 BL-{租户简码}-W{wholesalerId}-{yyyyMM}（14 §3.4，月粒度无日序列） */
    private String billNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 账期月 yyyy-MM */
    private String billingMonth;

    /** 账单期间起 = max(月初, 首版规则 effective_from)（14 §1.3 起点截断） */
    private LocalDate periodStart;

    /** 账单期间止 = 月末 */
    private LocalDate periodEnd;

    /** 仓储费小计（ΣSTORAGE + Σ其 REVERSAL） */
    private BigDecimal subtotalAmount;

    /** 调整合计（ΣADJUSTMENT + Σ其 REVERSAL，可负） */
    private BigDecimal adjustAmount;

    /** 应收 = subtotal + adjust ≥ 0 */
    private BigDecimal totalAmount;

    /** 累计已收（回款登记累加 / R12 冲销回减） */
    private BigDecimal paidAmount;

    private String status;

    /** 下发时刻（R11 判据 + 满 1 日自动确认锚点 + 申诉 7 天窗口起点） */
    private LocalDateTime dispatchAt;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long dispatchUserId;

    /** WA 确认对账时刻（或 00:50 Job 自动确认落值；R11 判据） */
    private LocalDateTime confirmedAt;

    /** 幂等键 bill:{t}:{ws}:{yyyyMM} */
    private String idempotentKey;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
