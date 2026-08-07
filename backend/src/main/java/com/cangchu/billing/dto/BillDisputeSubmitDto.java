package com.cangchu.billing.dto;

import lombok.Data;

import java.util.List;

/**
 * WA 账单申诉入参（P4 W3，14 §3.3 US-WA-08；下发后 7 天窗口 50378，申诉不冻结账单）
 */
@Data
public class BillDisputeSubmitDto {

    /** 申诉理由（必填） */
    private String reason;

    /** 争议条目 id（可选；须属本账单 50377；字符串 id 防 JS 精度） */
    private List<String> disputedItemIds;

    /** 附图 ≤5（/files 白名单 50340） */
    private List<String> attachments;
}
