package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.dto.OutboundComplainDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InquiryItem;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.ArbitrationMapper;
import com.cangchu.document.mapper.InquiryItemMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.ArbitrationVo;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 BE-W2 出库状态机 + 出库异常链场景测试（12 §7 BE-W2 测试关卡）。
 *
 * <p>覆盖（沿用 InboundDisputeChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>迁移矩阵逐格断言（12 §1.2，含全部不可达 50330 + 终态红线）。</li>
 *   <li>主链：WA 提交/询价确认 → PENDING_ACCEPT → 打印（补打 count++）→ 回退 → 登记出库
 *       → COMPLETED + 询价终态联动（12 §1.4）。</li>
 *   <li>R4 两路：待受理直撤（WITHDRAWN+回补配对）/ 已打印 flag→WK confirm/reject（12 §3.1）；
 *       并发撤回与登记 vs 确认撤回竞态（Java21 虚拟线程，CAS 唯一赢家）。</li>
 *   <li>R8 作废联动整单（VOIDED + 逐张 CANCELLED + 每张一条 OUTBOUND_REVERSAL，12 §3.2）。</li>
 *   <li>代建出库大额 50% 边界（50338）+ R14（50313）。</li>
 *   <li>30 天客诉窗口边界（29d23h/30d1h，50339）+ OPS 四选裁决（remark 必填/liability 必空/一单一诉）。</li>
 *   <li>R13 未结单据出口 + 库存公式不变量对账（12 §0）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class OutboundChainScenarioTest {

    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private ArbitrationService arbitrationService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;
    @Autowired
    private InquiryItemMapper inquiryItemMapper;
    @Autowired
    private ArbitrationMapper arbitrationMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed 工具（对齐 InboundDisputeChainScenarioTest） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId,
                       long skuId, long wkUserId, long opsUserId) {
    }

    private Ctx seedAll() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhone("1" + String.format("%010d", tenantId % 10_000_000_000L));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        seedRole(taUserId, "TA", tenantId, null);

        long waUserId = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(waUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        seedRole(waUserId, "WA", tenantId, w.getId());

        long skuId = seedSku(tenantId, w.getId());

        long wkUserId = snowflakeIdUtil.nextId();
        seedRole(wkUserId, "WK", tenantId, null);
        long opsUserId = snowflakeIdUtil.nextId();
        seedRole(opsUserId, "OPS", null, null);
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, skuId, wkUserId, opsUserId);
    }

    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

    private long seedRole(Long userId, String role, Long tenantId, Long wholesalerId) {
        long uid = userId != null ? userId : snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(uid);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        userRoleMapper.insert(r);
        return uid;
    }

    private void seedStock(Ctx c, long skuId, int qty) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(skuId)
                .qty(qty).palletQty(0).refDocNo("WK-SEED-" + snowflakeIdUtil.nextId())
                .operatorUserId(c.wkUserId()).build());
    }

    /** 直插询价单+明细（PENDING）——R4/R8 联动测试免 store 依赖（confirmByWa 不查 store）。 */
    private long seedInquiry(Ctx c, long[] skuIds, int[] qtys) {
        InquiryRequest req = new InquiryRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo("XJ-TEST-" + snowflakeIdUtil.nextId());
        req.setStoreId(snowflakeIdUtil.nextId());
        req.setTenantId(c.tenantId());
        req.setWholesalerId(c.wholesalerId());
        req.setStatus(InquiryRequest.STATUS_PENDING);
        req.setRtPhone("13800009999");
        inquiryRequestMapper.insert(req);
        for (int i = 0; i < skuIds.length; i++) {
            InquiryItem item = new InquiryItem();
            item.setId(snowflakeIdUtil.nextId());
            item.setInquiryId(req.getId());
            item.setSkuId(skuIds[i]);
            item.setQty(qtys[i]);
            item.setUnitPriceSnapshot(new BigDecimal("9.90"));
            item.setMoqPriceSnapshot(new BigDecimal("8.50"));
            item.setMoqQtySnapshot(10);
            item.setDealPrice(new BigDecimal("9.90"));
            inquiryItemMapper.insert(item);
        }
        return req.getId();
    }

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asOps() {
        // OPS 平台态：无租户上下文（跨租户，TenantLine 不注入先例）
        TenantContext.clear();
    }

    private OutboundRequestVo submit(Ctx c, int qty) {
        asWa(c);
        OutboundSubmitDto d = new OutboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        return outboundRequestService.submitByWa(d, c.waUserId());
    }

    private OutboundRequestVo proxy(Ctx c, int qty, Boolean confirmed, Integer restated) {
        asWk(c);
        WkOutboundCreateDto d = new WkOutboundCreateDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setConfirmed(confirmed);
        d.setRestatedQty(restated);
        return outboundRequestService.createByWk(d, c.wkUserId());
    }

    private OutboundComplainDto complainDto(String reason) {
        OutboundComplainDto d = new OutboundComplainDto();
        d.setReason(reason);
        return d;
    }

    private ArbitrationDecideDto decideDto(String conclusion, String remark, String liability) {
        ArbitrationDecideDto d = new ArbitrationDecideDto();
        d.setConclusion(conclusion);
        d.setRemark(remark);
        d.setLiability(liability);
        return d;
    }

    private int qtyOf(Ctx c, long skuId) {
        var list = inventoryService.queryInventory(c.wholesalerId(), skuId);
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private List<StockMovement> movements(Ctx c, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(type != null, StockMovement::getType, type));
    }

    private OutboundRequest doc(long id) {
        return outboundRequestMapper.selectById(id);
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    /** 12 §0 全链不变量：qty = ΣIN − ΣOUT + ΣOUT_REV − ΣDIS_REV + ΣDIS_RST（按 sku 分账）。 */
    private void assertInvariant(Ctx c, long skuId) {
        long in = 0, out = 0, outRev = 0, disRev = 0, disRst = 0;
        for (StockMovement m : movements(c, null)) {
            if (!m.getSkuId().equals(skuId)) {
                continue;
            }
            switch (m.getType()) {
                case StockMovement.TYPE_INBOUND -> in += m.getQty();
                case StockMovement.TYPE_OUTBOUND -> out += m.getQty();
                case StockMovement.TYPE_OUTBOUND_REVERSAL -> outRev += m.getQty();
                case StockMovement.TYPE_DISPUTE_REVERSAL -> disRev += m.getQty();
                case StockMovement.TYPE_DISPUTE_RESTORE -> disRst += m.getQty();
                default -> throw new IllegalStateException("未知流水类型 " + m.getType());
            }
        }
        long expected = in - out + outRev - disRev + disRst;
        assertThat((long) qtyOf(c, skuId)).as("库存公式不变量").isEqualTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(0);
    }

    /** 断言撤回/撤销单必有配对回补流水（reversal_of_id 指向同单 OUTBOUND，biz_time 沿用原锚点）。 */
    private void assertReversalPaired(Ctx c, String docNo) {
        StockMovement original = stockMovementMapper.selectOne(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, docNo)
                .eq(StockMovement::getType, StockMovement.TYPE_OUTBOUND));
        List<StockMovement> reversals = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, docNo)
                .eq(StockMovement::getType, StockMovement.TYPE_OUTBOUND_REVERSAL));
        assertThat(reversals).as("撤回必配一条 OUTBOUND_REVERSAL").hasSize(1);
        assertThat(reversals.get(0).getReversalOfId()).as("reversal_of_id 非空配对").isEqualTo(original.getId());
        assertThat(reversals.get(0).getBizTime()).as("biz_time 沿用原 OUTBOUND 锚点").isEqualTo(original.getBizTime());
        assertThat(reversals.get(0).getQty()).isEqualTo(original.getQty());
    }

    // ======================================================================
    // 迁移矩阵逐格断言（12 §1.2 / §7 关卡）
    // ======================================================================

    @Test
    @DisplayName("P3-SM-01 出库迁移矩阵逐格断言：合法 9 格可达，其余全部 50330（含终态红线/已出库→撤销❌/已撤销→已出库❌）")
    void outboundMatrixEveryCell() {
        List<String> all = List.of(
                OutboundRequest.STATUS_PENDING_ACCEPT, OutboundRequest.STATUS_PRINTED,
                OutboundRequest.STATUS_COMPLETED, OutboundRequest.STATUS_WITHDRAWN,
                OutboundRequest.STATUS_CANCELLED, OutboundRequest.STATUS_COMPLAINED);
        Map<String, Set<String>> legal = Map.of(
                OutboundRequest.STATUS_PENDING_ACCEPT, Set.of(
                        OutboundRequest.STATUS_PRINTED, OutboundRequest.STATUS_WITHDRAWN,
                        OutboundRequest.STATUS_CANCELLED),
                OutboundRequest.STATUS_PRINTED, Set.of(
                        OutboundRequest.STATUS_PENDING_ACCEPT, OutboundRequest.STATUS_COMPLETED,
                        OutboundRequest.STATUS_CANCELLED),
                OutboundRequest.STATUS_COMPLETED, Set.of(OutboundRequest.STATUS_COMPLAINED),
                OutboundRequest.STATUS_COMPLAINED, Set.of(OutboundRequest.STATUS_COMPLETED),
                OutboundRequest.STATUS_WITHDRAWN, Set.of(),
                OutboundRequest.STATUS_CANCELLED, Set.of());
        for (String from : all) {
            for (String to : all) {
                boolean expect = legal.get(from).contains(to);
                assertThat(DocStateMachine.canGo(DocKind.OUTBOUND, from, to))
                        .as("%s -> %s", from, to).isEqualTo(expect);
                if (!expect) {
                    BizException ex = Assertions.assertThrows(BizException.class,
                            () -> DocStateMachine.assertCanGo(DocKind.OUTBOUND, from, to));
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
                }
            }
        }
        // 入库矩阵（12 §2.1 冻结表）抽查：合法 4 格 + 终态红线
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "PENDING_WA_CONFIRM", "CONFIRMED")).isTrue();
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "PENDING_WA_CONFIRM", "DISPUTED")).isTrue();
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "DISPUTED", "CONFIRMED")).isTrue();
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "DISPUTED", "REVOKED")).isTrue();
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "CONFIRMED", "DISPUTED")).isFalse();
        assertThat(DocStateMachine.canGo(DocKind.INBOUND, "REVOKED", "CONFIRMED")).isFalse();
    }

    // ======================================================================
    // 主链：提交 → 打印/补打/回退 → 登记出库（12 §1.2/§1.4）
    // ======================================================================

    @Test
    @DisplayName("P3-OUT-01 WA 提交即扣 → 打印(首打时间/补打 count++) → 回退 → 再打 → 登记出库；作业过程库存不动")
    void mainChainSubmitPrintRegister() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 100);

        OutboundRequestVo vo = submit(c, 30);
        assertThat(vo.getStatus()).isEqualTo(OutboundRequest.STATUS_PENDING_ACCEPT);
        assertThat(vo.getSource()).isEqualTo(OutboundRequest.SOURCE_WA_SUBMIT);
        assertThat(vo.getDocNo()).startsWith("CK-");
        assertThat(qtyOf(c, c.skuId())).isEqualTo(70); // 提交瞬间已扣

        // 未打印不可登记（矩阵红线 PENDING_ACCEPT→COMPLETED ❌）
        asWk(c);
        BizException exReg = Assertions.assertThrows(BizException.class,
                () -> outboundRequestService.registerByWk(vo.getId(), c.wkUserId()));
        assertThat(exReg.getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 首打
        OutboundRequestVo printed = outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        assertThat(printed.getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
        assertThat(printed.getPrintedAt()).isNotNull();
        assertThat(printed.getPrintCount()).isEqualTo(1);
        LocalDateTime firstPrintAt = printed.getPrintedAt();

        // 补打：count++ 不迁移状态、printed_at 不覆盖
        OutboundRequestVo reprint = outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        assertThat(reprint.getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
        assertThat(reprint.getPrintCount()).isEqualTo(2);
        assertThat(reprint.getPrintedAt()).isEqualTo(firstPrintAt);

        // 重新核对回退 → 再打 → 登记出库
        OutboundRequestVo reverted = outboundRequestService.revertToPendingByWk(vo.getId(), c.wkUserId());
        assertThat(reverted.getStatus()).isEqualTo(OutboundRequest.STATUS_PENDING_ACCEPT);
        outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        OutboundRequestVo done = outboundRequestService.registerByWk(vo.getId(), c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(done.getPrintCount()).isEqualTo(3);

        // 打印/回退/登记均纯作业记录：库存只在提交时扣过一次
        assertThat(qtyOf(c, c.skuId())).isEqualTo(70);
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND)).hasSize(1);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-OUT-02 提交非法参数/越权/库存不足/商户下架：50252/权限/50251 整体回滚/50313（R14）")
    void submitInvalidCases() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 10);

        // qty<=0
        asWa(c);
        OutboundSubmitDto bad = new OutboundSubmitDto();
        bad.setWholesalerId(c.wholesalerId());
        bad.setSkuId(c.skuId());
        bad.setQty(0);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.submitByWa(bad, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_QTY_INVALID);

        // 非该商户 WA（用 WK 用户）→ 越权
        assertThat(Assertions.assertThrows(BizException.class, () -> {
            OutboundSubmitDto d = new OutboundSubmitDto();
            d.setWholesalerId(c.wholesalerId());
            d.setSkuId(c.skuId());
            d.setQty(1);
            outboundRequestService.submitByWa(d, c.wkUserId());
        }).getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);

        // 库存不足 → 50251 且单据一并回滚（无残留 PENDING_ACCEPT 单）
        assertThat(Assertions.assertThrows(BizException.class, () -> submit(c, 999))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        assertThat(outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getWholesalerId, c.wholesalerId()))).isZero();
        assertThat(qtyOf(c, c.skuId())).isEqualTo(10);

        // R14：商户 OFFLINE → 新提交 50313；代建入库同钩子（12 §1.1 三处复用）
        wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, c.wholesalerId()).set(Wholesaler::getStatus, "OFFLINE"));
        assertThat(Assertions.assertThrows(BizException.class, () -> submit(c, 1))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_ACTIVE);
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 1, true, null))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_ACTIVE);
        assertInvariant(c, c.skuId());
    }

    // ======================================================================
    // R4 撤回两路（12 §3.1）
    // ======================================================================

    @Test
    @DisplayName("P3-R4-01 待受理直撤：WITHDRAWN + 回补配对(reversal_of_id/biz_time) + 询价回滚 PENDING + 通知；重复撤回 50335")
    void withdrawPendingAccept() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 50);
        long inquiryId = seedInquiry(c, new long[]{c.skuId()}, new int[]{20});
        asWa(c);
        inquiryService.confirmByWa(inquiryId, null, c.waUserId());
        assertThat(qtyOf(c, c.skuId())).isEqualTo(30);
        OutboundRequest out = outboundRequestMapper.selectOne(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId));
        assertThat(out.getStatus()).isEqualTo(OutboundRequest.STATUS_PENDING_ACCEPT);

        OutboundRequestVo withdrawn = outboundRequestService.withdrawByWa(out.getId(), c.waUserId());
        assertThat(withdrawn.getStatus()).isEqualTo(OutboundRequest.STATUS_WITHDRAWN);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(50); // 已回补
        assertReversalPaired(c, out.getDocNo());
        // 全部出库单已撤 → 询价 CAS CONFIRMED→PENDING 回滚（12 §3.1 联动口径）
        assertThat(inquiryRequestMapper.selectById(inquiryId).getStatus())
                .isEqualTo(InquiryRequest.STATUS_PENDING);
        // 通知仓库侧（询价单无定向 WK → 租户联系人 TA）
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_OUTBOUND_WITHDRAWN)).isEqualTo(1);

        // 终态重复撤回 → 50335
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.withdrawByWa(out.getId(), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NOT_WITHDRAWABLE);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-R4-02 已打印两段式：flag→WK 拒绝→再申请→WK 确认 CANCELLED+回补；重复申请 50331、无 flag 确认 50336")
    void withdrawPrintedTwoPhase() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 40);
        OutboundRequestVo vo = submit(c, 15);
        asWk(c);
        outboundRequestService.printByWk(vo.getId(), c.wkUserId());

        // 无 flag 时 WK 确认/拒绝 → 50336
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.confirmWithdrawByWk(vo.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.rejectWithdrawByWk(vo.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);

        // WA 申请撤回：状态不迁移，flag 置位 + 通知仓库侧
        asWa(c);
        OutboundRequestVo requested = outboundRequestService.withdrawByWa(vo.getId(), c.waUserId());
        assertThat(requested.getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
        assertThat(requested.getWithdrawRequested()).isEqualTo(1);
        assertThat(requested.getWithdrawRequestedAt()).isNotNull();
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_OUTBOUND_WITHDRAW_REQUESTED)).isEqualTo(1);
        // 重复申请 → 50331
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.withdrawByWa(vo.getId(), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_CAS_CONFLICT);

        // WK 拒绝：清 flag、状态不变、通知 WA；库存未动
        asWk(c);
        OutboundRequestVo rejected = outboundRequestService.rejectWithdrawByWk(vo.getId(), c.wkUserId());
        assertThat(rejected.getStatus()).isEqualTo(OutboundRequest.STATUS_PRINTED);
        assertThat(rejected.getWithdrawRequested()).isZero();
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_OUTBOUND_WITHDRAW_REJECTED)).isEqualTo(1);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(25);

        // 再次申请 → WK 确认：PRINTED→CANCELLED + 回补配对 + 通知 WA
        asWa(c);
        outboundRequestService.withdrawByWa(vo.getId(), c.waUserId());
        asWk(c);
        OutboundRequestVo cancelled = outboundRequestService.confirmWithdrawByWk(vo.getId(), c.wkUserId());
        assertThat(cancelled.getStatus()).isEqualTo(OutboundRequest.STATUS_CANCELLED);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(40);
        assertReversalPaired(c, vo.getDocNo());
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_OUTBOUND_WITHDRAWN)).isEqualTo(1);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-R4-03 已出库不可撤（50335，走退货 R5——T3 波）")
    void withdrawCompletedRejected() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 20);
        OutboundRequestVo vo = proxy(c, 5, true, null); // 代建直达 COMPLETED
        asWa(c);
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> outboundRequestService.withdrawByWa(vo.getId(), c.waUserId()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_NOT_WITHDRAWABLE);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(15);
    }

    @Test
    @DisplayName("P3-R4-04 并发双撤回（虚拟线程）：CAS 唯一赢家，恰一条回补流水，库存只回补一次")
    void concurrentWithdraw() throws Exception {
        // W5 抖动稳定化：仅对 H2 内存库连接级偶发故障（"JDBC rollback failed"/connection closed，
        // 历史两次全量跑复现、隔离复跑即绿）做受控重试；断言失败（=业务缺陷）原样抛出，绝不掩盖。
        // 每次重试 seedAll 生成全新雪花 ID 数据，尝试间无状态污染。
        retryOnH2InfraFlake(3, this::concurrentWithdrawOnce);
    }

    private void concurrentWithdrawOnce() throws Exception {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30);
        OutboundRequestVo vo = submit(c, 10);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(20);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> f1 = vt.submit(() -> raceWithdraw(c, vo.getId(), start));
            Future<Object> f2 = vt.submit(() -> raceWithdraw(c, vo.getId(), start));
            start.countDown();
            Object r1 = f1.get(60, TimeUnit.SECONDS);
            Object r2 = f2.get(60, TimeUnit.SECONDS);
            boolean ok1 = r1 instanceof OutboundRequestVo;
            boolean ok2 = r2 instanceof OutboundRequestVo;
            assertThat(ok1 ^ ok2).as("并发撤回恰一方成功: r1=%s r2=%s", r1, r2).isTrue();
            Object loser = ok1 ? r2 : r1;
            assertThat(((BizException) loser).getErrorCode())
                    .isIn(ErrorCode.DOC_STATE_CAS_CONFLICT, ErrorCode.OUTBOUND_NOT_WITHDRAWABLE);
        }
        assertThat(doc(vo.getId()).getStatus()).isEqualTo(OutboundRequest.STATUS_WITHDRAWN);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(30); // 只回补一次
        assertReversalPaired(c, vo.getDocNo());
        assertInvariant(c, c.skuId());
    }

    /**
     * W5 抖动稳定化辅助：受控重试——只认 H2 基建级故障签名（连接关闭/回滚失败/连接断开），
     * 任何 {@link AssertionError}（业务断言失败）与业务异常一律立即抛出，不允许重试洗绿。
     */
    private void retryOnH2InfraFlake(int maxAttempts, ThrowingRunnable body) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                body.run();
                return;
            } catch (Throwable t) {
                if (attempt >= maxAttempts || !isH2InfraFlake(t)) {
                    throw t instanceof Exception e ? e : new IllegalStateException(t);
                }
                System.err.printf("[W5-flake-retry] 第 %d 次尝试命中 H2 基建抖动，重试中：%s%n", attempt, t);
                TenantContext.clear();
                Thread.sleep(200L * attempt);
            }
        }
    }

    /** 逐层遍历 cause 链：遇断言失败直接判否；仅匹配连接级/事务基建故障签名。 */
    private boolean isH2InfraFlake(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AssertionError) {
                return false; // 业务断言失败 = 潜在真缺陷，绝不重试
            }
            String msg = String.valueOf(c.getMessage());
            if (c instanceof org.springframework.transaction.TransactionSystemException
                    || c instanceof org.springframework.dao.DataAccessResourceFailureException
                    || c instanceof org.springframework.transaction.CannotCreateTransactionException
                    || msg.contains("JDBC rollback failed")
                    || msg.contains("JDBC commit failed")
                    || msg.contains("has been closed")           // H2 90098 database has been closed
                    || msg.contains("is already closed")         // H2 90007 object is already closed
                    || msg.contains("Connection is broken")) {   // H2 90067
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private Object raceWithdraw(Ctx c, long outboundId, CountDownLatch start) throws InterruptedException {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
        start.await();
        try {
            return outboundRequestService.withdrawByWa(outboundId, c.waUserId());
        } catch (BizException e) {
            return e;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("P3-R4-05 登记出库 vs 确认撤回竞态（虚拟线程）：恰一方赢——COMPLETED 无回补 / CANCELLED 有回补")
    void registerVsConfirmWithdrawRace() throws Exception {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30);
        OutboundRequestVo vo = submit(c, 10);
        asWk(c);
        outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        asWa(c);
        outboundRequestService.withdrawByWa(vo.getId(), c.waUserId()); // flag=1

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> registerF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return outboundRequestService.registerByWk(vo.getId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Object> confirmF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return outboundRequestService.confirmWithdrawByWk(vo.getId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object regR = registerF.get(60, TimeUnit.SECONDS);
            Object conR = confirmF.get(60, TimeUnit.SECONDS);
            boolean regOk = regR instanceof OutboundRequestVo;
            boolean conOk = conR instanceof OutboundRequestVo;
            assertThat(regOk ^ conOk).as("恰一方成功: reg=%s con=%s", regR, conR).isTrue();

            OutboundRequest cur = doc(vo.getId());
            if (regOk) {
                assertThat(cur.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
                assertThat(movements(c, StockMovement.TYPE_OUTBOUND_REVERSAL)).isEmpty();
                assertThat(qtyOf(c, c.skuId())).isEqualTo(20);
            } else {
                assertThat(cur.getStatus()).isEqualTo(OutboundRequest.STATUS_CANCELLED);
                assertReversalPaired(c, vo.getDocNo());
                assertThat(qtyOf(c, c.skuId())).isEqualTo(30);
            }
        }
        assertInvariant(c, c.skuId());
    }

    // ======================================================================
    // R8 作废联动（12 §3.2）
    // ======================================================================

    @Test
    @DisplayName("P3-R8-01 整单作废：VOIDED + 名下 PENDING_ACCEPT/PRINTED 逐张 CANCELLED，每张一条配对回补，库存全回")
    void voidChain() {
        Ctx c = seedAll();
        long sku2 = seedSku(c.tenantId(), c.wholesalerId());
        seedStock(c, c.skuId(), 50);
        seedStock(c, sku2, 50);
        long inquiryId = seedInquiry(c, new long[]{c.skuId(), sku2}, new int[]{10, 20});
        asWa(c);
        inquiryService.confirmByWa(inquiryId, null, c.waUserId());
        assertThat(qtyOf(c, c.skuId())).isEqualTo(40);
        assertThat(qtyOf(c, sku2)).isEqualTo(30);
        List<OutboundRequest> outs = outboundRequestMapper.selectList(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId));
        assertThat(outs).hasSize(2);
        // 其中一张已打印（R8 下不需 WK 二次确认）
        asWk(c);
        outboundRequestService.printByWk(outs.get(0).getId(), c.wkUserId());

        asWa(c);
        var voided = inquiryService.voidByWa(inquiryId, c.waUserId());
        assertThat(voided.getStatus()).isEqualTo(InquiryRequest.STATUS_VOIDED);
        assertThat(voided.getVoidedAt()).isNotNull();
        for (OutboundRequest out : outs) {
            assertThat(doc(out.getId()).getStatus()).isEqualTo(OutboundRequest.STATUS_CANCELLED);
            assertReversalPaired(c, out.getDocNo());
        }
        assertThat(qtyOf(c, c.skuId())).isEqualTo(50);
        assertThat(qtyOf(c, sku2)).isEqualTo(50);
        // 通知 WK（租户联系人，含收回纸单提示）
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_INQUIRY_VOIDED)).isEqualTo(1);
        assertInvariant(c, c.skuId());
        assertInvariant(c, sku2);
    }

    @Test
    @DisplayName("P3-R8-02 作废前置：存在已出库单 50337；PENDING 询价 50337；作废后重复作废 50337")
    void voidBlocked() {
        Ctx c = seedAll();
        long sku2 = seedSku(c.tenantId(), c.wholesalerId());
        seedStock(c, c.skuId(), 50);
        seedStock(c, sku2, 50);
        long inquiryId = seedInquiry(c, new long[]{c.skuId(), sku2}, new int[]{5, 6});
        asWa(c);
        inquiryService.confirmByWa(inquiryId, null, c.waUserId());
        List<OutboundRequest> outs = outboundRequestMapper.selectList(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId));
        // 一张走完（打印→登记出库）
        asWk(c);
        outboundRequestService.printByWk(outs.get(0).getId(), c.wkUserId());
        outboundRequestService.registerByWk(outs.get(0).getId(), c.wkUserId());

        asWa(c);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> inquiryService.voidByWa(inquiryId, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INQUIRY_NOT_VOIDABLE);

        // PENDING 询价不可作废
        long pendingInquiry = seedInquiry(c, new long[]{c.skuId()}, new int[]{1});
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> inquiryService.voidByWa(pendingInquiry, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.INQUIRY_NOT_VOIDABLE);
    }

    // ======================================================================
    // 代建出库 + 大额边界（12 §3.3）
    // ======================================================================

    @Test
    @DisplayName("P3-WK-01 代建出库：直达 COMPLETED/WK_CREATED + 扣库存 + 通知归属 WA；缺二次确认 50338；库存不足 50251")
    void proxyOutbound() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 20);

        // confirmed 缺省/false → 50338
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 1, null, null))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED);
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 1, false, null))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED);

        OutboundRequestVo vo = proxy(c, 8, true, null);
        assertThat(vo.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(vo.getSource()).isEqualTo(OutboundRequest.SOURCE_WK_CREATED);
        assertThat(vo.getCompletedAt()).isNotNull();
        assertThat(vo.getWkUserId()).isEqualTo(c.wkUserId());
        assertThat(qtyOf(c, c.skuId())).isEqualTo(12);
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_OUTBOUND_PROXY_CREATED)).isEqualTo(1);

        // 库存不足 → 50251（超大额同时缺复述会先触发 50338，此处带复述直达库存校验）
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 999, true, 999))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-WK-02 大额 50% 边界：qty=onhand/2 放行；qty>50% 未复述/复述不等 50338；复述一致放行")
    void proxyLargeAmountBoundary() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 100);

        // 边界内：50×2=100 不> 100 → 非大额，免复述
        assertThat(proxy(c, 50, true, null).getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(50);

        // 大额：26×2=52 > 50 → 未复述 50338；复述不等 50338
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 26, true, null))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED);
        assertThat(Assertions.assertThrows(BizException.class, () -> proxy(c, 26, true, 25))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED);
        // 复述一致 → 放行
        assertThat(proxy(c, 26, true, 26).getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(24);
        assertInvariant(c, c.skuId());
    }

    // ======================================================================
    // 30 天客诉 + OPS 仲裁（12 §3.4 / PRD 09 §3）
    // ======================================================================

    @Test
    @DisplayName("P3-KS-01 客诉前置：非代建 50330 / 未出库 50330；成功 → COMPLAINED + KS- 仲裁单 + 通知 WK")
    void complainPreconditionsAndCreate() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 60);

        // 非代建（询价链走完 COMPLETED 的 INQUIRY_AUTO 单）→ 50330
        long inquiryId = seedInquiry(c, new long[]{c.skuId()}, new int[]{5});
        asWa(c);
        inquiryService.confirmByWa(inquiryId, null, c.waUserId());
        OutboundRequest inqOut = outboundRequestMapper.selectOne(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId));
        asWk(c);
        outboundRequestService.printByWk(inqOut.getId(), c.wkUserId());
        outboundRequestService.registerByWk(inqOut.getId(), c.wkUserId());
        asWa(c);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.complainByWa(inqOut.getId(), c.waUserId(), complainDto("非代建")))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 未出库（WA_SUBMIT PENDING_ACCEPT）→ 50330（source 也非代建，双前置皆不满足）
        OutboundRequestVo pending = submit(c, 3);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.complainByWa(pending.getId(), c.waUserId(), complainDto("未出库")))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 成功：代建单 → COMPLAINED + KS- 仲裁单 PENDING + 通知登记 WK
        OutboundRequestVo vo = proxy(c, 10, true, null);
        asWa(c);
        OutboundRequestVo complained = outboundRequestService.complainByWa(vo.getId(), c.waUserId(),
                complainDto("代建出库件数与实收不符"));
        assertThat(complained.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLAINED);
        Arbitration arb = arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, vo.getId()));
        assertThat(arb.getDocNo()).startsWith("KS-");
        assertThat(arb.getBizType()).isEqualTo(Arbitration.BIZ_OUTBOUND_COMPLAINT);
        assertThat(arb.getRefDocType()).isEqualTo(Arbitration.REF_OUTBOUND);
        assertThat(arb.getRefDocNo()).isEqualTo(vo.getDocNo());
        assertThat(arb.getStatus()).isEqualTo(Arbitration.STATUS_PENDING);
        assertThat(arb.getInitiatorRole()).isEqualTo("WA");
        assertThat(arb.getReversedQty()).isNull(); // 客诉不动库存，无冲销字段
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_COMPLAINT_CREATED)).isEqualTo(1);
        // 客诉不动库存
        assertThat(qtyOf(c, c.skuId())).isEqualTo(60 - 5 - 3 - 10);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-KS-02 30 天窗口边界：29d23h 可诉 / 30d1h → 50339")
    void complaintWindowBoundary() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30);
        OutboundRequestVo inWindow = proxy(c, 3, true, null);
        OutboundRequestVo outWindow = proxy(c, 4, true, null);
        // 直接操控 completed_at 锚点（Job/窗口测试先例：回拨时间列）
        outboundRequestMapper.update(null, new LambdaUpdateWrapper<OutboundRequest>()
                .eq(OutboundRequest::getId, inWindow.getId())
                .set(OutboundRequest::getCompletedAt, LocalDateTime.now().minusDays(29).minusHours(23)));
        outboundRequestMapper.update(null, new LambdaUpdateWrapper<OutboundRequest>()
                .eq(OutboundRequest::getId, outWindow.getId())
                .set(OutboundRequest::getCompletedAt, LocalDateTime.now().minusDays(30).minusHours(1)));

        asWa(c);
        assertThat(outboundRequestService.complainByWa(inWindow.getId(), c.waUserId(), complainDto("窗口内"))
                .getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLAINED);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.complainByWa(outWindow.getId(), c.waUserId(), complainDto("超窗")))
                .getErrorCode()).isEqualTo(ErrorCode.OUTBOUND_COMPLAINT_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("P3-KS-03 OPS 裁决：四选+remark 必填(50333)/liability 必空(50342)/非 OPS 拒绝；裁决后回 COMPLETED 不动库存；双裁 50334；一单一诉 50330")
    void opsDecideChain() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30);
        OutboundRequestVo vo = proxy(c, 10, true, null);
        int stockAfterProxy = qtyOf(c, c.skuId());
        asWa(c);
        outboundRequestService.complainByWa(vo.getId(), c.waUserId(), complainDto("少 2 件"));
        Arbitration arb = arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, vo.getId()));

        asOps();
        // 非 OPS（WA 用户）→ 拒绝
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByOps(arb.getId(), c.waUserId(),
                                decideDto(Arbitration.WK_LIABLE, "备注", null)))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_002);
        // 结论与类型错配（入库二选用于客诉）→ 50333
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                                decideDto(Arbitration.CONCLUSION_APPROVED, "备注", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        // remark 缺失 → 50333（PRD 09 §1.1 结论备注必填）
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                                decideDto(Arbitration.WK_LIABLE, "  ", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        // liability 传入 → 50342（差额定责仅入库异议适用）
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                                decideDto(Arbitration.WK_LIABLE, "备注", Arbitration.WK_LIABLE)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_LIABILITY_INVALID);

        // 合法裁决：WK_LIABLE → DECIDED + 出库单 COMPLAINED→COMPLETED + 双方通知；库存/流水不动（D43）
        ArbitrationVo decided = arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                decideDto(Arbitration.WK_LIABLE, "核实仓库登记误差", null));
        assertThat(decided.getStatus()).isEqualTo(Arbitration.STATUS_DECIDED);
        assertThat(decided.getConclusion()).isEqualTo(Arbitration.WK_LIABLE);
        assertThat(decided.getArbitratorUserId()).isEqualTo(c.opsUserId());
        assertThat(doc(vo.getId()).getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(stockAfterProxy);
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND_REVERSAL)).isEmpty();
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);

        // 双裁 → 50334
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                                decideDto(Arbitration.NO_LIABILITY, "翻案", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_NOT_PENDING);
        // 一单一诉（已裁决后不可再提，PRD 09 §1.1）→ 50330
        asWa(c);
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> outboundRequestService.complainByWa(vo.getId(), c.waUserId(), complainDto("再诉")))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        assertInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("P3-KS-04 OPS 列表跨租户可见 + bizType 门（入库异议 50333）+ TA 端点不受理客诉（50333）")
    void opsListCrossTenantAndGates() {
        Ctx c1 = seedAll();
        Ctx c2 = seedAll();
        seedStock(c1, c1.skuId(), 10);
        seedStock(c2, c2.skuId(), 10);
        OutboundRequestVo v1 = proxy(c1, 2, true, null);
        OutboundRequestVo v2 = proxy(c2, 3, true, null);
        asWa(c1);
        outboundRequestService.complainByWa(v1.getId(), c1.waUserId(), complainDto("跨租户1"));
        asWa(c2);
        outboundRequestService.complainByWa(v2.getId(), c2.waUserId(), complainDto("跨租户2"));

        asOps();
        var page = arbitrationService.listForOps(c1.opsUserId(), null, Arbitration.STATUS_PENDING, 1, 100);
        List<String> refDocNos = page.getRecords().stream().map(ArbitrationVo::getRefDocNo).toList();
        assertThat(refDocNos).contains(v1.getDocNo(), v2.getDocNo()); // 跨租户全平台可见

        // OPS 端点只受理客诉：bizType=INBOUND_DISPUTE → 50333
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.listForOps(c1.opsUserId(), Arbitration.BIZ_INBOUND_DISPUTE,
                                null, 1, 20))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);

        // TA 端点不受理客诉（biz 门镜像）：TA decideByTa 客诉单 → 50333
        Arbitration arb1 = arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, v1.getId()));
        TenantContext.set(TenantContext.TenantInfo.of(c1.tenantId(), c1.taUserId(), "TA"));
        assertThat(Assertions.assertThrows(BizException.class,
                        () -> arbitrationService.decideByTa(arb1.getId(), c1.taUserId(),
                                decideDto(Arbitration.WK_LIABLE, "备注", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
    }

    // ======================================================================
    // R13 未结单据出口（12 §8.2）
    // ======================================================================

    @Test
    @DisplayName("P3-R13-01 未结口径：PENDING_ACCEPT/PRINTED/COMPLAINED 未结；WITHDRAWN/CANCELLED/COMPLETED 已结；仲裁 PENDING 计数")
    void openDocsCounting() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 60);

        // WA_SUBMIT → PENDING_ACCEPT：出库未结 1
        OutboundRequestVo vo = submit(c, 5);
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 打印 → 仍未结
        asWk(c);
        outboundRequestService.printByWk(vo.getId(), c.wkUserId());
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 登记出库 → 已结
        outboundRequestService.registerByWk(vo.getId(), c.wkUserId());
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();

        // 撤回单已结（回补完成不阻退驻）
        OutboundRequestVo vo2 = submit(c, 5);
        asWa(c);
        outboundRequestService.withdrawByWa(vo2.getId(), c.waUserId());
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();

        // 客诉中：出库未结 1 + 仲裁 PENDING 1；裁决后双双清零
        OutboundRequestVo vo3 = proxy(c, 4, true, null);
        asWa(c);
        outboundRequestService.complainByWa(vo3.getId(), c.waUserId(), complainDto("客诉未结"));
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        assertThat(arbitrationService.countPendingForWholesaler(c.wholesalerId())).isEqualTo(1);
        Arbitration arb = arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, vo3.getId()));
        asOps();
        arbitrationService.decideByOps(arb.getId(), c.opsUserId(),
                decideDto(Arbitration.NO_LIABILITY, "无责结案", null));
        assertThat(outboundRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
        assertThat(arbitrationService.countPendingForWholesaler(c.wholesalerId())).isZero();

        // 询价口径：CONFIRMED（名下有未结出库）计未结；出库单全撤后询价回 PENDING 仍未结；
        long inquiryId = seedInquiry(c, new long[]{c.skuId()}, new int[]{2});
        asWa(c);
        inquiryService.confirmByWa(inquiryId, null, c.waUserId());
        // CONFIRMED(1) + PENDING_ACCEPT(1) = 2
        assertThat(inquiryService.countOpenDocsForWholesaler(c.wholesalerId())).isEqualTo(2);
        OutboundRequest inqOut = outboundRequestMapper.selectOne(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId));
        outboundRequestService.withdrawByWa(inqOut.getId(), c.waUserId());
        // 询价回 PENDING(1)，出库已结(0)
        assertThat(inquiryService.countOpenDocsForWholesaler(c.wholesalerId())).isEqualTo(1);
        assertInvariant(c, c.skuId());
    }
}
