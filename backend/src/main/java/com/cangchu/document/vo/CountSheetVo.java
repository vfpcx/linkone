package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 盘点单 VO（P3b T3-W2，13 §2.2/§5.2）。items/inTransitHint 详情链路填充（列表为 null）。 */
@Data
@Builder
public class CountSheetVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 商户名（详情链路填充） */
    private String wholesalerName;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    private LocalDateTime decidedAt;

    private String rejectRemark;

    private String remark;

    private List<String> attachments;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 明细（详情链路；含 currentStock/suggestedPalletRelease 封顶预览数据） */
    private List<CountSheetItemVo> items;

    /** 在途提示条（详情链路；审批完成后仍返回当刻值供追溯页展示） */
    private StocktakeInTransitHintVo inTransitHint;
}
