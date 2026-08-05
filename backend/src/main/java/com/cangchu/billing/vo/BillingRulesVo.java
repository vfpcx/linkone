package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 计费规则读契约（P4 W1，14 §2.2：GET /api/v1/tenant/billing-rules）。
 *
 * <p>空态契约（无任何规则）：{@code current=null, history=[]}——前端据此渲染
 * 「首次设置空表单 + 引导横幅」（PRD 13-p4 §1.3/§8.1）。
 */
@Data
@Builder
public class BillingRulesVo {

    /** 当前生效版本（effective_to IS NULL）；无规则为 null */
    private BillingRuleVo current;

    /** 历史版本（已关闭行，按版本号倒序）；无历史为空数组 */
    private List<BillingRuleVo> history;
}
