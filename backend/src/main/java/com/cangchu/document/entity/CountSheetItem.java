package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点单明细（P3b T3-W2，13-p3b §2.2；一单多 SKU——盘点天然全仓性质，
 * 与单据链一单一 SKU 不同域，不构成先例冲突）。
 *
 * <p>两时点语义（13 §7.3）：{@code system_qty/diff} 以<b>提交时刻</b>快照为准（WK 所见即所盘，
 * 提交后出库不改差异值）；{@code applied_diff} 以<b>审批时刻</b>锁内重读 onhand 封顶（D-10/G9）。
 */
@Data
@TableName("count_sheet_items")
public class CountSheetItem {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sheetId;

    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 账面快照（建单预填、提交时刻定格——已扣后口径，10 §2.2） */
    private Integer systemQty;

    /** 实物数 ≥0（<0 → 50355） */
    private Integer actualQty;

    /** actual − system（正=盘盈/负=盘亏；系统不做在途还原折算） */
    private Integer diff;

    /** 审批通过实际生效值（带符号：盘盈=diff、盘亏=−applied 封顶后；驳回恒 NULL） */
    private Integer appliedDiff;

    /**
     * 托盘（13 §2.2 蓝图 NOT NULL DEFAULT 0 的 NULL 化偏差，V21 注释）：
     * 提交前=WK 输入（盘盈占用 +M / 盘亏释放数 ≥0；NULL=盘亏默认比例建议值）；
     * 审批通过后=回写生效带符号值（盘盈 +M / 盘亏 −实际释放，RTN pallet_release 回写先例）。
     */
    private Integer palletDelta;

    /** 差异理由；盘亏封顶差额自动追加（「盘亏 X 件，审批时在库仅 Y 件…」） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
