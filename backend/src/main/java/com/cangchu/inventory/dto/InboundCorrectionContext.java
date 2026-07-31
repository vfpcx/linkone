package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * R3 登记纠错库存联动入参（P3b T1-BE，13 §1.3）。
 *
 * <p>delta = new_qty − 当前实登 qty（非 0）：正=改大补录 CORRECTION_IN；负=改小冲销
 * CORRECTION_OUT（按 12 §2.4 封顶）。原 INBOUND 流水由 refDocNo 锁内解析（reverseOutbound 先例），
 * 两类流水 biz_time=原 INBOUND biz_time、reversal_of_id=原 INBOUND 流水 id（D-4，P4 按配对重算）。
 */
@Getter
@Builder
public class InboundCorrectionContext {

    private final Long wholesalerId;
    private final Long tenantId;
    private final Long skuId;

    /** 纠错变动量 = new_qty − 当前 qty（非 0；方向决定 CORRECTION_IN/OUT） */
    private final Integer delta;

    /** 原入库单据号（定位原 INBOUND 流水 + 落流水 ref_doc_no） */
    private final String refDocNo;

    /** 原入库托盘数（inbound_requests.pallet_qty，托盘按比例 ±ceil(pallet×applied/原qty)） */
    private final Integer originalPalletQty;

    /** 操作人（TA 审批人） */
    private final Long operatorUserId;
}
