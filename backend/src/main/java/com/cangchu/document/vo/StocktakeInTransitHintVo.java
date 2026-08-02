package com.cangchu.document.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 盘点在途提示条数据（P3b T3-W2，13 §2.2 护栏 / PRD 11 §2.2-2）：
 * 「当前存在 N 张已确认未出库单据（合计 M 件）+ 已受理未登记退货单 X 张（Y 件）」。
 * 出库在途=该商户 PENDING_ACCEPT/PRINTED 出库单（件数已扣账、货仍在仓——实物&gt;账面正常）；
 * 退货在途=ACCEPTED 退货单（D-7 登记时扣——账面含、实物即将出）。
 * skuXxxQty 按 SKU 聚合，供差异列旁「其中 ≤M 件可能为在途出库占用」标注。
 */
@Data
@Builder
public class StocktakeInTransitHintVo {

    /** 已确认未出库单据张数（PENDING_ACCEPT/PRINTED） */
    private long outboundDocCount;

    /** 已确认未出库合计件数 */
    private long outboundQtyTotal;

    /** 已受理未登记退货单张数（ACCEPTED） */
    private long returnDocCount;

    /** 已受理未登记退货合计件数 */
    private long returnQtyTotal;

    /** 按 SKU 聚合的在途出库件数（key=skuId 字符串化防 JS 精度丢失） */
    private Map<String, Integer> skuOutboundQty;

    /** 按 SKU 聚合的在途退货件数 */
    private Map<String, Integer> skuReturnQty;
}
