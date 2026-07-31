package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * R3 登记纠错单出参（P3b T1-BE）。
 */
@Data
@Builder
public class InboundCorrectionVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inboundRequestId;

    /** 冗余入库单号（列表展示免 join） */
    private String refDocNo;

    private Integer oldQty;

    private Integer newQty;

    private String reason;

    private String status;

    /** APPROVED 实际生效变动量（改小封顶后） */
    private Integer appliedQty;

    /** 改小遇已售差额（线下定责） */
    private Integer shortfallQty;

    private String remark;

    private String decideRemark;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wkUserId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taUserId;

    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;
}
