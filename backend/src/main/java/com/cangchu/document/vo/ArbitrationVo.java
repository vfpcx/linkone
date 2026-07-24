package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 仲裁单出参（P3 BE-W1：TA 审批中心列表/裁决回显）。 */
@Data
@Builder
public class ArbitrationVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String docNo;

    private String bizType;

    private String refDocType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long refDocId;

    private String refDocNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 涉事商户名（列表展示） */
    private String wholesalerName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long initiatorUserId;

    private String initiatorRole;

    private String reason;

    /** 附件 URL 列表（落库 JSON 的解码视图） */
    private List<String> attachments;

    private Integer reversedQty;

    private Integer shortfallQty;

    private String status;

    private String conclusion;

    private String liability;

    private String conclusionRemark;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long arbitratorUserId;

    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;
}
