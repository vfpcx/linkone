package com.cangchu.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.billing.dto.BillingRuleSaveDto;
import com.cangchu.billing.service.BillingRuleService;
import com.cangchu.billing.vo.BillingRuleVo;
import com.cangchu.billing.vo.BillingRulesVo;
import com.cangchu.common.response.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费规则 Controller（P4 W1，14 §2.2 两端点）。
 *
 * <p>路径 /api/v1/tenant/** 已在 SaTokenConfig checkLogin 段；鉴权（TA 写 / ST·TA 读）
 * 在 Service 内以 user_roles 登录态推导。蓝图偏差：04-api-spec 4.5 将 billing-rules 挂 ST 下，
 * 按 US-TA-04/06 §2.4（R20 二次确认为 TA 交互）归 TA 专属写、ST 只读复用 GET（14 §2.2 已定）。
 */
@RestController
@RequiredArgsConstructor
public class BillingRuleController {

    private final BillingRuleService billingRuleService;

    /** 读当前规则 + 历史版本（TA/ST）。无规则空态：{current:null, history:[]}。 */
    @GetMapping("/api/v1/tenant/billing-rules")
    public R<BillingRulesVo> getRules() {
        return R.ok(billingRuleService.getRules(StpUtil.getLoginIdAsLong()));
    }

    /**
     * 保存规则（TA；R20 变更事务）：首存免二次确认、自当日生效、不补历史；
     * 真实变更需 confirmed=true（40003）；同日多次变更最后一次生效。
     */
    @PostMapping("/api/v1/tenant/billing-rules")
    public R<BillingRuleVo> saveRule(@Valid @RequestBody BillingRuleSaveDto dto) {
        return R.ok(billingRuleService.saveRule(StpUtil.getLoginIdAsLong(), dto));
    }
}
