package com.cangchu.billing.service.impl;

import com.cangchu.billing.engine.BillingReplayCalculator;
import com.cangchu.billing.engine.BillingReplayCalculator.Movement;
import com.cangchu.billing.engine.BillingReplayCalculator.Segment;
import com.cangchu.billing.engine.BillingReplayCalculator.StorageLine;
import com.cangchu.billing.service.BillingReplayService;
import com.cangchu.billing.service.BillingRuleService;
import com.cangchu.billing.vo.BillingRuleVo;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.StorageLineVo;
import com.cangchu.inventory.dto.BillingMovementView;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 统一回放引擎实现（P4 W2，14 §1）。编排三出口：
 * 流水=InventoryService.listMovementsForBilling（G-S1）、规则段=BillingRuleService.listRuleChain、
 * SKU 名=SkuService.getById（L-5 冗余）；计算全在 {@link BillingReplayCalculator} 纯函数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReplayServiceImpl implements BillingReplayService {

    private final InventoryService inventoryService;
    private final BillingRuleService billingRuleService;
    private final SkuService skuService;

    @Override
    public Map<Long, List<BillingReplayCalculator.DayPosition>> replayDailyPositions(
            Long tenantId, Long wholesalerId, LocalDate from, LocalDate to) {
        // untilExclusive = to：D=to 的计费量只消费 bizDate ≤ to−1（公式内生，多取无害少取致错）
        List<Movement> movements = loadMovements(tenantId, wholesalerId, to);
        return BillingReplayCalculator.dailyPositions(movements, from, to);
    }

    @Override
    public Map<Long, BillingReplayCalculator.NetPosition> replayNet(
            Long tenantId, Long wholesalerId, LocalDate untilInclusive) {
        List<Movement> movements = loadMovements(tenantId, wholesalerId,
                untilInclusive != null ? untilInclusive.plusDays(1) : null);
        return BillingReplayCalculator.netAsOf(movements, untilInclusive);
    }

    @Override
    public MonthlyReplayVo replayMonthly(Long tenantId, Long wholesalerId, YearMonth month) {
        LocalDate monthEnd = month.atEndOfMonth();
        List<Segment> chain = loadRuleChain(tenantId, monthEnd);
        List<Movement> movements = loadMovements(tenantId, wholesalerId, monthEnd);
        List<StorageLine> lines = BillingReplayCalculator.monthlyStorageLines(movements, chain, month);

        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        if (!chain.isEmpty() && !chain.get(0).from().isAfter(monthEnd)) {
            periodStart = chain.get(0).from().isAfter(month.atDay(1)) ? chain.get(0).from() : month.atDay(1);
            periodEnd = monthEnd;
        }

        // L-5：skuName 冗余带出（同 SKU 只查一次；已删/不可见 null 由前端兜底）
        Map<Long, String> nameCache = new HashMap<>();
        List<StorageLineVo> lineVos = lines.stream()
                .sorted(Comparator.comparing(StorageLine::skuId).thenComparing(StorageLine::periodStart))
                .map(l -> StorageLineVo.builder()
                        .skuId(l.skuId() != null ? l.skuId().toString() : null)
                        .skuName(nameCache.computeIfAbsent(l.skuId(), this::resolveSkuName))
                        .periodStart(l.periodStart())
                        .periodEnd(l.periodEnd())
                        .qtyDays(l.unitPriceQty() != null ? l.qtyDays() : null)
                        .palletDays(l.unitPricePallet() != null ? l.palletDays() : null)
                        .unitPriceQty(l.unitPriceQty())
                        .unitPricePallet(l.unitPricePallet())
                        .amount(l.amount())
                        .build())
                .toList();
        BigDecimal subtotal = lineVos.stream()
                .map(StorageLineVo::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return MonthlyReplayVo.builder()
                .month(month.toString())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .lines(lineVos)
                .subtotal(subtotal)
                .build();
    }

    @Override
    public List<YearMonth> findBackdatedMonths(Long tenantId, Long wholesalerId, YearMonth period) {
        LocalDate periodStart = period.atDay(1);
        LocalDateTime createdFrom = periodStart.atStartOfDay();
        LocalDateTime createdUntil = period.atEndOfMonth().plusDays(1).atStartOfDay();
        TreeSet<YearMonth> months = new TreeSet<>();
        for (BillingMovementView v : inventoryService.listMovementsForBilling(tenantId, wholesalerId, null)) {
            // 锚点在过去的新流水：created_at ∈ 本期 ∧ bizDate < 本期期初（14 §1.5-1）
            if (v.createdAt() != null
                    && !v.createdAt().isBefore(createdFrom) && v.createdAt().isBefore(createdUntil)
                    && v.bizDate().isBefore(periodStart)) {
                months.add(YearMonth.from(v.bizDate()));
            }
        }
        return List.copyOf(months);
    }

    @Override
    public BigDecimal computeCrossMonthAdjustment(Long tenantId, Long wholesalerId,
                                                  YearMonth affectedMonth, BigDecimal originalStorageSubtotal) {
        // 影子重算（含新流水）− 原 STORAGE 小计；原历史账单一字不动（14 §1.5-2）
        BigDecimal shadow = replayMonthly(tenantId, wholesalerId, affectedMonth).getSubtotal();
        BigDecimal original = originalStorageSubtotal != null ? originalStorageSubtotal : BigDecimal.ZERO;
        return shadow.subtract(original).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ==================== 私有 ====================

    private List<Movement> loadMovements(Long tenantId, Long wholesalerId, LocalDate untilExclusive) {
        return inventoryService.listMovementsForBilling(tenantId, wholesalerId, untilExclusive).stream()
                .map(v -> new Movement(v.skuId(), v.type(), v.qty(), v.bizDate(), v.palletDelta()))
                .toList();
    }

    /** listRuleChain → 计算器段链（升序；effectiveTo=null 为当前段） */
    private List<Segment> loadRuleChain(Long tenantId, LocalDate untilInclusive) {
        return billingRuleService.listRuleChain(tenantId, untilInclusive).stream()
                .map(BillingReplayServiceImpl::toSegment)
                .toList();
    }

    private static Segment toSegment(BillingRuleVo vo) {
        return new Segment(vo.getEffectiveFrom(), vo.getEffectiveTo(),
                Boolean.TRUE.equals(vo.getQtyEnabled()), vo.getPricePerQtyDay(),
                Boolean.TRUE.equals(vo.getPalletEnabled()), vo.getPricePerPalletDay());
    }

    private String resolveSkuName(Long skuId) {
        if (skuId == null) {
            return null;
        }
        SkuVo sku = skuService.getById(skuId);
        return sku != null ? sku.getName() : null;
    }
}
