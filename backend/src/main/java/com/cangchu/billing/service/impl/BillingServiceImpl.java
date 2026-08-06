package com.cangchu.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.billing.dto.BillAdjustDto;
import com.cangchu.billing.dto.BillDisputeResolveDto;
import com.cangchu.billing.dto.BillDisputeSubmitDto;
import com.cangchu.billing.dto.BillReverseItemDto;
import com.cangchu.billing.dto.PaymentRegisterDto;
import com.cangchu.billing.dto.PaymentReverseDto;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.entity.BillDispute;
import com.cangchu.billing.entity.BillItem;
import com.cangchu.billing.entity.PaymentRecord;
import com.cangchu.billing.mapper.BillDisputeMapper;
import com.cangchu.billing.mapper.BillItemMapper;
import com.cangchu.billing.mapper.BillMapper;
import com.cangchu.billing.mapper.PaymentRecordMapper;
import com.cangchu.billing.service.BillingReplayService;
import com.cangchu.billing.service.BillingRuleService;
import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.BillDetailVo;
import com.cangchu.billing.vo.BillDisputeVo;
import com.cangchu.billing.vo.BillGenerateResultVo;
import com.cangchu.billing.vo.BillItemVo;
import com.cangchu.billing.vo.BillListVo;
import com.cangchu.billing.vo.BillVo;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.PaymentRecordVo;
import com.cangchu.billing.vo.StorageLineVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.file.AttachmentUrls;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.inventory.dto.BillingMovementView;
import com.cangchu.inventory.dto.BillingPairView;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账单生命周期服务实现（P4 W3，14 §3）。
 *
 * <p>安全自检（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：ST 端 requireStOrTa（W1/W2 gate 同构，WE 42004/其余 42001）；WA 端以
 *       user_roles 登录态推导本人商户（不信任客户端）；跨租户/跨商户/未下发对 WA 一律 50370
 *       按不存在（不泄漏存在性）。WE 对账单整域拒绝（WEM-S4-03）。</li>
 *   <li>D-05 状态机：全部写操作经 {@link DocStateMachine} DocKind.BILL 收口（不可达 50330、
 *       CAS 50331）；DISPUTED 位前置冻结 50381（R14，OPS 闭环 P5）。</li>
 *   <li>CON 并发：下发/撤回 casTransition；回款登记/冲销以 (status, paid_amount) 双条件
 *       UPDATE 决出（同单并发恰一方成功）；生成幂等键 uk 兜底并发双跑恰一单。</li>
 *   <li>跨域出口（G-S1/G-S2）：流水经 InventoryService、规则段经 BillingRuleService、
 *       SKU 名经 SkuService、仓库名/简码经 TenantService、商户经 WholesalerService、
 *       通知经 NotificationService——不跨域直连 Mapper。</li>
 * </ul>
 *
 * <p>金额恒等式（14 §8 不变量）：subtotal=ΣSTORAGE+Σ其REVERSAL、adjust=ΣADJUSTMENT+Σ其REVERSAL、
 * total=subtotal+adjust ≥0（Σ条目=三金额；STOCKTAKE_IMPACT 恒 0 不参与）。
 * 据实现口径：REVERSAL 金额按被冲销条目类型归桶——保证 bills.subtotal_amount 恒等于回放
 * STORAGE 小计（§1.5 跨月影子重算以其为原值基准，R10 冲销不失真）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    /** 自注入代理：逐 (t,ws) 经代理调 generateForPair 保证单事务生效（DailySnapshotServiceImpl 先例）。 */
    @Autowired
    @Lazy
    private BillingService self;

    private final BillMapper billMapper;
    private final BillItemMapper billItemMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final BillDisputeMapper billDisputeMapper;
    private final BillingReplayService billingReplayService;
    private final BillingRuleService billingRuleService;
    private final DailySnapshotService dailySnapshotService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final SkuService skuService;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    private static final Set<String> PAY_METHODS = Set.of(
            PaymentRecord.METHOD_BANK_TRANSFER, PaymentRecord.METHOD_CASH,
            PaymentRecord.METHOD_WX, PaymentRecord.METHOD_ALIPAY, PaymentRecord.METHOD_OTHER);

    /** 申诉窗口（天）：下发后 7 天内可提（50378，SQL 数据库时间） */
    private static final int DISPUTE_WINDOW_DAYS = 7;
    /** 凭证/附图上限 */
    private static final int MAX_ATTACHMENTS = 5;

    // ==================== 生成（§3.2） ====================

    @Override
    public BillGenerateResultVo generateMonthlyBills(YearMonth month) {
        // 系统态枚举 (t,ws)（含 WITHDRAWN/OFFLINE/ARCHIVED 尾款月照常出账，§3.2）
        List<BillingPairView> pairs = inventoryService.listMovementPairsForBilling();
        Map<Long, List<Long>> byTenant = new LinkedHashMap<>();
        for (BillingPairView pair : pairs) {
            byTenant.computeIfAbsent(pair.tenantId(), k -> new ArrayList<>()).add(pair.wholesalerId());
        }
        int generated = 0;
        int existing = 0;
        int skipped = 0;
        List<String> billIds = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : byTenant.entrySet()) {
            Long tenantId = e.getKey();
            if (billingRuleService.listRuleChain(tenantId, month.atEndOfMonth()).isEmpty()) {
                // 前置：无生效规则 → 该租户跳过并 log WARN（手动触发端点则抛 50380）
                log.warn("[P4][账单Job] 租户 {} 无生效计费规则，{} 月不出账（跳过）", tenantId, month);
                skipped += e.getValue().size();
                continue;
            }
            int tenantGenerated = 0;
            for (Long wholesalerId : e.getValue()) {
                try {
                    if (billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                            .eq(Bill::getIdempotentKey, idempotentKey(tenantId, wholesalerId, month))) > 0) {
                        existing++;
                        continue;
                    }
                    Bill bill = self.generateForPair(tenantId, wholesalerId, month);
                    if (bill != null) {
                        generated++;
                        tenantGenerated++;
                        billIds.add(bill.getId().toString());
                    } else {
                        skipped++;
                    }
                } catch (Exception ex) {
                    // 失败单商户吞异常记日志继续（recalcAll 先例）；重试=次日手动补跑端点（幂等）
                    skipped++;
                    log.error("[P4][账单Job] 生成失败 tenant={} ws={} month={}（跳过继续）",
                            tenantId, wholesalerId, month, ex);
                }
            }
            if (tenantGenerated > 0) {
                notifyBillsGenerated(tenantId, month, tenantGenerated);
            }
        }
        log.info("[P4][账单Job] {} 月出账完成：新生成={} 已存在={} 跳过={}", month, generated, existing, skipped);
        return BillGenerateResultVo.builder()
                .month(month.toString())
                .generated(generated)
                .existing(existing)
                .skipped(skipped)
                .billIds(billIds)
                .build();
    }

    @Override
    public BillGenerateResultVo generateForTenant(Long userId, String month) {
        Long tenantId = requireStOrTa(userId);
        YearMonth ym = parseMonth(month);
        if (!ym.isBefore(YearMonth.now())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "仅可补生成已结束月份的账单");
        }
        if (billingRuleService.listRuleChain(tenantId, ym.atEndOfMonth()).isEmpty()) {
            throw new BizException(ErrorCode.BILLING_RULE_MISSING);
        }
        int generated = 0;
        int existing = 0;
        int skipped = 0;
        List<String> billIds = new ArrayList<>();
        for (BillingPairView pair : inventoryService.listMovementPairsForBilling()) {
            if (!tenantId.equals(pair.tenantId())) {
                continue;
            }
            if (billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getIdempotentKey, idempotentKey(tenantId, pair.wholesalerId(), ym))) > 0) {
                existing++;
                continue;
            }
            Bill bill = self.generateForPair(tenantId, pair.wholesalerId(), ym);
            if (bill != null) {
                generated++;
                billIds.add(bill.getId().toString());
            } else {
                skipped++;
            }
        }
        if (generated > 0) {
            notifyBillsGenerated(tenantId, ym, generated);
        }
        log.info("[P4][账单][actor_role=ST] 用户 {} 手动补跑 {} 月：新生成={} 已存在={} 跳过={}",
                userId, ym, generated, existing, skipped);
        return BillGenerateResultVo.builder()
                .month(ym.toString())
                .generated(generated)
                .existing(existing)
                .skipped(skipped)
                .billIds(billIds)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Bill generateForPair(Long tenantId, Long wholesalerId, YearMonth month) {
        // ① 幂等：先查后写（重跑/并发天然幂等，90203 语义不占新码）
        String key = idempotentKey(tenantId, wholesalerId, month);
        Bill existing = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getIdempotentKey, key)
                .last("LIMIT 1"));
        if (existing != null) {
            return null;
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        // ② STORAGE 行：W2 月汇总契约（SKU×规则段，含 days/单价快照/段金额）
        MonthlyReplayVo replay = billingReplayService.replayMonthly(tenantId, wholesalerId, month);

        // ③ ADJUSTMENT 行：§1.5 跨月锚点差额（仅已有账单的历史月出差额；无账单不补 D-P4-4）
        List<BillItem> adjustItems = new ArrayList<>();
        for (YearMonth affected : billingReplayService.findBackdatedMonths(tenantId, wholesalerId, month)) {
            Bill affectedBill = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getTenantId, tenantId)
                    .eq(Bill::getWholesalerId, wholesalerId)
                    .eq(Bill::getBillingMonth, affected.toString())
                    .last("LIMIT 1"));
            if (affectedBill == null) {
                continue;
            }
            BigDecimal diff = billingReplayService.computeCrossMonthAdjustment(
                    tenantId, wholesalerId, affected, affectedBill.getSubtotalAmount());
            if (diff.signum() == 0) {
                continue;
            }
            BillItem adj = new BillItem();
            adj.setId(snowflakeIdUtil.nextId());
            adj.setTenantId(tenantId);
            adj.setItemType(BillItem.TYPE_ADJUSTMENT);
            adj.setPeriodStart(affected.atDay(1));
            adj.setPeriodEnd(affected.atEndOfMonth());
            adj.setAmount(diff);
            adj.setDescription(affected + " 仲裁恢复/纠错回溯差额");
            adjustItems.add(adj);
        }

        // ④ 出账对象判定（§3.2）：上月存在计费流水 ∨ 上月首日 billableQty/Pallet>0 ∨ 存在待结转差额
        List<BillingMovementView> movements =
                inventoryService.listMovementsForBilling(tenantId, wholesalerId, null);
        boolean hasMovementInMonth = movements.stream().anyMatch(m ->
                !m.bizDate().isBefore(monthStart) && !m.bizDate().isAfter(monthEnd));
        boolean hasOpening = billingReplayService
                .replayNet(tenantId, wholesalerId, monthStart.minusDays(1)).values().stream()
                .anyMatch(n -> n.qtyNet() > 0 || n.palletNet() > 0);
        boolean hasStorage = replay.getLines() != null && !replay.getLines().isEmpty();
        if (!hasMovementInMonth && !hasOpening && !hasStorage && adjustItems.isEmpty()) {
            return null;
        }

        // ⑤ STOCKTAKE_IMPACT 行（11-p3b-prd:367/US-WK-03）：上月每条 GAIN/LOSS 一行，amount=0 纯展示
        List<BillItem> stocktakeItems = new ArrayList<>();
        for (BillingMovementView m : movements) {
            boolean inMonth = !m.bizDate().isBefore(monthStart) && !m.bizDate().isAfter(monthEnd);
            if (!inMonth) {
                continue;
            }
            String desc = null;
            if (StockMovement.TYPE_GAIN.equals(m.type())) {
                desc = String.format("盘盈 +%d 件（%d-%d 审批，次日起算）",
                        m.qty(), m.bizDate().getMonthValue(), m.bizDate().getDayOfMonth());
            } else if (StockMovement.TYPE_LOSS.equals(m.type())) {
                desc = String.format("盘亏 −%d 件（%d-%d 审批，当日截止）",
                        m.qty(), m.bizDate().getMonthValue(), m.bizDate().getDayOfMonth());
            }
            if (desc != null) {
                BillItem impact = new BillItem();
                impact.setId(snowflakeIdUtil.nextId());
                impact.setTenantId(tenantId);
                impact.setItemType(BillItem.TYPE_STOCKTAKE_IMPACT);
                impact.setSkuId(m.skuId());
                impact.setAmount(BigDecimal.ZERO);
                impact.setDescription(desc);
                stocktakeItems.add(impact);
            }
        }

        // ⑥ 汇总（14 §8 恒等式）与状态落点
        BigDecimal subtotal = scale2(replay.getSubtotal());
        BigDecimal adjust = scale2(adjustItems.stream()
                .map(BillItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal total = subtotal.add(adjust);
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        // 应收 0 生成即 PAID（04 §3.4）；商户 OFFLINE 生成直落 DISPUTED（14 §3.1，生成落点非迁移）
        String status = total.signum() == 0 ? Bill.STATUS_PAID
                : wholesaler != null && "OFFLINE".equals(wholesaler.getStatus())
                ? Bill.STATUS_DISPUTED : Bill.STATUS_DRAFT;

        Bill bill = new Bill();
        bill.setId(snowflakeIdUtil.nextId());
        bill.setBillNo(documentNumberService.generateBillNo(
                tenantService.getSimpleCode(tenantId), wholesalerId, month));
        bill.setTenantId(tenantId);
        bill.setWholesalerId(wholesalerId);
        bill.setBillingMonth(month.toString());
        bill.setPeriodStart(replay.getPeriodStart() != null ? replay.getPeriodStart() : monthStart);
        bill.setPeriodEnd(replay.getPeriodEnd() != null ? replay.getPeriodEnd() : monthEnd);
        bill.setSubtotalAmount(subtotal);
        bill.setAdjustAmount(adjust);
        bill.setTotalAmount(total);
        bill.setPaidAmount(scale2(BigDecimal.ZERO));
        bill.setStatus(status);
        bill.setIdempotentKey(key);
        try {
            billMapper.insert(bill);
        } catch (DuplicateKeyException e) {
            // 并发双跑撞 uk_bill_idempotent/uk_bill_no → 恰一单，本方按已存在返回
            log.info("[P4][账单] 并发生成撞幂等键 tenant={} ws={} month={}，按已存在处理",
                    tenantId, wholesalerId, month);
            return null;
        }

        // STORAGE 条目（SKU×规则段，L-5 skuName 由详情 VO 冗余带出，条目只存 sku_id）
        if (replay.getLines() != null) {
            for (StorageLineVo line : replay.getLines()) {
                BillItem item = new BillItem();
                item.setId(snowflakeIdUtil.nextId());
                item.setTenantId(tenantId);
                item.setBillId(bill.getId());
                item.setItemType(BillItem.TYPE_STORAGE);
                item.setSkuId(line.getSkuId() != null ? Long.valueOf(line.getSkuId()) : null);
                item.setPeriodStart(line.getPeriodStart());
                item.setPeriodEnd(line.getPeriodEnd());
                item.setQtyDays(line.getQtyDays() != null ? line.getQtyDays().intValue() : null);
                item.setPalletDays(line.getPalletDays() != null ? line.getPalletDays().intValue() : null);
                item.setUnitPriceQty(line.getUnitPriceQty());
                item.setUnitPricePallet(line.getUnitPricePallet());
                item.setAmount(line.getAmount());
                billItemMapper.insert(item);
            }
        }
        for (BillItem adj : adjustItems) {
            adj.setBillId(bill.getId());
            billItemMapper.insert(adj);
        }
        for (BillItem impact : stocktakeItems) {
            impact.setBillId(bill.getId());
            billItemMapper.insert(impact);
        }

        log.info("[P4][账单] 生成 {}（tenant={} ws={} month={}）：subtotal={} adjust={} total={} status={}",
                bill.getBillNo(), tenantId, wholesalerId, month, subtotal, adjust, total, status);
        return bill;
    }

    @Override
    public int autoConfirmDispatched() {
        // 满 1 日自动确认（§3.3；SQL 数据库时间，TIMESTAMPADD 双方言先例）；矩阵合法性前置断言
        DocStateMachine.assertCanGo(DocKind.BILL, Bill.STATUS_DISPATCHED, Bill.STATUS_PENDING_PAYMENT);
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getStatus, Bill.STATUS_DISPATCHED)
                .apply("dispatch_at <= TIMESTAMPADD(DAY, -1, NOW())")
                .set(Bill::getStatus, Bill.STATUS_PENDING_PAYMENT)
                .setSql("confirmed_at = NOW()")
                .set(Bill::getUpdatedAt, LocalDateTime.now()));
        if (affected > 0) {
            log.info("[P4][账单Job] 下发满 1 日自动确认 {} 张 → 待回款", affected);
        }
        return affected;
    }

    // ==================== ST 查询 ====================

    @Override
    public BillListVo listBills(Long userId, String month, Long wholesalerId, String status,
                                int page, int size) {
        Long tenantId = requireStOrTa(userId);
        LambdaQueryWrapper<Bill> where = new LambdaQueryWrapper<Bill>()
                .eq(Bill::getTenantId, tenantId)
                .eq(month != null && !month.isBlank(), Bill::getBillingMonth, month)
                .eq(wholesalerId != null, Bill::getWholesalerId, wholesalerId)
                .eq(status != null && !status.isBlank(), Bill::getStatus, status);
        // 汇总卡（应收/已收/未收合计，按当前筛选）
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        for (Bill b : billMapper.selectList(where)) {
            receivable = receivable.add(b.getTotalAmount());
            received = received.add(b.getPaidAmount());
        }
        Page<Bill> p = billMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                where.clone().orderByDesc(Bill::getBillingMonth).orderByDesc(Bill::getCreatedAt));
        Map<Long, String> nameCache = new HashMap<>();
        List<BillVo> records = p.getRecords().stream()
                .map(b -> toBillVo(b, nameCache))
                .toList();
        return BillListVo.builder()
                .records(records)
                .total(p.getTotal())
                .page(p.getCurrent())
                .size(p.getSize())
                .receivable(scale2(receivable))
                .received(scale2(received))
                .outstanding(scale2(receivable.subtract(received)))
                .build();
    }

    @Override
    public BillDetailVo getBill(Long userId, Long billId) {
        Long tenantId = requireStOrTa(userId);
        return toDetail(requireOwnBill(tenantId, billId));
    }

    @Override
    public List<DailyBreakdownRowVo> dailyBreakdown(Long userId, Long billId) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        // W2 备注 2：下钻端点已落 /st/snapshots/**，此处按账单上下文直接复用（只读快照聚合，05 §5.4）
        return dailySnapshotService.dailyBreakdown(userId, bill.getWholesalerId(),
                bill.getBillingMonth(), null);
    }

    // ==================== ST 操作（§3.3，操作日志 actor_role=ST） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDetailVo adjust(Long userId, Long billId, BillAdjustDto dto) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        assertNotDisputed(bill);
        if (!Bill.STATUS_DRAFT.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_NOT_ADJUSTABLE);
        }
        if (dto == null || dto.getType() == null
                || !("DISCOUNT".equals(dto.getType()) || "RELIEF".equals(dto.getType()))) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "调整类型仅支持折扣/减免");
        }
        BigDecimal amount = requireAmount(dto.getAmount());
        if (dto.getRemark() == null || dto.getRemark().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写调整原因");
        }
        // 调整后应收不得为负（40204，04 §6.2）
        if (bill.getTotalAmount().subtract(amount).signum() < 0) {
            throw new BizException(ErrorCode.VALIDATION_BUSINESS_004, "调整金额超出账单小计");
        }
        BillItem item = new BillItem();
        item.setId(snowflakeIdUtil.nextId());
        item.setTenantId(tenantId);
        item.setBillId(billId);
        item.setItemType(BillItem.TYPE_ADJUSTMENT);
        item.setAmount(amount.negate());
        item.setDescription(("DISCOUNT".equals(dto.getType()) ? "折扣：" : "减免：") + dto.getRemark().trim());
        item.setOperatorUserId(userId);
        billItemMapper.insert(item);
        recomputeAmounts(bill);
        log.info("[P4][账单][actor_role=ST] 用户 {} 调整账单 {}：{} −{}（{}）",
                userId, bill.getBillNo(), dto.getType(), amount, dto.getRemark());
        return toDetail(billMapper.selectById(billId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDetailVo reverseItem(Long userId, Long billId, BillReverseItemDto dto) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        assertNotDisputed(bill);
        if (!Bill.STATUS_DRAFT.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_NOT_ADJUSTABLE);
        }
        if (dto == null || dto.getItemId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请指定要冲销的条目");
        }
        BillItem original = billItemMapper.selectById(dto.getItemId());
        if (original == null || !billId.equals(original.getBillId())) {
            throw new BizException(ErrorCode.BILL_ITEM_NOT_REVERSIBLE);
        }
        // 仅 STORAGE/ADJUSTMENT 条目可冲（REVERSAL/STOCKTAKE_IMPACT 不可）
        if (!BillItem.TYPE_STORAGE.equals(original.getItemType())
                && !BillItem.TYPE_ADJUSTMENT.equals(original.getItemType())) {
            throw new BizException(ErrorCode.BILL_ITEM_NOT_REVERSIBLE);
        }
        // 同一条目只能冲一次
        if (billItemMapper.selectCount(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, billId)
                .eq(BillItem::getReverseOfItemId, original.getId())) > 0) {
            throw new BizException(ErrorCode.BILL_ITEM_NOT_REVERSIBLE);
        }
        BillItem reversal = new BillItem();
        reversal.setId(snowflakeIdUtil.nextId());
        reversal.setTenantId(tenantId);
        reversal.setBillId(billId);
        reversal.setItemType(BillItem.TYPE_REVERSAL);
        reversal.setSkuId(original.getSkuId());
        reversal.setAmount(original.getAmount().negate());
        reversal.setDescription("冲销原条目");
        reversal.setReverseOfItemId(original.getId());
        reversal.setOperatorUserId(userId);
        billItemMapper.insert(reversal);
        recomputeAmounts(bill);
        log.info("[P4][账单][actor_role=ST] 用户 {} 冲销账单 {} 条目 {}（{} {}）",
                userId, bill.getBillNo(), original.getId(), original.getItemType(), original.getAmount());
        return toDetail(billMapper.selectById(billId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillVo dispatch(Long userId, Long billId) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        assertNotDisputed(bill);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(billMapper, DocKind.BILL, billId,
                Bill::getId, Bill::getStatus, bill.getStatus(), Bill.STATUS_DISPATCHED,
                uw -> uw.set(Bill::getDispatchAt, now)
                        .set(Bill::getDispatchUserId, userId)
                        .set(Bill::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // 通知商户管理员（站内信真发 + 短信 mock，零角色码）
        String storeName = storeName(tenantId);
        notifyWaOfBill(bill, Notification.TYPE_BILL_DISPATCHED, "账单已下发",
                storeName + "向您下发了 " + bill.getBillingMonth() + " 月账单（应收 ¥"
                        + plain(bill.getTotalAmount()) + "），请查收并确认对账。");
        log.info("[P4][账单][actor_role=ST] 用户 {} 下发账单 {}（短信通道 mock 发送）", userId, bill.getBillNo());
        return toBillVo(billMapper.selectById(billId), new HashMap<>());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillVo withdraw(Long userId, Long billId) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        assertNotDisputed(bill);
        if (Bill.STATUS_PENDING_PAYMENT.equals(bill.getStatus())
                || Bill.STATUS_PARTIAL_PAID.equals(bill.getStatus())
                || Bill.STATUS_PAID.equals(bill.getStatus())) {
            // 已确认/已有回款一律 50372（矩阵上也不可达，语义码优先）
            throw new BizException(ErrorCode.BILL_NOT_WITHDRAWABLE);
        }
        DocStateMachine.assertCanGo(DocKind.BILL, bill.getStatus(), Bill.STATUS_DRAFT);
        if (bill.getPaidAmount().signum() != 0 || bill.getConfirmedAt() != null) {
            throw new BizException(ErrorCode.BILL_NOT_WITHDRAWABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        // R11 三前置进 WHERE：DISPATCHED ∧ paid=0 ∧ 未确认（并发被确认/回款则失败）
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .eq(Bill::getStatus, Bill.STATUS_DISPATCHED)
                .eq(Bill::getPaidAmount, BigDecimal.ZERO)
                .isNull(Bill::getConfirmedAt)
                .set(Bill::getStatus, Bill.STATUS_DRAFT)
                .set(Bill::getDispatchAt, null)
                .set(Bill::getDispatchUserId, null)
                .set(Bill::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.BILL_NOT_WITHDRAWABLE);
        }
        notifyWaOfBill(bill, Notification.TYPE_BILL_WITHDRAWN, "账单已撤回",
                storeName(tenantId) + "撤回了 " + bill.getBillingMonth() + " 月账单，调整后将重新下发。");
        log.info("[P4][账单][actor_role=ST] 用户 {} 撤回账单 {}（R11）", userId, bill.getBillNo());
        return toBillVo(billMapper.selectById(billId), new HashMap<>());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDetailVo registerPayment(Long userId, Long billId, PaymentRegisterDto dto) {
        Long tenantId = requireStOrTa(userId);
        Bill bill = requireOwnBill(tenantId, billId);
        assertNotDisputed(bill);
        if (Bill.STATUS_PAID.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_ALREADY_SETTLED);
        }
        if (!Bill.STATUS_PENDING_PAYMENT.equals(bill.getStatus())
                && !Bill.STATUS_PARTIAL_PAID.equals(bill.getStatus())) {
            // DRAFT/DISPATCHED 未到回款态（50330 引擎语义复用）
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        if (dto == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        BigDecimal amount = requireAmount(dto.getAmount());
        if (dto.getPayAt() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写收款日期");
        }
        if (dto.getPayMethod() == null || !PAY_METHODS.contains(dto.getPayMethod())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "收款方式无效");
        }
        String evidences = encodeAttachments(dto.getEvidences(), "凭证最多 5 张");
        BigDecimal remaining = bill.getTotalAmount().subtract(bill.getPaidAmount());
        if (amount.compareTo(remaining) > 0) {
            // 不允许超收（平台不做多收挂账）
            throw new BizException(ErrorCode.BILL_PAYMENT_EXCEEDS);
        }
        BigDecimal newPaid = scale2(bill.getPaidAmount().add(amount));
        String newStatus = newPaid.compareTo(bill.getTotalAmount()) == 0
                ? Bill.STATUS_PAID : Bill.STATUS_PARTIAL_PAID;
        if (!newStatus.equals(bill.getStatus())) {
            DocStateMachine.assertCanGo(DocKind.BILL, bill.getStatus(), newStatus);
        }
        // CAS：status + paid_amount 双条件（同单并发回款/R12 恰一方成功；重复登记停留 PARTIAL_PAID 非迁移）
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, billId)
                .eq(Bill::getStatus, bill.getStatus())
                .eq(Bill::getPaidAmount, bill.getPaidAmount())
                .set(Bill::getPaidAmount, newPaid)
                .set(Bill::getStatus, newStatus)
                .set(Bill::getUpdatedAt, LocalDateTime.now()));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        PaymentRecord payment = new PaymentRecord();
        payment.setId(snowflakeIdUtil.nextId());
        payment.setTenantId(tenantId);
        payment.setWholesalerId(bill.getWholesalerId());
        payment.setBillId(billId);
        payment.setAmount(amount);
        payment.setPayAt(dto.getPayAt());
        payment.setPayMethod(dto.getPayMethod());
        payment.setEvidenceUrls(evidences);
        payment.setRemark(dto.getRemark());
        payment.setStatus(PaymentRecord.STATUS_EFFECTIVE);
        payment.setCreatedBy(userId);
        paymentRecordMapper.insert(payment);
        notifyWaOfBill(bill, Notification.TYPE_PAYMENT_REGISTERED, "回款已登记",
                "您的 " + bill.getBillingMonth() + " 月账单登记回款 ¥" + plain(amount)
                        + "，累计已收 ¥" + plain(newPaid) + "／应收 ¥" + plain(bill.getTotalAmount()) + "。");
        log.info("[P4][账单][actor_role=ST] 用户 {} 登记回款 {}（账单 {}，累计 {}/{}，状态 {}）",
                userId, amount, bill.getBillNo(), newPaid, bill.getTotalAmount(), newStatus);
        return toDetail(billMapper.selectById(billId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDetailVo reversePayment(Long userId, Long paymentId, PaymentReverseDto dto) {
        Long tenantId = requireStOrTa(userId);
        PaymentRecord payment = paymentRecordMapper.selectById(paymentId);
        // 不存在/跨租户/已冲销一律 50375（按不存在，不泄漏）
        if (payment == null || !tenantId.equals(payment.getTenantId())
                || !PaymentRecord.STATUS_EFFECTIVE.equals(payment.getStatus())) {
            throw new BizException(ErrorCode.BILL_PAYMENT_NOT_REVERSIBLE);
        }
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写冲销理由");
        }
        if (!Boolean.TRUE.equals(dto.getConfirmed())) {
            // R12 归 ST 二次确认凭据（蓝图 60205 OPS 确认不启用）
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少二次确认凭据");
        }
        Bill bill = billMapper.selectById(payment.getBillId());
        if (bill == null) {
            throw new BizException(ErrorCode.BILL_PAYMENT_NOT_REVERSIBLE);
        }
        assertNotDisputed(bill);
        LocalDateTime now = LocalDateTime.now();
        // CAS：仅 EFFECTIVE 可冲（重复冲销并发恰一方成功，败者 50375）
        int reversed = paymentRecordMapper.update(null, new LambdaUpdateWrapper<PaymentRecord>()
                .eq(PaymentRecord::getId, paymentId)
                .eq(PaymentRecord::getStatus, PaymentRecord.STATUS_EFFECTIVE)
                .set(PaymentRecord::getStatus, PaymentRecord.STATUS_REVERSED)
                .set(PaymentRecord::getReverseReason, dto.getReason().trim())
                .set(PaymentRecord::getReverseUserId, userId)
                .set(PaymentRecord::getReverseAt, now)
                .set(PaymentRecord::getUpdatedAt, now));
        if (reversed != 1) {
            throw new BizException(ErrorCode.BILL_PAYMENT_NOT_REVERSIBLE);
        }
        BigDecimal newPaid = scale2(bill.getPaidAmount().subtract(payment.getAmount()));
        // 状态回退（§3.1）：冲后 paid=0 → 待回款；0<paid<total → 部分回款；否则停留
        String newStatus = newPaid.signum() == 0 ? Bill.STATUS_PENDING_PAYMENT
                : newPaid.compareTo(bill.getTotalAmount()) < 0 ? Bill.STATUS_PARTIAL_PAID
                : bill.getStatus();
        if (!newStatus.equals(bill.getStatus())) {
            DocStateMachine.assertCanGo(DocKind.BILL, bill.getStatus(), newStatus);
        }
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, bill.getId())
                .eq(Bill::getStatus, bill.getStatus())
                .eq(Bill::getPaidAmount, bill.getPaidAmount())
                .set(Bill::getPaidAmount, newPaid)
                .set(Bill::getStatus, newStatus)
                .set(Bill::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        notifyWaOfBill(bill, Notification.TYPE_PAYMENT_REVERSED, "回款已冲销",
                "您的 " + bill.getBillingMonth() + " 月账单一笔回款（¥" + plain(payment.getAmount())
                        + "）已被冲销，原因：" + dto.getReason().trim() + "。");
        log.info("[P4][账单][actor_role=ST] 用户 {} 冲销回款 {}（账单 {}，回减后 {}，状态 {}→{}）",
                userId, paymentId, bill.getBillNo(), newPaid, bill.getStatus(), newStatus);
        return toDetail(billMapper.selectById(bill.getId()));
    }

    @Override
    public List<BillDisputeVo> listDisputes(Long userId, String status) {
        Long tenantId = requireStOrTa(userId);
        boolean pendingQueue = status == null || status.isBlank()
                || BillDispute.STATUS_PENDING.equals(status);
        List<BillDispute> disputes = billDisputeMapper.selectList(new LambdaQueryWrapper<BillDispute>()
                .eq(BillDispute::getTenantId, tenantId)
                .eq(status != null && !status.isBlank(), BillDispute::getStatus, status)
                // PENDING 队列升序先到先处理（先例）；其余倒序
                .orderBy(true, pendingQueue, BillDispute::getCreatedAt));
        Map<Long, Bill> billCache = new HashMap<>();
        Map<Long, String> nameCache = new HashMap<>();
        return disputes.stream().map(d -> toDisputeVo(d, billCache, nameCache)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDisputeVo resolveDispute(Long userId, Long disputeId, BillDisputeResolveDto dto) {
        Long tenantId = requireStOrTa(userId);
        BillDispute dispute = billDisputeMapper.selectById(disputeId);
        // 不存在/跨租户/非 PENDING 一律 50376
        if (dispute == null || !tenantId.equals(dispute.getTenantId())
                || !BillDispute.STATUS_PENDING.equals(dispute.getStatus())) {
            throw new BizException(ErrorCode.BILL_DISPUTE_NOT_PENDING);
        }
        if (dto == null || dto.getConclusion() == null
                || !(BillDispute.STATUS_RESOLVED.equals(dto.getConclusion())
                || BillDispute.STATUS_REJECTED.equals(dto.getConclusion()))) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "处理结论仅支持成立/不成立");
        }
        if (dto.getResolution() == null || dto.getResolution().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写处理说明");
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = billDisputeMapper.update(null, new LambdaUpdateWrapper<BillDispute>()
                .eq(BillDispute::getId, disputeId)
                .eq(BillDispute::getStatus, BillDispute.STATUS_PENDING)
                .set(BillDispute::getStatus, dto.getConclusion())
                .set(BillDispute::getPendingFlag, null)
                .set(BillDispute::getResolution, dto.getResolution().trim())
                .set(BillDispute::getResolverUserId, userId)
                .set(BillDispute::getResolvedAt, now)
                .set(BillDispute::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.BILL_DISPUTE_NOT_PENDING);
        }
        Bill bill = billMapper.selectById(dispute.getBillId());
        if (bill != null) {
            String verdict = BillDispute.STATUS_RESOLVED.equals(dto.getConclusion()) ? "成立" : "不成立";
            notifyWaOfBill(bill, Notification.TYPE_BILL_DISPUTE_RESOLVED, "申诉处理结果",
                    "您对 " + bill.getBillingMonth() + " 月账单的申诉已处理：" + verdict
                            + "。处理说明：" + dto.getResolution().trim());
        }
        log.info("[P4][账单][actor_role=ST] 用户 {} 处理申诉 {}：{}", userId, disputeId, dto.getConclusion());
        return toDisputeVo(billDisputeMapper.selectById(disputeId), new HashMap<>(), new HashMap<>());
    }

    // ==================== WA 侧 ====================

    @Override
    public BillListVo listWaBills(Long userId, String month, String status) {
        List<Long> wsIds = requireWaWholesalerIds(userId);
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .in(Bill::getWholesalerId, wsIds)
                .eq(month != null && !month.isBlank(), Bill::getBillingMonth, month)
                .eq(status != null && !status.isBlank(), Bill::getStatus, status)
                // 仅已下发过的可见（DRAFT/未下发 PAID 零应收单不出现，§3.6 不泄漏）
                .ne(Bill::getStatus, Bill.STATUS_DRAFT)
                .and(w -> w.isNotNull(Bill::getDispatchAt).or().eq(Bill::getStatus, Bill.STATUS_DISPUTED))
                .orderByDesc(Bill::getBillingMonth)
                .orderByDesc(Bill::getCreatedAt));
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        for (Bill b : bills) {
            receivable = receivable.add(b.getTotalAmount());
            received = received.add(b.getPaidAmount());
        }
        Map<Long, String> nameCache = new HashMap<>();
        return BillListVo.builder()
                .records(bills.stream().map(b -> toBillVo(b, nameCache)).toList())
                .total(bills.size())
                .page(1)
                .size(bills.size())
                .receivable(scale2(receivable))
                .received(scale2(received))
                .outstanding(scale2(receivable.subtract(received)))
                .build();
    }

    @Override
    public BillDetailVo getWaBill(Long userId, Long billId) {
        return toDetail(requireWaVisibleBill(userId, billId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillVo confirmByWa(Long userId, Long billId) {
        Bill bill = requireWaVisibleBill(userId, billId);
        assertNotDisputed(bill);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(billMapper, DocKind.BILL, billId,
                Bill::getId, Bill::getStatus, bill.getStatus(), Bill.STATUS_PENDING_PAYMENT,
                uw -> uw.set(Bill::getConfirmedAt, now).set(Bill::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        log.info("[P4][账单] 商户确认对账 {}（用户 {}）→ 待回款", bill.getBillNo(), userId);
        return toBillVo(billMapper.selectById(billId), new HashMap<>());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BillDisputeVo disputeByWa(Long userId, Long billId, BillDisputeSubmitDto dto) {
        Bill bill = requireWaVisibleBill(userId, billId);
        assertNotDisputed(bill);
        if (bill.getDispatchAt() == null) {
            // 未下发过按不存在（不泄漏）
            throw new BizException(ErrorCode.BILL_NOT_FOUND);
        }
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写申诉理由");
        }
        // 窗口=下发后 7 天（SQL 数据库时间，50378）
        if (billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getId, billId)
                .apply("dispatch_at > TIMESTAMPADD(DAY, -" + DISPUTE_WINDOW_DAYS + ", NOW())")) == 0) {
            throw new BizException(ErrorCode.BILL_DISPUTE_WINDOW_CLOSED);
        }
        // 条目须属本账单（50377）
        String disputedItemIds = null;
        if (dto.getDisputedItemIds() != null && !dto.getDisputedItemIds().isEmpty()) {
            Set<Long> itemIds = new LinkedHashSet<>();
            for (String raw : dto.getDisputedItemIds()) {
                try {
                    itemIds.add(Long.valueOf(raw));
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.BILL_DISPUTE_INVALID);
                }
            }
            long owned = billItemMapper.selectCount(new LambdaQueryWrapper<BillItem>()
                    .eq(BillItem::getBillId, billId)
                    .in(BillItem::getId, itemIds));
            if (owned != itemIds.size()) {
                throw new BizException(ErrorCode.BILL_DISPUTE_INVALID);
            }
            disputedItemIds = toJsonArray(itemIds.stream().map(String::valueOf).toList());
        }
        String attachments = encodeAttachments(dto.getAttachments(), "附图最多 5 张");
        // 同账单至多一张 PENDING（先查 + uk_bd_bill_pending 兜底，50382）
        if (billDisputeMapper.selectCount(new LambdaQueryWrapper<BillDispute>()
                .eq(BillDispute::getBillId, billId)
                .eq(BillDispute::getStatus, BillDispute.STATUS_PENDING)) > 0) {
            throw new BizException(ErrorCode.BILL_DISPUTE_PENDING_EXISTS);
        }
        BillDispute dispute = new BillDispute();
        dispute.setId(snowflakeIdUtil.nextId());
        dispute.setTenantId(bill.getTenantId());
        dispute.setWholesalerId(bill.getWholesalerId());
        dispute.setBillId(billId);
        dispute.setSubmitUserId(userId);
        dispute.setReason(dto.getReason().trim());
        dispute.setDisputedItemIds(disputedItemIds);
        dispute.setAttachments(attachments);
        dispute.setStatus(BillDispute.STATUS_PENDING);
        dispute.setPendingFlag(1);
        try {
            billDisputeMapper.insert(dispute);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BILL_DISPUTE_PENDING_EXISTS);
        }
        // 申诉不冻结账单；通知本仓库全部结算员
        String wsName = wholesalerName(bill.getWholesalerId(), new HashMap<>());
        notificationService.sendToAll(bill.getTenantId(),
                authService.listActiveStUserIdsOfTenant(bill.getTenantId()),
                Notification.TYPE_BILL_DISPUTE_SUBMITTED, "账单申诉",
                (wsName != null ? wsName : "商户") + "对 " + bill.getBillingMonth()
                        + " 月账单发起申诉，请及时处理。",
                Notification.REF_BILL_DISPUTE, dispute.getId());
        log.info("[P4][账单] 商户申诉账单 {}（用户 {}，申诉 {}）", bill.getBillNo(), userId, dispute.getId());
        return toDisputeVo(dispute, new HashMap<>(), new HashMap<>());
    }

    // ==================== R13/R14 联动（§3.5） ====================

    @Override
    public void assertAllSettled(Long wholesalerId) {
        long count = countUnsettled(wholesalerId);
        if (count > 0) {
            // O-5 承诺位兑现：存在 status != PAID 账单（含 DISPUTED）不得退驻
            throw new BizException(ErrorCode.WITHDRAW_BILL_NOT_SETTLED);
        }
    }

    @Override
    public long countUnsettled(Long wholesalerId) {
        return billMapper.selectCount(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, wholesalerId)
                .ne(Bill::getStatus, Bill.STATUS_PAID));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markDisputedByWholesaler(Long wholesalerId) {
        // 三态可入 DISPUTED（矩阵合法性前置断言）；DRAFT 不标（未对外）
        List<String> from = List.of(Bill.STATUS_DISPATCHED, Bill.STATUS_PENDING_PAYMENT,
                Bill.STATUS_PARTIAL_PAID);
        from.forEach(s -> DocStateMachine.assertCanGo(DocKind.BILL, s, Bill.STATUS_DISPUTED));
        List<Bill> targets = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWholesalerId, wholesalerId)
                .in(Bill::getStatus, from));
        if (targets.isEmpty()) {
            return 0;
        }
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getWholesalerId, wholesalerId)
                .in(Bill::getStatus, from)
                .set(Bill::getStatus, Bill.STATUS_DISPUTED)
                .set(Bill::getUpdatedAt, LocalDateTime.now()));
        Long tenantId = targets.get(0).getTenantId();
        String wsName = wholesalerName(wholesalerId, new HashMap<>());
        notificationService.sendToAll(tenantId, authService.listActiveStUserIdsOfTenant(tenantId),
                Notification.TYPE_BILL_DISPUTED_MARKED, "账单转入争议中",
                (wsName != null ? wsName : "商户") + "已被强制下架，" + affected
                        + " 张未结账单转入争议中，争议期间账单冻结仅可查看与导出。",
                Notification.REF_BILL, targets.get(0).getId());
        log.info("[P4][账单][R14] 商户 {} 强制下架：{} 张未结账单批量转 DISPUTED", wholesalerId, affected);
        return affected;
    }

    // ==================== 私有：金额/校验/VO ====================

    private String idempotentKey(Long tenantId, Long wholesalerId, YearMonth month) {
        return "bill:" + tenantId + ":" + wholesalerId + ":"
                + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** 金额校验：必填、>0、≤2 位小数（40103 金额格式复用） */
    private BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "请填写金额");
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new BizException(ErrorCode.VALIDATION_FORMAT_003);
        }
        return amount;
    }

    /** 附件编码：≤5 张（50340）+ /files 白名单（AttachmentUrls N2 口径） */
    private String encodeAttachments(List<String> urls, String overflowMessage) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        if (urls.size() > MAX_ATTACHMENTS) {
            throw new BizException(ErrorCode.FILE_UPLOAD_INVALID, overflowMessage);
        }
        return AttachmentUrls.encode(urls);
    }

    /** DISPUTED 位冻结全部写操作（R14，50381；OPS 仲裁闭环 P5） */
    private void assertNotDisputed(Bill bill) {
        if (Bill.STATUS_DISPUTED.equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_DISPUTED_LOCKED);
        }
    }

    /** ST 侧账单归属：不存在/跨租户一律 50370（不泄漏存在性） */
    private Bill requireOwnBill(Long tenantId, Long billId) {
        Bill bill = billId != null ? billMapper.selectById(billId) : null;
        if (bill == null || !tenantId.equals(bill.getTenantId())) {
            throw new BizException(ErrorCode.BILL_NOT_FOUND);
        }
        return bill;
    }

    /** WA 侧账单可见性：本人商户 ∧ 已下发过（DRAFT/未下发按不存在 50370；DISPUTED 保留知情权可见） */
    private Bill requireWaVisibleBill(Long userId, Long billId) {
        List<Long> wsIds = requireWaWholesalerIds(userId);
        Bill bill = billId != null ? billMapper.selectById(billId) : null;
        if (bill == null || !wsIds.contains(bill.getWholesalerId())
                || Bill.STATUS_DRAFT.equals(bill.getStatus())
                || (bill.getDispatchAt() == null && !Bill.STATUS_DISPUTED.equals(bill.getStatus()))) {
            throw new BizException(ErrorCode.BILL_NOT_FOUND);
        }
        return bill;
    }

    /** 重算三金额（恒等式）：REVERSAL 按被冲销条目类型归桶；CAS 限 DRAFT（冲/调仅草稿态） */
    private void recomputeAmounts(Bill bill) {
        List<BillItem> items = billItemMapper.selectList(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, bill.getId()));
        Map<Long, BillItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.getId(), i));
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal adjust = BigDecimal.ZERO;
        for (BillItem item : items) {
            switch (item.getItemType()) {
                case BillItem.TYPE_STORAGE -> subtotal = subtotal.add(item.getAmount());
                case BillItem.TYPE_ADJUSTMENT -> adjust = adjust.add(item.getAmount());
                case BillItem.TYPE_REVERSAL -> {
                    BillItem original = byId.get(item.getReverseOfItemId());
                    if (original != null && BillItem.TYPE_STORAGE.equals(original.getItemType())) {
                        subtotal = subtotal.add(item.getAmount());
                    } else {
                        adjust = adjust.add(item.getAmount());
                    }
                }
                default -> { /* STOCKTAKE_IMPACT 恒 0 不参与 */ }
            }
        }
        BigDecimal total = scale2(subtotal.add(adjust));
        if (total.signum() < 0) {
            throw new BizException(ErrorCode.VALIDATION_BUSINESS_004, "调整金额超出账单小计");
        }
        int affected = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, bill.getId())
                .eq(Bill::getStatus, Bill.STATUS_DRAFT)
                .set(Bill::getSubtotalAmount, scale2(subtotal))
                .set(Bill::getAdjustAmount, scale2(adjust))
                .set(Bill::getTotalAmount, total)
                .set(Bill::getUpdatedAt, LocalDateTime.now()));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
    }

    /** 通知 WA 管理员全员（多账号全发去重；收件人缺失静默降级不阻断主链） */
    private void notifyWaOfBill(Bill bill, String type, String title, String content) {
        notificationService.sendToAll(bill.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(bill.getWholesalerId()),
                type, title, content, Notification.REF_BILL, bill.getId());
    }

    private void notifyBillsGenerated(Long tenantId, YearMonth month, int count) {
        try {
            notificationService.sendToAll(tenantId, authService.listActiveStUserIdsOfTenant(tenantId),
                    Notification.TYPE_BILL_GENERATED, "月账单已生成",
                    month + " 月账单已生成，共 " + count + " 张，请及时核对下发。",
                    Notification.REF_BILL, null);
        } catch (Exception e) {
            // 通知失败不阻断出账主链
            log.error("[P4][账单] 生成通知发送失败 tenant={} month={}", tenantId, month, e);
        }
    }

    private String storeName(Long tenantId) {
        String name = tenantService.getTenantName(tenantId);
        return name != null ? name : "您入驻的仓库";
    }

    private String wholesalerName(Long wholesalerId, Map<Long, String> cache) {
        return cache.computeIfAbsent(wholesalerId, id -> {
            WholesalerVo ws = wholesalerService.getById(id);
            return ws != null ? ws.getName() : null;
        });
    }

    private List<Long> requireWaWholesalerIds(Long userId) {
        List<Long> ids = authService.listActiveWholesalerIds(userId, "WA");
        if (ids.isEmpty()) {
            if (authService.hasRole(userId, "WE")) {
                // WE 对账单整域拒绝（WEM-S4-03 防回归）
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅批发商管理员可查看账单");
        }
        return ids;
    }

    /** 读写 gate：ST 或 TA 兼岗（billing 域 gate，W1/W2 同构）；WE 42004、其余 42001 */
    private Long requireStOrTa(Long userId) {
        Long tenantId = authService.findBoundTenantId(userId, "TA");
        if (tenantId == null) {
            tenantId = authService.findBoundTenantId(userId, "ST");
        }
        if (tenantId == null) {
            if (authService.hasRole(userId, "WE")) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004);
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅仓库管理员或结算员可操作账单");
        }
        return tenantId;
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

    private static BigDecimal scale2(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v != null ? v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
    }

    /** 简易 JSON 数组编码（纯数字字符串 id，无转义需求） */
    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static List<String> fromJsonArray(String json) {
        return AttachmentUrls.decode(json);
    }

    private BillVo toBillVo(Bill bill, Map<Long, String> nameCache) {
        return BillVo.builder()
                .id(bill.getId().toString())
                .billNo(bill.getBillNo())
                .wholesalerId(bill.getWholesalerId().toString())
                .wholesalerName(wholesalerName(bill.getWholesalerId(), nameCache))
                .billingMonth(bill.getBillingMonth())
                .periodStart(bill.getPeriodStart())
                .periodEnd(bill.getPeriodEnd())
                .subtotalAmount(bill.getSubtotalAmount())
                .adjustAmount(bill.getAdjustAmount())
                .totalAmount(bill.getTotalAmount())
                .paidAmount(bill.getPaidAmount())
                .outstandingAmount(scale2(bill.getTotalAmount().subtract(bill.getPaidAmount())))
                .status(bill.getStatus())
                .dispatchAt(bill.getDispatchAt())
                .confirmedAt(bill.getConfirmedAt())
                .createdAt(bill.getCreatedAt())
                .build();
    }

    private BillDetailVo toDetail(Bill bill) {
        List<BillItem> items = billItemMapper.selectList(new LambdaQueryWrapper<BillItem>()
                .eq(BillItem::getBillId, bill.getId())
                .orderByAsc(BillItem::getId));
        Set<Long> reversedIds = new LinkedHashSet<>();
        items.stream().map(BillItem::getReverseOfItemId).filter(java.util.Objects::nonNull)
                .forEach(reversedIds::add);
        Map<Long, String> skuNameCache = new HashMap<>();
        List<BillItemVo> itemVos = items.stream().map(i -> BillItemVo.builder()
                .id(i.getId().toString())
                .itemType(i.getItemType())
                .skuId(i.getSkuId() != null ? i.getSkuId().toString() : null)
                .skuName(i.getSkuId() != null ? skuName(i.getSkuId(), skuNameCache) : null)
                .periodStart(i.getPeriodStart())
                .periodEnd(i.getPeriodEnd())
                .qtyDays(i.getQtyDays())
                .palletDays(i.getPalletDays())
                .unitPriceQty(i.getUnitPriceQty())
                .unitPricePallet(i.getUnitPricePallet())
                .amount(i.getAmount())
                .description(i.getDescription())
                .reverseOfItemId(i.getReverseOfItemId() != null ? i.getReverseOfItemId().toString() : null)
                .reversed(reversedIds.contains(i.getId()))
                .build()).toList();
        List<PaymentRecordVo> paymentVos = paymentRecordMapper.selectList(
                        new LambdaQueryWrapper<PaymentRecord>()
                                .eq(PaymentRecord::getBillId, bill.getId())
                                .orderByAsc(PaymentRecord::getCreatedAt)
                                .orderByAsc(PaymentRecord::getId)).stream()
                .map(pr -> PaymentRecordVo.builder()
                        .id(pr.getId().toString())
                        .billId(pr.getBillId().toString())
                        .amount(pr.getAmount())
                        .payAt(pr.getPayAt())
                        .payMethod(pr.getPayMethod())
                        .evidences(fromJsonArray(pr.getEvidenceUrls()))
                        .remark(pr.getRemark())
                        .status(pr.getStatus())
                        .reverseReason(pr.getReverseReason())
                        .reverseAt(pr.getReverseAt())
                        .createdAt(pr.getCreatedAt())
                        .build()).toList();
        Map<Long, Bill> billCache = new HashMap<>();
        billCache.put(bill.getId(), bill);
        Map<Long, String> nameCache = new HashMap<>();
        List<BillDisputeVo> disputeVos = billDisputeMapper.selectList(
                        new LambdaQueryWrapper<BillDispute>()
                                .eq(BillDispute::getBillId, bill.getId())
                                .orderByAsc(BillDispute::getCreatedAt)
                                .orderByAsc(BillDispute::getId)).stream()
                .map(d -> toDisputeVo(d, billCache, nameCache)).toList();
        return BillDetailVo.builder()
                .bill(toBillVo(bill, nameCache))
                .items(itemVos)
                .payments(paymentVos)
                .disputes(disputeVos)
                .build();
    }

    private String skuName(Long skuId, Map<Long, String> cache) {
        return cache.computeIfAbsent(skuId, id -> {
            SkuVo sku = skuService.getById(id);
            return sku != null ? sku.getName() : null;
        });
    }

    private BillDisputeVo toDisputeVo(BillDispute d, Map<Long, Bill> billCache,
                                      Map<Long, String> nameCache) {
        Bill bill = billCache.computeIfAbsent(d.getBillId(), billMapper::selectById);
        return BillDisputeVo.builder()
                .id(d.getId().toString())
                .billId(d.getBillId().toString())
                .billNo(bill != null ? bill.getBillNo() : null)
                .wholesalerName(wholesalerName(d.getWholesalerId(), nameCache))
                .reason(d.getReason())
                .disputedItemIds(fromJsonArray(d.getDisputedItemIds()))
                .attachments(fromJsonArray(d.getAttachments()))
                .status(d.getStatus())
                .resolution(d.getResolution())
                .resolvedAt(d.getResolvedAt())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
