package com.cangchu.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.billing.dto.BillDisputeSubmitDto;
import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.vo.BillDetailVo;
import com.cangchu.billing.vo.BillDisputeVo;
import com.cangchu.billing.vo.BillListVo;
import com.cangchu.billing.vo.BillVo;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WA 账单 Controller（P4 W3，14 §6.3；/api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段）。
 *
 * <p>仅批发商管理员（WA）：WE 整域拒绝 42004（WEM-S4-03 防回归）；商户归属以 user_roles
 * 登录态推导（不信任客户端）。仅已下发过的账单可见——待核对（含撤回后）按不存在 50370 不泄漏；
 * 争议中账单保留查看知情权（PRD 13-p4 §7.2）。
 */
@RestController
@RequiredArgsConstructor
public class WholesalerBillController {

    private final BillingService billingService;

    /** 收到的账单（仅已下发过的；含汇总卡） */
    @GetMapping("/api/v1/wholesaler/bills")
    public R<BillListVo> list(@RequestParam(required = false) String month,
                              @RequestParam(required = false) String status) {
        return R.ok(billingService.listWaBills(StpUtil.getLoginIdAsLong(), month, status));
    }

    @GetMapping("/api/v1/wholesaler/bills/{id}")
    public R<BillDetailVo> detail(@PathVariable Long id) {
        return R.ok(billingService.getWaBill(StpUtil.getLoginIdAsLong(), id));
    }

    /**
     * 按日下钻（P4-L2 收口）：账单视角只读 daily_snapshots 聚合，行语义与 ST 侧
     * /tenant/st/bills/{id}/daily-breakdown 完全一致；不暴露实时库存（05 §5.4）。
     * 可见性同详情：未下发/跨商户按不存在 50370；WE 42004。
     */
    @GetMapping("/api/v1/wholesaler/bills/{id}/daily-breakdown")
    public R<List<DailyBreakdownRowVo>> dailyBreakdown(@PathVariable Long id) {
        return R.ok(billingService.waDailyBreakdown(StpUtil.getLoginIdAsLong(), id));
    }

    /** 对账确认 → 待回款（D-P4-6；满 1 日未确认 00:50 Job 自动） */
    @PostMapping("/api/v1/wholesaler/bills/{id}/confirm")
    public R<BillVo> confirm(@PathVariable Long id) {
        return R.ok(billingService.confirmByWa(StpUtil.getLoginIdAsLong(), id));
    }

    /** 申诉（US-WA-08：下发后 7 天窗；同账单至多一张待处理；不冻结账单） */
    @PostMapping("/api/v1/wholesaler/bills/{id}/dispute")
    public R<BillDisputeVo> dispute(@PathVariable Long id, @RequestBody BillDisputeSubmitDto dto) {
        return R.ok(billingService.disputeByWa(StpUtil.getLoginIdAsLong(), id, dto));
    }
}
