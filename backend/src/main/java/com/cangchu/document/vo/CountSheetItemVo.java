package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

/**
 * 盘点明细行 VO（P3b T3-W2）。
 * 两时点语义：systemQty/diff=提交时刻快照（草稿期为建单/编辑时预填值）；
 * appliedDiff=审批通过实际生效值（盘亏 D-10 封顶后带符号）。
 * currentStock/suggestedPalletRelease=当刻只读快照（审批弹窗封顶实时预览、盘亏托盘默认值提示），
 * 详情链路填充，与 RTN currentStock 契约先例同构。
 */
@Data
@Builder
public class CountSheetItemVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** SKU 名称（详情链路填充，前端免二次拉取） */
    private String skuName;

    private Integer systemQty;

    private Integer actualQty;

    private Integer diff;

    private Integer appliedDiff;

    private Integer palletDelta;

    /** 盘盈按批登记：批次号（未按批=null；审批通过后随明细留存） */
    private String gainBatchNo;

    /** 盘盈按批登记：生产日期（可选） */
    private java.time.LocalDate gainProductionDate;

    /** 盘盈按批登记：到效期（给批次号则必有） */
    private java.time.LocalDate gainExpiryDate;

    private String remark;

    /** 当前在库（详情链路只读快照——审批弹窗封顶预览：min(|盘亏|, currentStock)） */
    private Integer currentStock;

    /** 盘亏默认释放托盘建议值（13 §2.4-2 比例公式；盘盈/无差异行为 null） */
    private Integer suggestedPalletRelease;
}
