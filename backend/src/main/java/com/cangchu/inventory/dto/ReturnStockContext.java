package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 退货登记上下文（P3b T3-W1，13 §2.1：D-7 登记时扣——由 document 的退货登记调用）。
 *
 * <p>契约：{@code returnStock} 在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行：
 * 校验在库足够（不足抛 STOCK_NOT_ENOUGH，不写流水，单据事务整体回滚保持 ACCEPTED）
 * → 扣件数 + 释放托盘（默认比例建议值 / WK 覆盖，双重封顶不打负）→ 写 RETURN 流水
 * （biz_time=登记日，pallet_delta=−释放数）。
 *
 * <ul>
 *   <li>wholesalerId / tenantId / skuId 必填（tenantId 由单据带入）。</li>
 *   <li>qty                必填，实退件数（>0）。</li>
 *   <li>palletReleaseOverride 可空：null=按 13 §2.4-2 默认建议值；非空=WK 覆盖（含 0），
 *       落库前 min(·, 在库托盘) 封顶。</li>
 *   <li>refDocNo / operatorUserId 同既有先例。</li>
 * </ul>
 */
@Data
@Builder
public class ReturnStockContext {
    private Long wholesalerId;
    private Long tenantId;
    private Long skuId;
    private Integer qty;
    private Integer palletReleaseOverride;
    private String refDocNo;
    private Long operatorUserId;
}
