package com.cangchu.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.vo.BillsOverviewVo;
import com.cangchu.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TA 账单总览 Controller（P4 W5 补口，14 §6.3 US-TA-08；W4 前端移交缺口）。
 *
 * <p>路径 /api/v1/tenant/** 已在 SaTokenConfig checkLogin 段；requireTa 在 Service 内以
 * user_roles 登录态推导（ST 42001 / WE 42004 / WK·WA 42001，S4）。
 */
@RestController
@RequiredArgsConstructor
public class BillsOverviewController {

    private final BillingService billingService;

    /** 账单总览：month 可缺省=全部月份；应收/已收/未收/账单数/状态分布 + 逐商户行（下钻单 WA） */
    @GetMapping("/api/v1/tenant/bills-overview")
    public R<BillsOverviewVo> overview(@RequestParam(required = false) String month) {
        return R.ok(billingService.billsOverview(StpUtil.getLoginIdAsLong(), month));
    }
}
