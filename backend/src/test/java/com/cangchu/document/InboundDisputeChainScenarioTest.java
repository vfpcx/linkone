package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.mapper.ArbitrationMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.ArbitrationVo;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.dto.OutboundReversalContext;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 BE-W1 入库异常链场景测试（12 §7 BE-W1 测试关卡）。
 *
 * <p>覆盖（沿用 InboundScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>确认链：WA confirm / WE 授权位（INBOUND_CONFIRM）/ 重复确认 50331。</li>
 *   <li>封顶口径 4 边界：全在库冲销 / 部分售出 / 售罄 0 冲销 / 冲销与出库并发抢锁（Java21 虚拟线程）。</li>
 *   <li>72h Job：幂等 + 与手动确认竞态（CAS 断言）+ 超窗 50332。</li>
 *   <li>仲裁双路：APPROVED 恢复流水 biz_time=原入库时间戳（G10 断言）/ REJECTED 保留冲销 → REVOKED。</li>
 *   <li>liability 三态：必填缺失 / 必空传入 / 枚举非法 → 50342；结论错配 / REJECTED 缺 remark → 50333；双裁 50334。</li>
 *   <li>库存公式不变量对账（12 §0）：qty = ΣIN − ΣOUT + ΣOUT_REV − ΣDIS_REV + ΣDIS_RST。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class InboundDisputeChainScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private ArbitrationService arbitrationService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
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
    private PiiCrypto piiCrypto;
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

    // ==================== seed 工具 ====================

    /** 一套完整业务上下文：tenant(含 TA 联系人) + wholesaler(含 WA owner) + sku + WK/WA 用户。 */
    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId, long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
        // 雪花直取保证 PK/简码/手机号全局唯一（tenants 唯一索引，避免跨用例撞键）
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        seedRole(taUserId, "TA", tenantId, null, null);

        long waUserId = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(waUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        seedRole(waUserId, "WA", tenantId, w.getId(), null);

        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(w.getId());
        s.setName("品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);

        long wkUserId = snowflakeIdUtil.nextId();
        seedRole(wkUserId, "WK", tenantId, null, null);
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, s.getId(), wkUserId);
    }

    private long seedRole(Long userId, String role, Long tenantId, Long wholesalerId, String permissions) {
        long uid = userId != null ? userId : snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(uid);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        r.setPermissions(permissions);
        userRoleMapper.insert(r);
        return uid;
    }

    private InboundRequestVo register(Ctx c, int qty, Integer palletQty) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        InboundRegisterDto d = new InboundRegisterDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(palletQty);
        return inboundRequestService.registerByWk(d, c.wkUserId());
    }

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    private void deduct(Ctx c, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo("CK-TEST").operatorUserId(c.waUserId()).build());
    }

    private InboundDisputeDto disputeDto(String reason) {
        InboundDisputeDto d = new InboundDisputeDto();
        d.setReason(reason);
        return d;
    }

    private int qtyOf(Ctx c) {
        var list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private List<StockMovement> movements(Ctx c, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(type != null, StockMovement::getType, type));
    }

    private Arbitration arbOf(long inboundId) {
        return arbitrationMapper.selectOne(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocId, inboundId));
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    private ArbitrationDecideDto decideDto(String conclusion, String remark, String liability) {
        ArbitrationDecideDto d = new ArbitrationDecideDto();
        d.setConclusion(conclusion);
        d.setRemark(remark);
        d.setLiability(liability);
        return d;
    }

    /** 12 §0 全链不变量：qty = ΣINBOUND − ΣOUTBOUND + ΣOUTBOUND_REVERSAL − ΣDISPUTE_REVERSAL + ΣDISPUTE_RESTORE。 */
    private void assertInvariant(Ctx c) {
        long in = 0, out = 0, outRev = 0, disRev = 0, disRst = 0;
        for (StockMovement m : movements(c, null)) {
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
        assertThat((long) qtyOf(c)).as("库存公式不变量").isEqualTo(expected);
        assertThat(expected).isGreaterThanOrEqualTo(0);
    }

    // ======================================================================
    // 确认链
    // ======================================================================

    @Test
    @DisplayName("P3-CONF-01 WA 确认 → CONFIRMED(auto_accepted=0)；重复确认 → 50331；通知 WA 已写入")
    void confirmByWa() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 2);
        // 登记同事务通知归属 WA
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_PENDING_CONFIRM)).isEqualTo(1);

        asWa(c);
        InboundRequestVo confirmed = inboundRequestService.confirmByWa(vo.getId(), c.waUserId());
        assertThat(confirmed.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
        assertThat(confirmed.getAutoAccepted()).isZero();
        assertThat(confirmed.getWaConfirmAt()).isNotNull();

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.confirmByWa(vo.getId(), c.waUserId()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_CAS_CONFLICT);
    }

    @Test
    @DisplayName("P3-CONF-02 WE 授权位：无 INBOUND_CONFIRM → 42004；有授权 → 确认成功（G7）")
    void wePermissionGate() {
        Ctx c = seedAll();
        long weNoPerm = seedRole(null, "WE", c.tenantId(), c.wholesalerId(), "[\"PRICE_EDIT\"]");
        long weWithPerm = seedRole(null, "WE", c.tenantId(), c.wholesalerId(), "[\"INBOUND_CONFIRM\"]");

        InboundRequestVo vo = register(c, 10, 0);
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weNoPerm, "WE"));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.confirmByWa(vo.getId(), weNoPerm));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_004);

        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weWithPerm, "WE"));
        InboundRequestVo confirmed = inboundRequestService.confirmByWa(vo.getId(), weWithPerm);
        assertThat(confirmed.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
    }

    // ======================================================================
    // 封顶口径 4 边界（12 §2.4）
    // ======================================================================

    @Test
    @DisplayName("P3-CAP-01 全在库：登记30全在库 → 冲销30/差额0，库存0，YY-仲裁单+通知 TA/WK")
    void disputeAllOnhand() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 3);

        asWa(c);
        InboundDisputeResultVo r = inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("未到货"));

        assertThat(r.getReversedQty()).isEqualTo(30);
        assertThat(r.getShortfallQty()).isZero();
        assertThat(r.getArbitrationDocNo()).startsWith("YY-");
        assertThat(qtyOf(c)).isZero();
        assertThat(movements(c, StockMovement.TYPE_DISPUTE_REVERSAL)).hasSize(1);

        InboundRequest cur = inboundRequestMapper.selectById(vo.getId());
        assertThat(cur.getStatus()).isEqualTo(InboundRequest.STATUS_DISPUTED);
        assertThat(cur.getDisputedAt()).isNotNull();

        Arbitration arb = arbOf(vo.getId());
        assertThat(arb.getStatus()).isEqualTo(Arbitration.STATUS_PENDING);
        assertThat(arb.getBizType()).isEqualTo(Arbitration.BIZ_INBOUND_DISPUTE);
        assertThat(arb.getReversedQty()).isEqualTo(30);
        assertThat(arb.getShortfallQty()).isZero();
        assertThat(arb.getRefDocNo()).isEqualTo(vo.getDocNo());
        // 通知 TA（审批中心角标）+ WK
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_DISPUTE_CREATED)).isEqualTo(1);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_DISPUTE_CREATED)).isEqualTo(1);
        assertInvariant(c);
    }

    @Test
    @DisplayName("P3-CAP-02 部分售出：登记30已售20 → 冲销10/差额20（保守归属，库存0）")
    void disputePartialSold() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 3);
        deduct(c, 20);

        asWa(c);
        InboundDisputeResultVo r = inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("数量不符"));

        assertThat(r.getReversedQty()).isEqualTo(10);
        assertThat(r.getShortfallQty()).isEqualTo(20);
        assertThat(qtyOf(c)).isZero();
        Arbitration arb = arbOf(vo.getId());
        assertThat(arb.getReversedQty()).isEqualTo(10);
        assertThat(arb.getShortfallQty()).isEqualTo(20);
        assertInvariant(c);
    }

    @Test
    @DisplayName("P3-CAP-03 售罄：在库0 → 冲销0 不写流水，全额进差额，仲裁单照常生成")
    void disputeSoldOut() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 0);
        deduct(c, 30);

        asWa(c);
        InboundDisputeResultVo r = inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("质量问题"));

        assertThat(r.getReversedQty()).isZero();
        assertThat(r.getShortfallQty()).isEqualTo(30);
        assertThat(movements(c, StockMovement.TYPE_DISPUTE_REVERSAL)).isEmpty();
        assertThat(inboundRequestMapper.selectById(vo.getId()).getStatus())
                .isEqualTo(InboundRequest.STATUS_DISPUTED);
        assertThat(arbOf(vo.getId())).isNotNull();
        assertInvariant(c);
    }

    @Test
    @DisplayName("P3-CAP-04 冲销与出库并发抢锁（虚拟线程）：同锁串行化，先到先得，库存不打负、公式恒成立")
    void disputeVsDeductConcurrency() throws Exception {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 0);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> disputeF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
                start.await();
                try {
                    return inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("并发异议"));
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Object> deductF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
                start.await();
                try {
                    deduct(c, 20);
                    return "DEDUCTED";
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object disputeR = disputeF.get(60, TimeUnit.SECONDS);
            Object deductR = deductF.get(60, TimeUnit.SECONDS);

            // 异议必成功（单据侧 CAS 无竞争）；冲销量取决于抢锁先后
            assertThat(disputeR).isInstanceOf(InboundDisputeResultVo.class);
            InboundDisputeResultVo dr = (InboundDisputeResultVo) disputeR;
            if ("DEDUCTED".equals(deductR)) {
                // 出库先到：售出 20 → 只冲在库 10
                assertThat(dr.getReversedQty()).isEqualTo(10);
                assertThat(dr.getShortfallQty()).isEqualTo(20);
            } else {
                // 冲销先到：库存清零 → 出库 STOCK_NOT_ENOUGH，全额冲销
                assertThat(((BizException) deductR).getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
                assertThat(dr.getReversedQty()).isEqualTo(30);
                assertThat(dr.getShortfallQty()).isZero();
            }
        }
        assertThat(qtyOf(c)).isGreaterThanOrEqualTo(0);
        assertInvariant(c);
    }

    // ======================================================================
    // 72h 自动确认 Job（12 §2.5）
    // ======================================================================

    @Test
    @DisplayName("P3-JOB-01 超时自动确认（auto_accepted=1）+ 幂等重跑 0 + 超窗 confirm/dispute → 50332")
    void autoConfirmIdempotent() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 10, 0);
        // 回拨 deadline 模拟超时（Job 用数据库时间比较）
        inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, vo.getId())
                .set(InboundRequest::getWaConfirmDeadline, LocalDateTime.now().minusMinutes(5)));

        TenantContext.clear(); // Job 系统态：无租户上下文（全平台扫描先例）
        assertThat(inboundRequestService.autoConfirmExpired()).isEqualTo(1);
        InboundRequest cur = inboundRequestMapper.selectById(vo.getId());
        assertThat(cur.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
        assertThat(cur.getAutoAccepted()).isEqualTo(1);
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isEqualTo(1);

        // 幂等：重跑不重复处理、不重复通知
        assertThat(inboundRequestService.autoConfirmExpired()).isZero();
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_INBOUND_AUTO_CONFIRMED)).isEqualTo(1);

        // 超窗 confirm / dispute → 50332（窗口已关，单据已自动确认）
        asWa(c);
        BizException exConfirm = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.confirmByWa(vo.getId(), c.waUserId()));
        assertThat(exConfirm.getErrorCode()).isEqualTo(ErrorCode.INBOUND_CONFIRM_WINDOW_CLOSED);
        BizException exDispute = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("太晚了")));
        assertThat(exDispute.getErrorCode()).isEqualTo(ErrorCode.INBOUND_CONFIRM_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("P3-JOB-02 Job 与手动确认竞态：CAS 决出唯一赢家（auto_accepted 与败方异常闭环一致）")
    void autoConfirmVsManualRace() throws Exception {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 10, 0);
        inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, vo.getId())
                .set(InboundRequest::getWaConfirmDeadline, LocalDateTime.now().minusMinutes(5)));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> manualF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
                start.await();
                try {
                    return inboundRequestService.confirmByWa(vo.getId(), c.waUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Integer> jobF = vt.submit(() -> {
                start.await();
                return inboundRequestService.autoConfirmExpired();
            });
            start.countDown();
            Object manualR = manualF.get(60, TimeUnit.SECONDS);
            int jobConfirmed = jobF.get(60, TimeUnit.SECONDS);

            InboundRequest cur = inboundRequestMapper.selectById(vo.getId());
            assertThat(cur.getStatus()).isEqualTo(InboundRequest.STATUS_CONFIRMED);
            if (jobConfirmed == 1) {
                // Job 赢：auto_accepted=1，手动 CAS 失败（50332/50331 视重读时序）
                assertThat(cur.getAutoAccepted()).isEqualTo(1);
                assertThat(manualR).isInstanceOf(BizException.class);
                assertThat(((BizException) manualR).getErrorCode())
                        .isIn(ErrorCode.INBOUND_CONFIRM_WINDOW_CLOSED, ErrorCode.DOC_STATE_CAS_CONFLICT);
            } else {
                // 手动赢：auto_accepted=0，Job 本轮 0 单
                assertThat(cur.getAutoAccepted()).isZero();
                assertThat(manualR).isInstanceOf(InboundRequestVo.class);
            }
        }
    }

    // ======================================================================
    // TA 仲裁双路（12 §2.6）
    // ======================================================================

    @Test
    @DisplayName("P3-ARB-01 APPROVED 恢复流水：qty+=reversed，DISPUTE_RESTORE biz_time=原入库时间戳（G10）+ reversal_of_id 配对")
    void decideApproved() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 4);
        deduct(c, 20);
        asWa(c);
        inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("未到货"));
        Arbitration arb = arbOf(vo.getId());
        StockMovement reversal = movements(c, StockMovement.TYPE_DISPUTE_REVERSAL).get(0);

        asTa(c);
        ArbitrationVo decided = arbitrationService.decideByTa(arb.getId(), c.taUserId(),
                decideDto(Arbitration.CONCLUSION_APPROVED, "查证已到货", null));

        assertThat(decided.getStatus()).isEqualTo(Arbitration.STATUS_DECIDED);
        assertThat(decided.getConclusion()).isEqualTo(Arbitration.CONCLUSION_APPROVED);
        assertThat(decided.getLiability()).isNull();
        // 库存恢复：0 + 10（只回 reversed，差额 20 不恢复）
        assertThat(qtyOf(c)).isEqualTo(10);
        // 入库单 DISPUTED → CONFIRMED
        assertThat(inboundRequestMapper.selectById(vo.getId()).getStatus())
                .isEqualTo(InboundRequest.STATUS_CONFIRMED);
        // G10：DISPUTE_RESTORE 流水 biz_time = 原入库单 created_at；reversal_of_id 回指冲销流水
        List<StockMovement> restores = movements(c, StockMovement.TYPE_DISPUTE_RESTORE);
        assertThat(restores).hasSize(1);
        StockMovement restore = restores.get(0);
        assertThat(restore.getQty()).isEqualTo(10);
        assertThat(restore.getBizTime())
                .isEqualTo(inboundRequestMapper.selectById(vo.getId()).getCreatedAt());
        assertThat(restore.getReversalOfId()).isEqualTo(reversal.getId());
        // 冲销流水保留（永不修改/删除既有流水）
        assertThat(movements(c, StockMovement.TYPE_DISPUTE_REVERSAL)).hasSize(1);
        // 双方通知
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_ARBITRATION_DECIDED)).isEqualTo(1);
        assertInvariant(c);
    }

    @Test
    @DisplayName("P3-ARB-02 REJECTED 保留冲销：入库单→REVOKED，无恢复流水；双裁 → 50334")
    void decideRejected() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 0);
        deduct(c, 20);
        asWa(c);
        inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("数量不符"));
        Arbitration arb = arbOf(vo.getId());

        asTa(c);
        ArbitrationVo decided = arbitrationService.decideByTa(arb.getId(), c.taUserId(),
                decideDto(Arbitration.CONCLUSION_REJECTED, "证据不足，判异议成立", Arbitration.WK_LIABLE));

        assertThat(decided.getConclusion()).isEqualTo(Arbitration.CONCLUSION_REJECTED);
        assertThat(decided.getLiability()).isEqualTo(Arbitration.WK_LIABLE);
        assertThat(qtyOf(c)).isZero(); // 冲销保留
        assertThat(movements(c, StockMovement.TYPE_DISPUTE_RESTORE)).isEmpty();
        assertThat(inboundRequestMapper.selectById(vo.getId()).getStatus())
                .isEqualTo(InboundRequest.STATUS_REVOKED);
        assertInvariant(c);

        // 一单一裁：已有结论再裁 → 50334
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> arbitrationService.decideByTa(arb.getId(), c.taUserId(),
                        decideDto(Arbitration.CONCLUSION_APPROVED, "翻案", null)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_NOT_PENDING);
    }

    @Test
    @DisplayName("P3-ARB-03 liability 三态校验（50342）+ 结论错配/缺 remark（50333）")
    void liabilityThreeStates() {
        Ctx c = seedAll();
        // 场景 A：shortfall>0（登记30 售20 → 差额20）
        InboundRequestVo voA = register(c, 30, 0);
        deduct(c, 20);
        asWa(c);
        inboundRequestService.disputeByWa(voA.getId(), c.waUserId(), disputeDto("A"));
        Arbitration arbA = arbOf(voA.getId());

        asTa(c);
        // ① REJECTED ∧ shortfall>0 缺 liability → 50342（必填）
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbA.getId(), c.taUserId(), decideDto(Arbitration.CONCLUSION_REJECTED, "备注", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        // ② 枚举非法 → 50342
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbA.getId(), c.taUserId(), decideDto(Arbitration.CONCLUSION_REJECTED, "备注", "SOMEONE_ELSE")))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        // ③ APPROVED 传入 liability → 50342（必空）
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbA.getId(), c.taUserId(), decideDto(Arbitration.CONCLUSION_APPROVED, "备注", Arbitration.WA_LIABLE)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        // ④ 结论与类型错配（出库四选用于入库仲裁）→ 50333
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbA.getId(), c.taUserId(), decideDto(Arbitration.WK_LIABLE, "备注", null)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        // ⑤ REJECTED 缺 remark → 50333
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbA.getId(), c.taUserId(), decideDto(Arbitration.CONCLUSION_REJECTED, "  ", Arbitration.WK_LIABLE)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        // 上述失败均未落结论
        assertThat(arbitrationMapper.selectById(arbA.getId()).getStatus()).isEqualTo(Arbitration.STATUS_PENDING);

        // 场景 B：shortfall=0（全在库）→ REJECTED 传入 liability 也必须为空 → 50342
        InboundRequestVo voB = register(c, 5, 0);
        asWa(c);
        inboundRequestService.disputeByWa(voB.getId(), c.waUserId(), disputeDto("B"));
        Arbitration arbB = arbOf(voB.getId());
        asTa(c);
        assertThat(Assertions.assertThrows(BizException.class, () -> arbitrationService.decideByTa(
                        arbB.getId(), c.taUserId(), decideDto(Arbitration.CONCLUSION_REJECTED, "备注", Arbitration.NO_LIABILITY)))
                .getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        // shortfall=0 的 REJECTED 不带 liability → 合法
        ArbitrationVo ok = arbitrationService.decideByTa(arbB.getId(), c.taUserId(),
                decideDto(Arbitration.CONCLUSION_REJECTED, "全额冲销无差额", null));
        assertThat(ok.getStatus()).isEqualTo(Arbitration.STATUS_DECIDED);
    }

    @Test
    @DisplayName("P3-ARB-04 TA 列表按租户隔离 + 跨租户裁决按不存在（50334）")
    void taListAndCrossTenant() {
        Ctx c = seedAll();
        Ctx other = seedAll();
        InboundRequestVo vo = register(c, 8, 0);
        asWa(c);
        inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("列表用"));
        Arbitration arb = arbOf(vo.getId());

        asTa(c);
        var page = arbitrationService.listForTa(c.tenantId(), c.taUserId(),
                Arbitration.BIZ_INBOUND_DISPUTE, Arbitration.STATUS_PENDING, 1, 20);
        assertThat(page.getRecords()).extracting(ArbitrationVo::getDocNo).contains(arb.getDocNo());

        // 他租户 TA：TenantLine 兜底 → 按不存在（50334），不泄露存在性
        asTa(other);
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> arbitrationService.decideByTa(arb.getId(), other.taUserId(),
                        decideDto(Arbitration.CONCLUSION_APPROVED, "越权", null)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ARBITRATION_NOT_PENDING);
    }

    // ======================================================================
    // 库存公式不变量对账（混合链路）
    // ======================================================================

    @Test
    @DisplayName("P3-INV-01 公式对账：入库/出库/出库回补/异议冲销/仲裁恢复混合后 qty 与流水恒等")
    void invariantAcrossFullChain() {
        Ctx c = seedAll();
        InboundRequestVo vo = register(c, 30, 2);
        deduct(c, 5);
        // R4 语义的出库回补（BE-W2 端点前，直接走 InventoryService 验证流水配对）
        StockMovement outbound = movements(c, StockMovement.TYPE_OUTBOUND).get(0);
        inventoryService.reverseOutbound(OutboundReversalContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(5).refDocNo("CK-TEST").reversalOfId(outbound.getId())
                .bizTime(outbound.getBizTime()).operatorUserId(c.waUserId()).build());
        assertThat(qtyOf(c)).isEqualTo(30);
        assertInvariant(c);

        // 异议（全在库 30 冲销）→ 仲裁通过恢复 30
        asWa(c);
        inboundRequestService.disputeByWa(vo.getId(), c.waUserId(), disputeDto("其他"));
        assertThat(qtyOf(c)).isZero();
        assertInvariant(c);

        asTa(c);
        arbitrationService.decideByTa(arbOf(vo.getId()).getId(), c.taUserId(),
                decideDto(Arbitration.CONCLUSION_APPROVED, "查证到货", null));
        assertThat(qtyOf(c)).isEqualTo(30);
        // OUTBOUND_REVERSAL 配对断言（reversal_of_id 非空指向原 OUTBOUND）
        List<StockMovement> outRev = movements(c, StockMovement.TYPE_OUTBOUND_REVERSAL);
        assertThat(outRev).hasSize(1);
        assertThat(outRev.get(0).getReversalOfId()).isEqualTo(outbound.getId());
        assertThat(outRev.get(0).getBizTime()).isEqualTo(outbound.getBizTime());
        assertInvariant(c);
    }
}
