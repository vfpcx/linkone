package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 清库单视图（P3b T4-W2）。详情链路附批次只读信息（批次号/到效期/推算剩余）与
 * currentStock/suggestedPalletRelease（TA 审批弹窗封顶预览=min(qty, currentStock)，
 * 盘点 CountSheetItemVo 同构契约，FE 无需另拉库存接口）。
 */
@Data
@Builder
public class ClearanceRequestVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 详情链路附带（列表为省 join 不带，盘点先例） */
    private String wholesalerName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 详情链路附带 */
    private String skuName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long batchId;

    /** 批次只读信息（表单/审批弹窗展示） */
    private String batchNo;

    private LocalDate batchExpiryDate;

    /** 批次推算剩余（截至 02:00，UI 标注「推算」） */
    private Integer batchRemainingQty;

    /** 清库件数（现场核数） */
    private Integer qty;

    /** 释放托盘（提交=覆盖值或 null 默认比例；APPROVED 后=实际释放值） */
    private Integer palletRelease;

    private String reason;

    private String reasonRemark;

    private List<String> attachments;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    private LocalDateTime decidedAt;

    private String rejectRemark;

    private String remark;

    /** 详情链路：当刻池在库（审批封顶预览基准） */
    private Integer currentStock;

    /** 详情链路：默认释放托盘建议值（13 §2.4-2 公式；palletRelease 已覆盖时为覆盖值） */
    private Integer suggestedPalletRelease;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
