package com.cangchu.document.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

/**
 * WA 异议结果出参（P3 BE-W1，12 §2.3）：冲销明细 + 仲裁单引用（前端弹窗回显
 * 「登记 N / 已冲销 M / 差额 N−M」与 YY- 单号）。
 */
@Data
@Builder
public class InboundDisputeResultVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inboundId;

    /** 入库单号（WK-） */
    private String docNo;

    /** 入库单新状态（DISPUTED） */
    private String status;

    /** 登记件数 Q */
    private Integer registeredQty;

    /** 实际冲销件数（按在库封顶） */
    private Integer reversedQty;

    /** 已售差额（进入定责） */
    private Integer shortfallQty;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long arbitrationId;

    /** 仲裁单号（YY-） */
    private String arbitrationDocNo;
}
