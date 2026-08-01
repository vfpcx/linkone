package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退货单 VO（P3b T3-W1）。
 * currentStock / suggestedPalletRelease 仅在受理·登记链路填充（WK 登记页
 * 「当前在库 N 件 / 默认释放托盘」提示），列表侧可空。
 */
@Data
@Builder
public class ReturnRequestVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private Integer qty;

    private Integer palletRelease;

    private String status;

    private String withdrawReason;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long waUserId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    private LocalDateTime acceptedAt;

    private LocalDateTime completedAt;

    private String remark;

    private LocalDateTime createdAt;

    /** 当前在库件数（登记页提示；不足时前端红条文案用） */
    private Integer currentStock;

    /** 默认释放托盘建议值（13 §2.4-2 公式；WK 可覆盖含 0） */
    private Integer suggestedPalletRelease;
}
