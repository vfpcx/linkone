package com.cangchu.document.dto;

import lombok.Data;

import java.util.List;

/**
 * WK 编辑清库单（P3b T4-W2）：DRAFT 直接改 / REJECTED 改回 DRAFT 重提（与盘点同构）。
 * batchId 不可变（一单一批次）；校验口径同建单。
 */
@Data
public class ClearanceUpdateDto {

    /** 清库件数（现场核数；null=默认批次推算剩余） */
    private Integer qty;

    /** EXPIRED / DAMAGED / OTHER（OTHER 时 reasonRemark 必填） */
    private String reason;

    private String reasonRemark;

    /** 释放托盘覆盖值（null=默认比例；含 0） */
    private Integer palletRelease;

    /** 实物照片 URL ≥1 ≤3 */
    private List<String> attachments;

    private String remark;
}
