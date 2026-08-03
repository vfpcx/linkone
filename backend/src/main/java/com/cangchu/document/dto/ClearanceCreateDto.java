package com.cangchu.document.dto;

import lombok.Data;

import java.util.List;

/**
 * WK 建清库单草稿（P3b T4-W2，13 §5.3）。
 * 一单一批次；wholesalerId/skuId 随批次推导（S4 不取客户端）。
 * qty=现场核数（可空默认=批次推算剩余；≤ 该 SKU 池当前在库）；
 * attachments 实物照片必填 ≥1 ≤3（50366，R19 刚性）。
 */
@Data
public class ClearanceCreateDto {

    private Long batchId;

    /** 清库件数（现场核数；null=默认批次推算剩余） */
    private Integer qty;

    /** EXPIRED / DAMAGED / OTHER（OTHER 时 reasonRemark 必填） */
    private String reason;

    private String reasonRemark;

    /** 释放托盘覆盖值（null=默认比例；含 0） */
    private Integer palletRelease;

    /** 实物照片 URL ≥1 ≤3（复用 /files，N2 白名单） */
    private List<String> attachments;

    private String remark;
}
