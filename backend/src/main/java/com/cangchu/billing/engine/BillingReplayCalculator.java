package com.cangchu.billing.engine;

import com.cangchu.inventory.entity.StockMovement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统一回放引擎纯函数（P4 W2，14 §1.1 引擎核心不变量 / 05 §1.3）。
 *
 * <p>核心公式（十二类流水一条规则通吃，无逐类型 if 分支）：
 * <pre>
 * signed(m) = +qty  type ∈ {INBOUND, GAIN, DISPUTE_RESTORE, CORRECTION_IN, OUTBOUND_REVERSAL}
 *           = −qty  type ∈ {OUTBOUND, RETURN, LOSS, DISPUTE_REVERSAL, CORRECTION_OUT, EXPIRY_CLEARANCE}
 *           =  0    type = PALLET_RELEASE（qty 恒 0，天然无影响）
 * billableQty(D)    = max( Σ signed(m)       where bizDate(m) ≤ D−1, 0 )
 * billablePallet(D) = max( Σ pallet_delta(m) where bizDate(m) ≤ D−1, 0 )
 * </pre>
 *
 * <p>「≤ D−1」一式实现全部基准日口径（05 §1.2）：入库/盘盈/仲裁恢复/纠错补录次日 0:00 起算；
 * 出库/退货/盘亏/清库/异议冲销/纠错冲销当日仍计、次日不计；同日入出 0 件·天；
 * DISPUTE_REVERSAL(异议日)+DISPUTE_RESTORE(biz_time=原入库时间戳) 配对自动呈现连续计费，
 * 无需按 reversal_of_id 特判（RESTORE reversal_of_id=null 防御缺口天然容错，G7）。
 * V20 前存量流水 pallet_delta 恒 0 ⇒ 托盘·天自然以规则生效时 Σpallet_delta 现值为基线（D-P4-5=A）。
 *
 * <p>纯函数（输入=流水列表+规则段链，输出=逐日/逐段聚合），供 Job 与测试直驱
 * （BatchService.recalcAll 先例）；月内逐日用前缀和，O(流水数 + 天数×规则段数)。
 * 金额精度（14 §8）：逐段先算后舍（HALF_UP 到分）；单日金额同口径（下钻展示用）。
 */
public final class BillingReplayCalculator {

    private BillingReplayCalculator() {
    }

    // ==================== 值对象 ====================

    /** 回放输入流水（五锚点已折算：bizDate=biz_time 所在自然日） */
    public record Movement(Long skuId, String type, int qty, LocalDate bizDate, int palletDelta) {
    }

    /** 规则段（listRuleChain 升序段链映射；to=null 为当前段开区间） */
    public record Segment(LocalDate from, LocalDate to, boolean qtyEnabled, BigDecimal priceQty,
                          boolean palletEnabled, BigDecimal pricePallet) {

        public boolean covers(LocalDate d) {
            return !d.isBefore(from) && (to == null || !d.isAfter(to));
        }
    }

    /** 某 SKU 某日计费量（快照行语义：双 0 不落行由调用方裁决） */
    public record DayPosition(LocalDate date, int qty, int pallet) {
    }

    /** 未截断原始净值（对账哨兵：应与 inventories.qty / pallet_qty 恒等） */
    public record NetPosition(long qtyNet, long palletNet) {
    }

    /** 月度 STORAGE 行（SKU × 规则段，14 §1.3；amount 已 HALF_UP 到分） */
    public record StorageLine(Long skuId, LocalDate periodStart, LocalDate periodEnd,
                              long qtyDays, long palletDays,
                              BigDecimal unitPriceQty, BigDecimal unitPricePallet, BigDecimal amount) {
    }

    // ==================== 公式 ====================

    /** signed(m) 符号：+1 入向 / −1 出向 / 0（PALLET_RELEASE 与未知类型防御） */
    public static int signOf(String type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case StockMovement.TYPE_INBOUND, StockMovement.TYPE_GAIN, StockMovement.TYPE_DISPUTE_RESTORE,
                 StockMovement.TYPE_CORRECTION_IN, StockMovement.TYPE_OUTBOUND_REVERSAL -> 1;
            case StockMovement.TYPE_OUTBOUND, StockMovement.TYPE_RETURN, StockMovement.TYPE_LOSS,
                 StockMovement.TYPE_DISPUTE_REVERSAL, StockMovement.TYPE_CORRECTION_OUT,
                 StockMovement.TYPE_EXPIRY_CLEARANCE -> -1;
            default -> 0;
        };
    }

    /**
     * 逐日物化 [from, to]（含两端）：每 SKU 每日 billableQty/billablePallet。
     * 返回 key=skuId（有流水的 SKU 全在，含全月 0 的——快照「双 0 不落行」由调用方过滤）。
     */
    public static Map<Long, List<DayPosition>> dailyPositions(List<Movement> movements,
                                                              LocalDate from, LocalDate to) {
        Map<Long, List<DayPosition>> result = new LinkedHashMap<>();
        if (from.isAfter(to)) {
            return result;
        }
        for (Map.Entry<Long, TreeMap<LocalDate, long[]>> e : deltasBySku(movements).entrySet()) {
            TreeMap<LocalDate, long[]> deltas = e.getValue();
            long runQty = 0;
            long runPallet = 0;
            // 先折入 bizDate ≤ from−1 的前缀
            for (long[] d : deltas.headMap(from, false).values()) {
                runQty += d[0];
                runPallet += d[1];
            }
            List<DayPosition> days = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                // 当日 D 的计费量 = Σ(bizDate ≤ D−1)，截断 ≥0
                days.add(new DayPosition(d, (int) Math.max(runQty, 0), (int) Math.max(runPallet, 0)));
                long[] today = deltas.get(d);
                if (today != null) {
                    runQty += today[0];
                    runPallet += today[1];
                }
            }
            result.put(e.getKey(), days);
        }
        return result;
    }

    /** 原始净值 Σsigned / Σpallet_delta（bizDate ≤ untilInclusive；null=全量）——对账哨兵用，不截断 */
    public static Map<Long, NetPosition> netAsOf(List<Movement> movements, LocalDate untilInclusive) {
        Map<Long, NetPosition> result = new LinkedHashMap<>();
        for (Map.Entry<Long, TreeMap<LocalDate, long[]>> e : deltasBySku(movements).entrySet()) {
            long q = 0;
            long p = 0;
            for (Map.Entry<LocalDate, long[]> d : e.getValue().entrySet()) {
                if (untilInclusive != null && d.getKey().isAfter(untilInclusive)) {
                    break;
                }
                q += d.getValue()[0];
                p += d.getValue()[1];
            }
            result.put(e.getKey(), new NetPosition(q, p));
        }
        return result;
    }

    /**
     * 月度 STORAGE 行（SKU × 规则段，14 §1.3）：
     * 期间 = [max(月初, 首段 from), 月末]（首版前天数不出账 D-P4-4）；变更日按新段（R20）；
     * 未启用维 days/单价不计；件·天/托盘·天全 0 的 SKU 段不出行；
     * 逐段金额 = qtyDays×件价 + palletDays×托盘价，先算后舍 HALF_UP 到分。
     * 规则段链为空 → 空列表（无规则=零账）。
     */
    public static List<StorageLine> monthlyStorageLines(List<Movement> movements,
                                                        List<Segment> chain, YearMonth month) {
        List<StorageLine> lines = new ArrayList<>();
        if (chain == null || chain.isEmpty()) {
            return lines;
        }
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate periodStart = maxDate(month.atDay(1), chain.get(0).from());
        if (periodStart.isAfter(monthEnd)) {
            return lines;
        }
        Map<Long, List<DayPosition>> positions = dailyPositions(movements, periodStart, monthEnd);
        for (Map.Entry<Long, List<DayPosition>> skuEntry : positions.entrySet()) {
            // 逐段累计（段链升序 → LinkedHashMap 保序）
            Map<Segment, long[]> perSegment = new LinkedHashMap<>();
            for (DayPosition day : skuEntry.getValue()) {
                Segment seg = segmentOf(chain, day.date());
                if (seg == null) {
                    continue; // 段间隙（理论上链连续，仅首段前可能）不出账
                }
                long[] acc = perSegment.computeIfAbsent(seg, s -> new long[2]);
                if (seg.qtyEnabled()) {
                    acc[0] += day.qty();
                }
                if (seg.palletEnabled()) {
                    acc[1] += day.pallet();
                }
            }
            for (Map.Entry<Segment, long[]> se : perSegment.entrySet()) {
                Segment seg = se.getKey();
                long qtyDays = se.getValue()[0];
                long palletDays = se.getValue()[1];
                if (qtyDays == 0 && palletDays == 0) {
                    continue; // 该段无在库量：不出行
                }
                BigDecimal amount = BigDecimal.ZERO;
                if (seg.qtyEnabled()) {
                    amount = amount.add(seg.priceQty().multiply(BigDecimal.valueOf(qtyDays)));
                }
                if (seg.palletEnabled()) {
                    amount = amount.add(seg.pricePallet().multiply(BigDecimal.valueOf(palletDays)));
                }
                lines.add(new StorageLine(skuEntry.getKey(),
                        maxDate(seg.from(), periodStart),
                        seg.to() == null || seg.to().isAfter(monthEnd) ? monthEnd : seg.to(),
                        seg.qtyEnabled() ? qtyDays : 0,
                        seg.palletEnabled() ? palletDays : 0,
                        seg.qtyEnabled() ? seg.priceQty() : null,
                        seg.palletEnabled() ? seg.pricePallet() : null,
                        amount.setScale(2, RoundingMode.HALF_UP)));
            }
        }
        return lines;
    }

    /** 单日金额（下钻展示口径）：qty×件价 + pallet×托盘价（仅启用维），HALF_UP 到分；无段返回 null */
    public static BigDecimal dailyAmount(int qty, int pallet, Segment seg) {
        if (seg == null) {
            return null;
        }
        BigDecimal amount = BigDecimal.ZERO;
        if (seg.qtyEnabled()) {
            amount = amount.add(seg.priceQty().multiply(BigDecimal.valueOf(qty)));
        }
        if (seg.palletEnabled()) {
            amount = amount.add(seg.pricePallet().multiply(BigDecimal.valueOf(pallet)));
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** 日 D 所在规则段（链升序；无命中 null=规则前不出账） */
    public static Segment segmentOf(List<Segment> chain, LocalDate d) {
        for (Segment seg : chain) {
            if (seg.covers(d)) {
                return seg;
            }
        }
        return null;
    }

    // ==================== 私有 ====================

    /** 按 SKU 聚成逐日增量表：date → [Σsigned·qty, Σpallet_delta]（前缀和物料） */
    private static Map<Long, TreeMap<LocalDate, long[]>> deltasBySku(List<Movement> movements) {
        Map<Long, TreeMap<LocalDate, long[]>> bySku = new LinkedHashMap<>();
        for (Movement m : movements) {
            TreeMap<LocalDate, long[]> deltas = bySku.computeIfAbsent(m.skuId(), k -> new TreeMap<>());
            long[] d = deltas.computeIfAbsent(m.bizDate(), k -> new long[2]);
            d[0] += (long) signOf(m.type()) * m.qty();
            d[1] += m.palletDelta();
        }
        return bySku;
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
