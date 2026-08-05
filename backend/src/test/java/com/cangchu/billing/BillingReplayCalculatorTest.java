package com.cangchu.billing;

import com.cangchu.billing.engine.BillingReplayCalculator;
import com.cangchu.billing.engine.BillingReplayCalculator.DayPosition;
import com.cangchu.billing.engine.BillingReplayCalculator.Movement;
import com.cangchu.billing.engine.BillingReplayCalculator.NetPosition;
import com.cangchu.billing.engine.BillingReplayCalculator.Segment;
import com.cangchu.billing.engine.BillingReplayCalculator.StorageLine;
import com.cangchu.inventory.entity.StockMovement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P4 W2 统一回放引擎金标准（14 §1.6 G1–G10 + 05 §1.6 边界示例 + 任务关卡边界日用例）。
 * 纯函数直驱（无 Spring 上下文）；全部期望值为手算账本断言，严禁由实现反推。
 */
class BillingReplayCalculatorTest {

    private static final Long SKU = 101L;

    private static Movement mv(String type, int qty, LocalDate bizDate) {
        return new Movement(SKU, type, qty, bizDate, 0);
    }

    private static Movement mv(String type, int qty, LocalDate bizDate, int palletDelta) {
        return new Movement(SKU, type, qty, bizDate, palletDelta);
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    /** 某 SKU 全月件·天合计（=Σ逐日 billableQty） */
    private static long qtyDays(List<Movement> ms, YearMonth month) {
        return sum(ms, month, true);
    }

    private static long palletDays(List<Movement> ms, YearMonth month) {
        return sum(ms, month, false);
    }

    private static long sum(List<Movement> ms, YearMonth month, boolean qtyDim) {
        Map<Long, List<DayPosition>> positions =
                BillingReplayCalculator.dailyPositions(ms, month.atDay(1), month.atEndOfMonth());
        return positions.values().stream().flatMap(List::stream)
                .mapToLong(p -> qtyDim ? p.qty() : p.pallet()).sum();
    }

    /** 单日 billableQty */
    private static int qtyOn(List<Movement> ms, LocalDate day) {
        Map<Long, List<DayPosition>> positions = BillingReplayCalculator.dailyPositions(ms, day, day);
        return positions.isEmpty() ? 0 : positions.get(SKU).get(0).qty();
    }

    private static int palletOn(List<Movement> ms, LocalDate day) {
        Map<Long, List<DayPosition>> positions = BillingReplayCalculator.dailyPositions(ms, day, day);
        return positions.isEmpty() ? 0 : positions.get(SKU).get(0).pallet();
    }

    private static Segment seg(LocalDate from, LocalDate to, String priceQty) {
        return new Segment(from, to, true, new BigDecimal(priceQty), false, null);
    }

    // ==================== G1–G5：05 §1.6 五边界示例原样造数 ====================

    @Test
    @DisplayName("G1 5/10 入库 10，5/20 出库全部 → 100 件·天（起算次日、出库当日仍计）")
    void g1_inThenOutAll() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(5, 10)),
                mv(StockMovement.TYPE_OUTBOUND, 10, d(5, 20)));
        assertThat(qtyDays(ms, YearMonth.of(2026, 5))).isEqualTo(100);
        // 边界日：入库日当日不计、次日起算；出库日当日仍计、次日不计
        assertThat(qtyOn(ms, d(5, 10))).isZero();
        assertThat(qtyOn(ms, d(5, 11))).isEqualTo(10);
        assertThat(qtyOn(ms, d(5, 20))).isEqualTo(10);
        assertThat(qtyOn(ms, d(5, 21))).isZero();
    }

    @Test
    @DisplayName("G2 同日入出 → 0 件·天（不收费）")
    void g2_sameDayInOut() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(5, 10)),
                mv(StockMovement.TYPE_OUTBOUND, 10, d(5, 10)));
        assertThat(qtyDays(ms, YearMonth.of(2026, 5))).isZero();
    }

    @Test
    @DisplayName("G3 5/10 入 10，5/15 退货 4，5/25 出 6 → 110 件·天")
    void g3_inReturnOut() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(5, 10)),
                mv(StockMovement.TYPE_RETURN, 4, d(5, 15)),
                mv(StockMovement.TYPE_OUTBOUND, 6, d(5, 25)));
        // (5/11~5/15) 10×5 + (5/16~5/25) 6×10 = 110
        assertThat(qtyDays(ms, YearMonth.of(2026, 5))).isEqualTo(110);
        // 退货日边界：当日仍计 10、次日 6
        assertThat(qtyOn(ms, d(5, 15))).isEqualTo(10);
        assertThat(qtyOn(ms, d(5, 16))).isEqualTo(6);
    }

    @Test
    @DisplayName("G4 5/10 入 10，5/20 盘亏 3 → 100 + 7×11 = 177 件·天（5 月）")
    void g4_lossFromDay() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(5, 10)),
                mv(StockMovement.TYPE_LOSS, 3, d(5, 20)));
        // (5/11~5/20) 10×10 + (5/21~5/31) 7×11 = 177
        assertThat(qtyDays(ms, YearMonth.of(2026, 5))).isEqualTo(177);
        // 盘亏日边界：审批日当日仍计、次日按核减后
        assertThat(qtyOn(ms, d(5, 20))).isEqualTo(10);
        assertThat(qtyOn(ms, d(5, 21))).isEqualTo(7);
    }

    @Test
    @DisplayName("G5 5/10 入 10，5/15 盘盈 5 → 盘盈自次日起算（5/15 计 10、5/16 计 15）")
    void g5_gainFromNextDay() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(5, 10)),
                mv(StockMovement.TYPE_GAIN, 5, d(5, 15)));
        assertThat(qtyOn(ms, d(5, 15))).isEqualTo(10);
        assertThat(qtyOn(ms, d(5, 16))).isEqualTo(15);
        // 全月：(5/11~5/15) 10×5 + (5/16~5/31) 15×16 = 290
        assertThat(qtyDays(ms, YearMonth.of(2026, 5))).isEqualTo(290);
    }

    // ==================== G6/G7：争议对 ====================

    @Test
    @DisplayName("G6 入库→异议冲销(D+3)→仲裁恢复：全月件·天与「从未异议」逐日相等（锚点归一）")
    void g6_disputePairEqualsNeverDisputed() {
        List<Movement> disputed = List.of(
                new Movement(SKU, StockMovement.TYPE_INBOUND, 50, d(7, 3), 2, 1L, null),
                new Movement(SKU, StockMovement.TYPE_DISPUTE_REVERSAL, 50, d(7, 6), -2, 2L, null),
                // RESTORE biz_time=原入库时间戳（P3 实现口径），reversal_of_id 回指配对冲销
                new Movement(SKU, StockMovement.TYPE_DISPUTE_RESTORE, 50, d(7, 3), 2, 3L, 2L));
        List<Movement> neverDisputed = List.of(
                new Movement(SKU, StockMovement.TYPE_INBOUND, 50, d(7, 3), 2, 1L, null));

        YearMonth july = YearMonth.of(2026, 7);
        List<DayPosition> a = BillingReplayCalculator
                .dailyPositions(disputed, july.atDay(1), july.atEndOfMonth()).get(SKU);
        List<DayPosition> b = BillingReplayCalculator
                .dailyPositions(neverDisputed, july.atDay(1), july.atEndOfMonth()).get(SKU);
        // 逐日相等（含争议期 7/4~7/9：照常计费、无双计、无中断）
        assertThat(a).containsExactlyElementsOf(b);
        assertThat(qtyDays(disputed, july)).isEqualTo(qtyDays(neverDisputed, july)).isEqualTo(28L * 50);
        assertThat(palletDays(disputed, july)).isEqualTo(palletDays(neverDisputed, july)).isEqualTo(28L * 2);
    }

    @Test
    @DisplayName("G6b 驳回·保留冲销（无恢复流水）：计费截止异议日当天（D39）")
    void g6b_disputeRejectedKeepsReversal() {
        List<Movement> ms = List.of(
                new Movement(SKU, StockMovement.TYPE_INBOUND, 50, d(7, 3), 0, 1L, null),
                new Movement(SKU, StockMovement.TYPE_DISPUTE_REVERSAL, 50, d(7, 6), 0, 2L, null));
        assertThat(qtyOn(ms, d(7, 6))).isEqualTo(50);  // 异议日当天仍计
        assertThat(qtyOn(ms, d(7, 7))).isZero();       // 次日不计
        assertThat(qtyDays(ms, YearMonth.of(2026, 7))).isEqualTo(3L * 50); // 7/4~7/6
    }

    @Test
    @DisplayName("G7 RESTORE reversal_of_id=null / 悬空：照常按自身 biz_time 计入，不抛异常")
    void g7_orphanRestoreTolerated() {
        List<Movement> orphanNull = List.of(
                new Movement(SKU, StockMovement.TYPE_DISPUTE_RESTORE, 20, d(7, 3), 0, 9L, null));
        List<Movement> orphanDangling = List.of(
                new Movement(SKU, StockMovement.TYPE_DISPUTE_RESTORE, 20, d(7, 3), 0, 9L, 999L));
        for (List<Movement> ms : List.of(orphanNull, orphanDangling)) {
            assertThatCode(() -> BillingReplayCalculator.dailyPositions(ms, d(7, 1), d(7, 31)))
                    .doesNotThrowAnyException();
            assertThat(qtyOn(ms, d(7, 3))).isZero();
            assertThat(qtyOn(ms, d(7, 4))).isEqualTo(20); // 原入库次日起算
        }
    }

    // ==================== G8：盘亏封顶按流水 qty ====================

    @Test
    @DisplayName("G8 盘亏封顶 applied<原差异：按流水 qty（applied）计，不按盘点差异原值")
    void g8_lossUsesAppliedQty() {
        // 盘点差异原值 5，审批时在库仅 3 → LOSS 流水 qty=3（封顶后 applied）
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 3, d(7, 1)),
                mv(StockMovement.TYPE_LOSS, 3, d(7, 10)));
        assertThat(qtyOn(ms, d(7, 10))).isEqualTo(3);
        assertThat(qtyOn(ms, d(7, 11))).isZero();
    }

    // ==================== G9：R20 规则段切换 ====================

    @Test
    @DisplayName("G9 R20 月中变更：同 SKU 拆两段行、变更日归新段、两段金额独立先算后舍")
    void g9_ruleSegmentSplit() {
        List<Movement> ms = List.of(mv(StockMovement.TYPE_INBOUND, 10, d(5, 31)));
        List<Segment> chain = List.of(
                seg(d(6, 1), d(6, 14), "1.0000"),
                seg(d(6, 15), null, "2.0000"));
        List<StorageLine> lines =
                BillingReplayCalculator.monthlyStorageLines(ms, chain, YearMonth.of(2026, 6));
        assertThat(lines).hasSize(2);
        StorageLine first = lines.stream().filter(l -> l.periodStart().equals(d(6, 1))).findFirst().orElseThrow();
        StorageLine second = lines.stream().filter(l -> l.periodStart().equals(d(6, 15))).findFirst().orElseThrow();
        // 6/1~6/14 = 14 天×10 件×1 元；6/15~6/30 = 16 天×10 件×2 元（变更日 6/15 按新价）
        assertThat(first.qtyDays()).isEqualTo(140);
        assertThat(first.periodEnd()).isEqualTo(d(6, 14));
        assertThat(first.amount()).isEqualByComparingTo("140.00");
        assertThat(second.qtyDays()).isEqualTo(160);
        assertThat(second.periodEnd()).isEqualTo(d(6, 30));
        assertThat(second.amount()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("G9b 计费起点截断：首版 effective_from 晚于月初 → 期间自生效日起（历史天数不出账）")
    void g9b_firstRuleTruncatesPeriod() {
        List<Movement> ms = List.of(mv(StockMovement.TYPE_INBOUND, 10, d(6, 1)));
        List<Segment> chain = List.of(seg(d(6, 10), null, "1.0000"));
        List<StorageLine> lines =
                BillingReplayCalculator.monthlyStorageLines(ms, chain, YearMonth.of(2026, 6));
        assertThat(lines).hasSize(1);
        // 6/10~6/30 = 21 天×10 件（6/2~6/9 在库但规则前 → 不出账）
        assertThat(lines.get(0).periodStart()).isEqualTo(d(6, 10));
        assertThat(lines.get(0).qtyDays()).isEqualTo(210);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("210.00");
    }

    // ==================== G10：托盘链与 V20 前基线 ====================

    @Test
    @DisplayName("G10 托盘链：PALLET_RELEASE 不进件·天；托盘·天按 Σpallet_delta")
    void g10_palletChain() {
        List<Movement> ms = List.of(
                mv(StockMovement.TYPE_INBOUND, 10, d(7, 1), 2),
                mv(StockMovement.TYPE_PALLET_RELEASE, 0, d(7, 10), -1),
                mv(StockMovement.TYPE_RETURN, 2, d(7, 15), -1));
        // 件·天不受 PALLET_RELEASE 影响
        assertThat(qtyOn(ms, d(7, 10))).isEqualTo(10);
        assertThat(qtyOn(ms, d(7, 11))).isEqualTo(10);
        // 托盘：7/2~7/10=2、7/11~7/15=1、7/16~=0（释放日当日仍计、次日不计）
        assertThat(palletOn(ms, d(7, 10))).isEqualTo(2);
        assertThat(palletOn(ms, d(7, 11))).isEqualTo(1);
        assertThat(palletOn(ms, d(7, 15))).isEqualTo(1);
        assertThat(palletOn(ms, d(7, 16))).isZero();
    }

    @Test
    @DisplayName("G10b V20 前存量托盘基线=0：pallet_delta 恒 0 的历史入库不产生托盘·天（D-P4-5=A）")
    void g10b_preV20PalletBaselineZero() {
        // V20 前存量流水：qty>0 但 pallet_delta=0（不回填）
        List<Movement> ms = List.of(mv(StockMovement.TYPE_INBOUND, 100, d(7, 1), 0));
        YearMonth july = YearMonth.of(2026, 7);
        assertThat(qtyDays(ms, july)).isEqualTo(30L * 100);
        assertThat(palletDays(ms, july)).isZero();
        // 规则生效后首笔带托盘流水才起账
        List<Movement> withNew = List.of(
                mv(StockMovement.TYPE_INBOUND, 100, d(7, 1), 0),
                mv(StockMovement.TYPE_INBOUND, 10, d(7, 20), 3));
        assertThat(palletOn(withNew, d(7, 20))).isZero();
        assertThat(palletOn(withNew, d(7, 21))).isEqualTo(3);
    }

    // ==================== 十二类流水综合金账本（手算断言金额） ====================

    @Test
    @DisplayName("金账本：跨全部 12 类流水的已知账本，双维金额手算逐行断言")
    void goldenLedgerTwelveTypes() {
        Long skuA = 101L;
        Long skuB = 102L;
        List<Movement> ms = List.of(
                // SKU A：入库/出库/托盘释放/退货/盘亏/盘盈/清库
                new Movement(skuA, StockMovement.TYPE_INBOUND, 100, d(6, 30), 5, 1L, null),
                new Movement(skuA, StockMovement.TYPE_OUTBOUND, 30, d(7, 10), 0, 2L, null),
                new Movement(skuA, StockMovement.TYPE_PALLET_RELEASE, 0, d(7, 12), -2, 3L, null),
                new Movement(skuA, StockMovement.TYPE_RETURN, 10, d(7, 15), -1, 4L, null),
                new Movement(skuA, StockMovement.TYPE_LOSS, 5, d(7, 20), 0, 5L, null),
                new Movement(skuA, StockMovement.TYPE_GAIN, 8, d(7, 25), 0, 6L, null),
                new Movement(skuA, StockMovement.TYPE_EXPIRY_CLEARANCE, 4, d(7, 28), 0, 7L, null),
                // SKU B：争议对/纠错对/出库回补对
                new Movement(skuB, StockMovement.TYPE_INBOUND, 50, d(7, 3), 2, 11L, null),
                new Movement(skuB, StockMovement.TYPE_DISPUTE_REVERSAL, 50, d(7, 6), -2, 12L, null),
                new Movement(skuB, StockMovement.TYPE_DISPUTE_RESTORE, 50, d(7, 3), 2, 13L, 12L),
                new Movement(skuB, StockMovement.TYPE_CORRECTION_IN, 5, d(7, 3), 0, 14L, 11L),
                new Movement(skuB, StockMovement.TYPE_CORRECTION_OUT, 10, d(7, 3), 0, 15L, 11L),
                new Movement(skuB, StockMovement.TYPE_OUTBOUND, 20, d(7, 15), 0, 16L, null),
                new Movement(skuB, StockMovement.TYPE_OUTBOUND_REVERSAL, 20, d(7, 15), 0, 17L, 16L));
        List<Segment> chain = List.of(
                new Segment(d(7, 1), null, true, new BigDecimal("0.5000"), true, new BigDecimal("2.0000")));

        List<StorageLine> lines =
                BillingReplayCalculator.monthlyStorageLines(ms, chain, YearMonth.of(2026, 7));
        assertThat(lines).hasSize(2);
        StorageLine lineA = lines.stream().filter(l -> l.skuId().equals(skuA)).findFirst().orElseThrow();
        StorageLine lineB = lines.stream().filter(l -> l.skuId().equals(skuB)).findFirst().orElseThrow();

        // SKU A 手算：件·天 = 10×100 + 5×70 + 5×60 + 5×55 + 3×63 + 3×59 = 2291
        //            托盘·天 = 12×5 + 3×3 + 16×2 = 101
        //            金额 = 2291×0.5 + 101×2.0 = 1145.50 + 202.00 = 1347.50
        assertThat(lineA.qtyDays()).isEqualTo(2291);
        assertThat(lineA.palletDays()).isEqualTo(101);
        assertThat(lineA.amount()).isEqualByComparingTo("1347.50");

        // SKU B 手算（锚点归一后 7/3 净 +45、争议对/回补对逐日抵消）：
        //            件·天 = 28×45 = 1260；托盘·天 = 28×2 = 56
        //            金额 = 1260×0.5 + 56×2.0 = 630.00 + 112.00 = 742.00
        assertThat(lineB.qtyDays()).isEqualTo(1260);
        assertThat(lineB.palletDays()).isEqualTo(56);
        assertThat(lineB.amount()).isEqualByComparingTo("742.00");

        // 对账净值（Σ 不截断）：A=100−30−10−5+8−4=59 / 托盘 5−2−1=2；B=45 / 托盘 2
        Map<Long, NetPosition> net = BillingReplayCalculator.netAsOf(ms, null);
        assertThat(net.get(skuA)).isEqualTo(new NetPosition(59, 2));
        assertThat(net.get(skuB)).isEqualTo(new NetPosition(45, 2));
    }

    // ==================== 无规则 / 精度 / 截断 / 性能 ====================

    @Test
    @DisplayName("无规则=零账：空段链 → 无 STORAGE 行；规则晚于月末 → 同零账")
    void noRuleZeroBill() {
        List<Movement> ms = List.of(mv(StockMovement.TYPE_INBOUND, 10, d(6, 1)));
        assertThat(BillingReplayCalculator.monthlyStorageLines(ms, List.of(), YearMonth.of(2026, 6)))
                .isEmpty();
        assertThat(BillingReplayCalculator.monthlyStorageLines(ms,
                List.of(seg(d(8, 1), null, "1.0000")), YearMonth.of(2026, 6)))
                .isEmpty();
    }

    @Test
    @DisplayName("金额精度：4 位小数单价逐段先算后舍 HALF_UP 到分（31×0.3333=10.3323→10.33）")
    void amountRoundingHalfUp() {
        List<Movement> ms = List.of(mv(StockMovement.TYPE_INBOUND, 1, d(6, 30)));
        List<StorageLine> lines = BillingReplayCalculator.monthlyStorageLines(ms,
                List.of(seg(d(7, 1), null, "0.3333")), YearMonth.of(2026, 7));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).qtyDays()).isEqualTo(31);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("10.33");
    }

    @Test
    @DisplayName("负值截断：异常流水令 Σ<0 时 billable=0（max(Σ,0)）")
    void negativeClampedToZero() {
        List<Movement> ms = List.of(mv(StockMovement.TYPE_OUTBOUND, 5, d(7, 1)));
        assertThat(qtyOn(ms, d(7, 2))).isZero();
        assertThat(qtyDays(ms, YearMonth.of(2026, 7))).isZero();
    }

    @Test
    @DisplayName("性能护栏：单商户万级流水全月回放 <1s（14 §7 W2 闸门）")
    void performanceTenThousandMovements() {
        List<Movement> ms = new ArrayList<>(10_000);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            long sku = 200 + rnd.nextInt(20);
            LocalDate day = d(1, 1).plusDays(rnd.nextInt(200));
            boolean in = i % 2 == 0;
            ms.add(new Movement(sku, in ? StockMovement.TYPE_INBOUND : StockMovement.TYPE_OUTBOUND,
                    rnd.nextInt(1, 50), day, in ? 1 : -1));
        }
        List<Segment> chain = List.of(
                new Segment(d(1, 1), null, true, new BigDecimal("0.5000"), true, new BigDecimal("2.0000")));
        long start = System.nanoTime();
        BillingReplayCalculator.monthlyStorageLines(ms, chain, YearMonth.of(2026, 7));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("万级流水全月回放耗时(ms)").isLessThan(1000);
    }
}
