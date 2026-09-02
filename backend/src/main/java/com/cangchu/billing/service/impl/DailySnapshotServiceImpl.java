package com.cangchu.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.billing.engine.BillingReplayCalculator;
import com.cangchu.billing.engine.BillingReplayCalculator.DayPosition;
import com.cangchu.billing.engine.BillingReplayCalculator.NetPosition;
import com.cangchu.billing.engine.BillingReplayCalculator.Segment;
import com.cangchu.billing.entity.DailySnapshot;
import com.cangchu.billing.mapper.DailySnapshotMapper;
import com.cangchu.billing.service.BillingReplayService;
import com.cangchu.billing.service.BillingRuleService;
import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.BillingRuleVo;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.SnapshotRecalcResultVo;
import com.cangchu.billing.vo.SnapshotRunResultVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantScopeAuthSupport;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.BillingPairView;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日快照服务实现（P4 W2，14 §1.2）。
 *
 * <p>覆写策略：逐 (tenant, wholesaler, 日) 单事务删插（回放为准）；
 * uk_snap_ws_sku_date 兜底并发不重复。Job 系统态无 TenantContext（白名单不注入，72h Job 先例），
 * tenant_id 显式落值；HTTP 态（recalc/下钻）有 TenantContext，白名单兜底隔离叠加显式条件双保险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySnapshotServiceImpl implements DailySnapshotService {

    /** 自注入代理：rebuildAllPairs 逐 (t,ws,日) 经代理调 rebuildSnapshotForDate，保证单事务生效（InventoryServiceImpl 先例）。 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DailySnapshotService self;

    private final DailySnapshotMapper dailySnapshotMapper;
    private final BillingReplayService billingReplayService;
    private final BillingRuleService billingRuleService;
    private final InventoryService inventoryService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    // TA 一账号多仓收敛（20 §2）：TA/ST 端 gate 当前仓解析经 TenantScopeAuthSupport
    private final TenantScopeAuthSupport tenantScopeAuthSupport;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 漏跑回补窗口（14 §1.2：Job 启动时回补缺口 ≤7 日——昨日之前 7 天内整日缺失才补） */
    private static final int BACKFILL_WINDOW_DAYS = 7;
    /** recalc 端点单次跨度护栏（一个季度） */
    private static final int RECALC_MAX_DAYS = 92;

    // ==================== Job 体 ====================

    @Override
    public SnapshotRunResultVo runDailySnapshot() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<BillingPairView> pairs = inventoryService.listMovementPairsForBilling();

        // ① 回补缺口（≤7 日）：窗口内某日整日无任何快照行 → 视为漏跑，全量补算该日
        List<LocalDate> backfilled = new ArrayList<>();
        for (LocalDate d = yesterday.minusDays(BACKFILL_WINDOW_DAYS); d.isBefore(yesterday); d = d.plusDays(1)) {
            Long count = dailySnapshotMapper.selectCount(new LambdaQueryWrapper<DailySnapshot>()
                    .eq(DailySnapshot::getSnapshotDate, d));
            if (count == 0 && rebuildAllPairs(pairs, d) > 0) {
                backfilled.add(d);
            }
        }

        // ② 昨日全量快照（重跑覆写幂等）
        int rows = rebuildAllPairs(pairs, yesterday);

        // ③ 全量不变量对账哨兵
        int mismatch = reconcileWithInventory();

        log.info("[P4][快照Job] 快照日={} 组合={} 落行={} 回补日={} 对账不一致={}",
                yesterday, pairs.size(), rows, backfilled, mismatch);
        return SnapshotRunResultVo.builder()
                .snapshotDate(yesterday)
                .pairCount(pairs.size())
                .rowCount(rows)
                .backfilledDates(backfilled)
                .mismatchCount(mismatch)
                .build();
    }

    private int rebuildAllPairs(List<BillingPairView> pairs, LocalDate date) {
        int rows = 0;
        for (BillingPairView pair : pairs) {
            try {
                rows += self.rebuildSnapshotForDate(pair.tenantId(), pair.wholesalerId(), date);
            } catch (Exception e) {
                // 单组合失败吞异常记日志继续（recalcAll 先例）；次日重跑/手动 recalc 兜底
                log.error("[P4][快照Job] 组合失败 tenant={} ws={} date={}（跳过继续）",
                        pair.tenantId(), pair.wholesalerId(), date, e);
            }
        }
        return rows;
    }

    // ==================== 覆写（回放为准） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rebuildSnapshotForDate(Long tenantId, Long wholesalerId, LocalDate date) {
        // 回放该日（含全 0 SKU），双 0 不落行
        Map<Long, List<DayPosition>> positions =
                billingReplayService.replayDailyPositions(tenantId, wholesalerId, date, date);
        Map<Long, int[]> desired = new LinkedHashMap<>();
        positions.forEach((skuId, days) -> {
            DayPosition p = days.get(0);
            if (p.qty() != 0 || p.pallet() != 0) {
                desired.put(skuId, new int[]{p.qty(), p.pallet()});
            }
        });

        Map<Long, DailySnapshot> existing = new HashMap<>();
        dailySnapshotMapper.selectList(new LambdaQueryWrapper<DailySnapshot>()
                        .eq(DailySnapshot::getTenantId, tenantId)
                        .eq(DailySnapshot::getWholesalerId, wholesalerId)
                        .eq(DailySnapshot::getSnapshotDate, date))
                .forEach(s -> existing.put(s.getSkuId(), s));

        for (Map.Entry<Long, int[]> e : desired.entrySet()) {
            DailySnapshot row = existing.remove(e.getKey());
            int qty = e.getValue()[0];
            int pallet = e.getValue()[1];
            if (row == null) {
                DailySnapshot fresh = new DailySnapshot();
                fresh.setId(snowflakeIdUtil.nextId());
                fresh.setTenantId(tenantId);       // Job 无 TenantContext，显式落值
                fresh.setWholesalerId(wholesalerId);
                fresh.setSkuId(e.getKey());
                fresh.setSnapshotDate(date);
                fresh.setQty(qty);
                fresh.setPalletQty(pallet);
                dailySnapshotMapper.insert(fresh);
            } else if (row.getQty() != qty || row.getPalletQty() != pallet) {
                // 快照 ≠ 回放：以回放为准覆写并记差异日志（D-P4-2=A）
                log.warn("[P4][快照对账] 快照与回放不一致，以回放为准覆写 ws={} sku={} date={} " +
                                "快照(qty={},pallet={}) → 回放(qty={},pallet={})",
                        wholesalerId, row.getSkuId(), date, row.getQty(), row.getPalletQty(), qty, pallet);
                row.setQty(qty);
                row.setPalletQty(pallet);
                dailySnapshotMapper.updateById(row);
            }
        }
        // 回放已无量（双 0）但快照残留 → 同为不一致，以回放为准删行
        for (DailySnapshot stale : existing.values()) {
            log.warn("[P4][快照对账] 快照残留行回放为 0，以回放为准删除 ws={} sku={} date={} " +
                            "快照(qty={},pallet={})",
                    wholesalerId, stale.getSkuId(), date, stale.getQty(), stale.getPalletQty());
            dailySnapshotMapper.deleteById(stale.getId());
        }
        return desired.size();
    }

    // ==================== recalc 端点体 ====================

    @Override
    public SnapshotRecalcResultVo recalcSnapshots(Long userId, Long wholesalerId, LocalDate from, LocalDate to) {
        Long tenantId = requireStOrTa(userId);
        if (from == null || to == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写重算起止日期");
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (from.isAfter(to) || to.isAfter(yesterday)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "重算区间无效（起 ≤ 止 ≤ 昨日）");
        }
        if (from.plusDays(RECALC_MAX_DAYS - 1L).isBefore(to)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "重算区间过长（单次最多 " + RECALC_MAX_DAYS + " 天）");
        }
        List<BillingPairView> pairs;
        if (wholesalerId != null) {
            assertWholesalerOfTenant(tenantId, wholesalerId);
            pairs = List.of(new BillingPairView(tenantId, wholesalerId));
        } else {
            pairs = inventoryService.listMovementPairsForBilling().stream()
                    .filter(p -> tenantId.equals(p.tenantId()))
                    .toList();
        }
        int rows = 0;
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            rows += rebuildAllPairs(pairs, d);
            days++;
        }
        log.info("[P4][快照重算] tenant={} ws={} [{}~{}] 天数={} 落行={}",
                tenantId, wholesalerId, from, to, days, rows);
        return SnapshotRecalcResultVo.builder().from(from).to(to).days(days).rows(rows).build();
    }

    // ==================== 下钻（ST/TA） ====================

    @Override
    public List<DailyBreakdownRowVo> dailyBreakdown(Long userId, Long wholesalerId, String month, Long skuId) {
        Long tenantId = requireStOrTa(userId);
        assertWholesalerOfTenant(tenantId, wholesalerId);
        return dailyBreakdownForPair(tenantId, wholesalerId, month, skuId);
    }

    @Override
    public List<DailyBreakdownRowVo> dailyBreakdownForPair(Long tenantId, Long wholesalerId,
                                                           String month, Long skuId) {
        // 无鉴权内核（P4-L2）：调用方须已完成鉴权/归属校验（ST/TA 走上面 gate 版；
        // WA 账单视角经 BillingServiceImpl.requireWaVisibleBill 后进入——行语义两侧完全一致）
        YearMonth ym = parseMonth(month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (end.isAfter(yesterday)) {
            end = yesterday;
        }
        if (start.isAfter(end)) {
            return List.of(); // 未来月：尚无快照
        }

        Map<LocalDate, int[]> byDate = new LinkedHashMap<>();
        dailySnapshotMapper.selectList(new LambdaQueryWrapper<DailySnapshot>()
                        .eq(DailySnapshot::getTenantId, tenantId)
                        .eq(DailySnapshot::getWholesalerId, wholesalerId)
                        .eq(skuId != null, DailySnapshot::getSkuId, skuId)
                        .between(DailySnapshot::getSnapshotDate, start, end))
                .forEach(s -> {
                    int[] acc = byDate.computeIfAbsent(s.getSnapshotDate(), k -> new int[2]);
                    acc[0] += s.getQty();
                    acc[1] += s.getPalletQty();
                });

        List<Segment> chain = billingRuleService.listRuleChain(tenantId, end).stream()
                .map(DailySnapshotServiceImpl::toSegment)
                .toList();
        List<DailyBreakdownRowVo> rows = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            int[] acc = byDate.getOrDefault(d, new int[2]); // 缺行即 0
            Segment seg = BillingReplayCalculator.segmentOf(chain, d);
            rows.add(DailyBreakdownRowVo.builder()
                    .date(d)
                    .qty(acc[0])
                    .palletQty(acc[1])
                    .amount(BillingReplayCalculator.dailyAmount(acc[0], acc[1], seg))
                    .build());
        }
        return rows;
    }

    @Override
    public MonthlyReplayVo skuBreakdown(Long userId, Long wholesalerId, String month) {
        Long tenantId = requireStOrTa(userId);
        assertWholesalerOfTenant(tenantId, wholesalerId);
        return billingReplayService.replayMonthly(tenantId, wholesalerId, parseMonth(month));
    }

    // ==================== 对账哨兵 ====================

    @Override
    public int reconcileWithInventory() {
        int mismatch = 0;
        for (BillingPairView pair : inventoryService.listMovementPairsForBilling()) {
            try {
                Map<Long, NetPosition> net =
                        billingReplayService.replayNet(pair.tenantId(), pair.wholesalerId(), null);
                Map<Long, InventoryVo> invBySku = new HashMap<>();
                for (InventoryVo inv : inventoryService.queryInventory(pair.wholesalerId(), null)) {
                    invBySku.put(inv.getSkuId(), inv);
                }
                for (Map.Entry<Long, NetPosition> e : net.entrySet()) {
                    InventoryVo inv = invBySku.get(e.getKey());
                    long invQty = inv != null && inv.getQty() != null ? inv.getQty() : 0;
                    long invPallet = inv != null && inv.getPalletQty() != null ? inv.getPalletQty() : 0;
                    if (e.getValue().qtyNet() != invQty || e.getValue().palletNet() != invPallet) {
                        mismatch++;
                        // 流水被绕改/时钟漂移哨兵（14 §1.2 用途③）：只报警不自动改账
                        log.error("[P4][对账哨兵] 回放与库存不一致 tenant={} ws={} sku={} " +
                                        "回放(qty={},pallet={}) vs 库存(qty={},pallet={})",
                                pair.tenantId(), pair.wholesalerId(), e.getKey(),
                                e.getValue().qtyNet(), e.getValue().palletNet(), invQty, invPallet);
                    }
                }
            } catch (Exception e) {
                log.error("[P4][对账哨兵] 组合对账失败 tenant={} ws={}（跳过继续）",
                        pair.tenantId(), pair.wholesalerId(), e);
            }
        }
        return mismatch;
    }

    // ==================== 私有 ====================

    private static Segment toSegment(BillingRuleVo vo) {
        return new Segment(vo.getEffectiveFrom(), vo.getEffectiveTo(),
                Boolean.TRUE.equals(vo.getQtyEnabled()), vo.getPricePerQtyDay(),
                Boolean.TRUE.equals(vo.getPalletEnabled()), vo.getPricePerPalletDay());
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请指定账期月（yyyy-MM）");
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "账期月格式无效（yyyy-MM）");
        }
    }

    /** 商户归属校验：跨租户/不存在统一 50230（TenantLine 兜底之上的显式核对） */
    private void assertWholesalerOfTenant(Long tenantId, Long wholesalerId) {
        if (wholesalerId == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请指定商户");
        }
        WholesalerVo ws = wholesalerService.getById(wholesalerId);
        if (ws == null || !tenantId.equals(ws.getTenantId())) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
    }

    /** 读/重算：ST 或 TA（billing 域 gate，BillingRuleServiceImpl 同构；20 §2 多仓收敛）；WE 42004、其余 42001。 */
    private Long requireStOrTa(Long userId) {
        Long tenantId = tenantScopeAuthSupport.scopedTaOrStTenantId(userId);
        if (tenantId == null) {
            if (authService.hasRole(userId, "WE")) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员或结算员可查看计费快照");
        }
        return tenantId;
    }
}
