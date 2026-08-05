package com.cangchu.billing.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 计费规则保存入参（P4 W1，14 §2.2：POST /api/v1/tenant/billing-rules）。
 *
 * <p>字段名沿用产品原型口径（billingByQty/billingByPallet），与 W4 前端契约对齐——
 * 旧 Settings.vue 幽灵字段自本 API 起真实生效（§2.6 契约断裂修复）。
 * 校验（50379 四路）：两维至少启用其一；启用维单价必填且 ≥0；停用维单价忽略置 NULL。
 * confirmed 为 R20 二次确认凭据：仅「真实变更既有规则」时必须 true（缺失 40003，batch-toggle 先例）；
 * 首版保存与幂等空转无需。
 */
@Data
public class BillingRuleSaveDto {

    /** 启用件·天计费（null 视为 false） */
    private Boolean billingByQty;

    /** 件·天单价（billingByQty=true 时必填 ≥0） */
    private BigDecimal pricePerQtyDay;

    /** 启用托盘·天计费（null 视为 false） */
    private Boolean billingByPallet;

    /** 托盘·天单价（billingByPallet=true 时必填 ≥0） */
    private BigDecimal pricePerPalletDay;

    /** R20 弹窗二次确认凭据（真实变更时必须为 true，否则 40003） */
    private Boolean confirmed;
}
