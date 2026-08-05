package com.cangchu.billing.service;

import com.cangchu.billing.engine.BillingReplayCalculator;
import com.cangchu.billing.vo.MonthlyReplayVo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 统一回放引擎服务（P4 W2，14 §1：D-P4-2=A 快照作缓存、流水回放为准）。
 *
 * <p>编排层：经 {@code InventoryService.listMovementsForBilling}（G-S1 只读出口）取五锚点流水、
 * 经 {@code BillingRuleService.listRuleChain}（W1 据实现备注 4）取规则段链，
 * 核心计算全部下沉 {@link BillingReplayCalculator} 纯函数（测试直驱先例）。
 *
 * <p><b>W3 契约（账单生成月汇总接口）</b>：{@link #replayMonthly} 产出 STORAGE 行
 * （SKU×规则段，含 days/单价快照/段金额）与 subtotal；{@link #findBackdatedMonths} +
 * {@link #computeCrossMonthAdjustment} 支撑 §1.5 跨月锚点差额 → ADJUSTMENT 入当月账。
 */
public interface BillingReplayService {

    /**
     * 逐日物化：某 (tenant, wholesaler) 全 SKU 在 [from, to]（含两端）的逐日计费量。
     * key=skuId；含全 0 SKU（快照「双 0 不落行」由消费方过滤）。
     */
    Map<Long, List<BillingReplayCalculator.DayPosition>> replayDailyPositions(
            Long tenantId, Long wholesalerId, LocalDate from, LocalDate to);

    /**
     * 原始净值（不截断）：Σsigned / Σpallet_delta，bizDate ≤ untilInclusive（null=全量）。
     * 对账哨兵消费：应与 inventories.qty / pallet_qty 恒等（14 §1.2 用途③）。
     */
    Map<Long, BillingReplayCalculator.NetPosition> replayNet(
            Long tenantId, Long wholesalerId, LocalDate untilInclusive);

    /**
     * 月汇总（W3 出账契约 / ST 按 SKU 下钻）：STORAGE 行（SKU×规则段）+ subtotal + 期间截断。
     * 无规则或规则晚于月末 → 零账（lines=[]、subtotal=0、period=null，D-P4-4）。
     */
    MonthlyReplayVo replayMonthly(Long tenantId, Long wholesalerId, YearMonth month);

    /**
     * §1.5 跨月锚点扫描：created_at ∈ period ∧ bizDate &lt; period 期初 的流水
     * （仲裁恢复/纠错回溯）所涉历史月，升序去重。W3 出账时逐月走
     * {@link #computeCrossMonthAdjustment}。
     */
    List<YearMonth> findBackdatedMonths(Long tenantId, Long wholesalerId, YearMonth period);

    /**
     * §1.5 跨月差额（影子重算）：replay(M', 含新流水).subtotal − originalStorageSubtotal。
     * 差额可正可负；由 W3 以 ADJUSTMENT 条目入当月账，原历史账单一字不动（R20 严肃性同构）。
     *
     * @param originalStorageSubtotal 历史账单 M' 的原 STORAGE 小计（由 W3 从 bills 行取出传入）
     */
    BigDecimal computeCrossMonthAdjustment(Long tenantId, Long wholesalerId,
                                           YearMonth affectedMonth, BigDecimal originalStorageSubtotal);
}
