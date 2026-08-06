package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 账单详情 VO（P4 W3，14 §6.2）：三金额+状态+items 全量+payments+disputes。
 * 下钻（按日/按 SKU）另走 /st/bills/{id}/daily-breakdown 与 /st/snapshots/**（W2 备注 2）。
 */
@Data
@Builder
public class BillDetailVo {

    private BillVo bill;

    private List<BillItemVo> items;

    private List<PaymentRecordVo> payments;

    private List<BillDisputeVo> disputes;
}
