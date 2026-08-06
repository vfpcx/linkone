package com.cangchu.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.billing.dto.SnapshotRecalcDto;
import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.SnapshotRecalcResultVo;
import com.cangchu.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计费快照/对账下钻 Controller（P4 W2，14 §1.2；供 W4 ST 前端）。
 *
 * <p>路径 /api/v1/tenant/st/** 已在 SaTokenConfig checkLogin 段；鉴权（ST 或 TA 兼岗，
 * WE 42004 / WK·WA 42001）在 Service 内以 user_roles 登录态推导（W1 gate 同构）。
 * ST 不可见库存明细（05 §5.4）：本域仅暴露快照聚合与回放月汇总，无实时库存端点。
 */
@RestController
@RequiredArgsConstructor
public class BillingSnapshotController {

    private final DailySnapshotService dailySnapshotService;

    /**
     * 按日下钻：某商户某月逐日计费件数/托盘 + 当日金额（当段单价现算；无规则段 null）。
     * skuId 可空=全 SKU 聚合。
     */
    @GetMapping("/api/v1/tenant/st/snapshots/daily")
    public R<List<DailyBreakdownRowVo>> dailyBreakdown(@RequestParam Long wholesalerId,
                                                       @RequestParam String month,
                                                       @RequestParam(required = false) Long skuId) {
        return R.ok(dailySnapshotService.dailyBreakdown(
                StpUtil.getLoginIdAsLong(), wholesalerId, month, skuId));
    }

    /** 按 SKU 下钻：某商户某月 SKU×规则段行 + 小计（回放月汇总，与 W3 出账同源）。 */
    @GetMapping("/api/v1/tenant/st/snapshots/by-sku")
    public R<MonthlyReplayVo> skuBreakdown(@RequestParam Long wholesalerId,
                                           @RequestParam String month) {
        return R.ok(dailySnapshotService.skuBreakdown(
                StpUtil.getLoginIdAsLong(), wholesalerId, month));
    }

    /**
     * 快照重放覆写（快照仅缓存语义，D-P4-2=A）：对本租户（或指定商户）重算 [from, to] 快照，
     * 以回放为准删插覆写；幂等可重复调用。
     */
    @PostMapping("/api/v1/tenant/st/snapshots/recalc")
    public R<SnapshotRecalcResultVo> recalc(@RequestBody SnapshotRecalcDto dto) {
        return R.ok(dailySnapshotService.recalcSnapshots(StpUtil.getLoginIdAsLong(),
                dto.getWholesalerId(), dto.getFrom(), dto.getTo()));
    }
}
