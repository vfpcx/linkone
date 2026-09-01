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
import com.cangchu.document.dto.ClearanceCreateDto;
import com.cangchu.document.dto.ClearanceDecideDto;
import com.cangchu.document.dto.ClearanceUpdateDto;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.entity.ClearanceRequest;
import com.cangchu.document.mapper.ClearanceRequestMapper;
import com.cangchu.document.service.ClearanceRequestService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.ClearanceRequestVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.ExpiryDashboardVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3b T4-W2 临期 Job + 强制清库（QK-）场景测试（13 §6 T4-W2 测试关卡）。
 *
 * <p>覆盖（沿 BatchChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>02:00 Job 体：新进入 EXPIRING 首发一次（WK+WA 各一条）、D-12 锚点去重（重跑不重发）、
 *       远期批次不发。</li>
 *   <li>02:30 Job 体：归零标记边界（昨日标/当日到期不标/remaining=0 不标）+ 幂等重跑 + BATCH_EXPIRED 通知。</li>
 *   <li>手动一键通知：24h 限 1（50367，SQL 数据库时间比对）；非临期 50330；非 WK 拒。</li>
 *   <li>QK 全链：50365/50366/50251/原因三选校验；矩阵逐格；三选处置 EXPIRY_CLEARANCE 流水锚点
 *       （type/qty/batch_id/biz_time/pallet_delta）；封顶三值（现场核数 vs 推算剩余 vs onhand）；
 *       applied=0 零流水；托盘全出清零；驳回重提；冻结在途走完（提交时策略）。</li>
 *   <li>CLEARED 后不复算（清库流水带 batch_id 不进池分摊）；R13 未结扩展；TA 看板；
 *       虚拟线程并发：双建恰一成功、清库审批 × 出库同锁串行。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class ExpiryClearanceScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private ClearanceRequestService clearanceRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private BatchMapper batchMapper;
    @Autowired
    private ClearanceRequestMapper clearanceRequestMapper;
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

    // ==================== seed 工具（BatchChainScenarioTest 同构） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId, long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        // W5 抖动①稳定化：全局单调序列简码（TestUniq），根除雪花低位取模的生日悖论碰撞
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
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
        seedRole(wkUserId, "WK", tenantId, null);
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, s.getId(), wkUserId);
    }

    private void seedRole(Long userId, String role, Long tenantId, Long wholesalerId) {
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(userId);
        r.setRole(role);
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        userRoleMapper.insert(r);
    }

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    private void enableBatch(Ctx c) {
        asTa(c);
        BatchToggleDto d = new BatchToggleDto();
        d.setEnable(true);
        d.setConfirmed(true);
        batchService.toggle(c.taUserId(), d);
    }

    private InboundSubmitDto.Item item(Ctx c, int qty, String batchNo, LocalDate prod, LocalDate exp) {
        InboundSubmitDto.Item i = new InboundSubmitDto.Item();
        i.setSkuId(c.skuId());
        i.setQty(qty);
        i.setBatchNo(batchNo);
        i.setProductionDate(prod);
        i.setExpiryDate(exp);
        return i;
    }

    /** 正向链全程：提交（带批次）→ 受理 → 登记（可带托盘/过期凭据）→ CONFIRMED。 */
    private InboundRequestVo submitAcceptRegister(Ctx c, int qty, Integer palletQty, String batchNo,
                                                  LocalDate prod, LocalDate exp, Boolean expiredConfirmed) {
        asWa(c);
        InboundSubmitDto d = new InboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setItems(List.of(item(c, qty, batchNo, prod, exp)));
        InboundRequestVo vo = inboundRequestService.submitByWa(d, c.waUserId()).get(0);
        asWk(c);
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
        InboundForwardRegisterDto reg = new InboundForwardRegisterDto();
        reg.setActualQty(qty);
        reg.setPalletQty(palletQty);
        reg.setExpiredConfirmed(expiredConfirmed);
        return inboundRequestService.registerForwardByWk(vo.getId(), reg, c.wkUserId());
    }

    /** 登记近效期批次（+days 天到效，阈值 30 内 → 入库即 EXPIRING）。 */
    private Batch seedExpiringBatch(Ctx c, String batchNo, int qty, int daysToExpiry) {
        submitAcceptRegister(c, qty, null, batchNo,
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(daysToExpiry), null);
        return batchByNo(c, batchNo);
    }

    /** 登记昨日过期批次并跑 02:30 归零标记 → PENDING_CLEARANCE。 */
    private Batch seedPendingClearanceBatch(Ctx c, String batchNo, int qty, Integer palletQty) {
        submitAcceptRegister(c, qty, palletQty, batchNo,
                LocalDate.now().minusDays(100), LocalDate.now().minusDays(1), true);
        batchService.markExpiredBatches();
        Batch b = batchByNo(c, batchNo);
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_PENDING_CLEARANCE);
        return b;
    }

    private void deduct(Ctx c, int qty, String refDocNo) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo(refDocNo).operatorUserId(c.wkUserId()).build());
    }

    private Batch batchByNo(Ctx c, String batchNo) {
        return batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId())
                .eq(Batch::getSkuId, c.skuId())
                .eq(Batch::getBatchNo, batchNo));
    }

    private ClearanceCreateDto createDto(Long batchId, Integer qty, String reason,
                                         String reasonRemark, Integer palletRelease, List<String> photos) {
        ClearanceCreateDto d = new ClearanceCreateDto();
        d.setBatchId(batchId);
        d.setQty(qty);
        d.setReason(reason);
        d.setReasonRemark(reasonRemark);
        d.setPalletRelease(palletRelease);
        d.setAttachments(photos);
        return d;
    }

    private ClearanceRequestVo createQk(Ctx c, Long batchId, Integer qty, String reason) {
        asWk(c);
        return clearanceRequestService.createByWk(
                createDto(batchId, qty, reason, null, null, List.of(fakeUrl())), c.tenantId(), c.wkUserId());
    }

    private ClearanceRequestVo submitQk(Ctx c, Long id) {
        asWk(c);
        return clearanceRequestService.submitByWk(id, c.wkUserId());
    }

    private ClearanceRequestVo decideQk(Ctx c, Long id, String conclusion, String remark) {
        asTa(c);
        ClearanceDecideDto d = new ClearanceDecideDto();
        d.setConclusion(conclusion);
        d.setRemark(remark);
        return clearanceRequestService.decideByTa(id, d, c.taUserId());
    }

    private List<StockMovement> movementsOf(String refDocNo, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, refDocNo)
                .eq(StockMovement::getType, type));
    }

    private long notifyCount(String type, Long refId) {
        Long cnt = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, type)
                .eq(Notification::getRefId, refId));
        return cnt != null ? cnt : 0;
    }

    private int qtyOf(Ctx c) {
        var list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() || list.get(0).getQty() == null ? 0 : list.get(0).getQty();
    }

    private int errCode(Runnable r) {
        try {
            r.run();
            return -1;
        } catch (BizException e) {
            return e.getCode();
        }
    }

    private static String fakeUrl() {
        return "/files/202608/" + UUID.randomUUID() + ".jpg";
    }

    // ==================== 02:00 Job：D-12 首发去重（关卡） ====================

    @Test
    @DisplayName("T4W2-JOB-01 新进入 EXPIRING 首发一次（WK+WA 各一条）；重跑不重发；远期批次不发")
    void dailyRecalcNotifiesNewlyExpiringOnce() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch near = seedExpiringBatch(c, "NEAR-1", 40, 10);
        submitAcceptRegister(c, 40, null, "FAR-1",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(300), null);
        assertThat(near.getExpiringNotifiedAt()).isNull();

        batchService.runDailyRecalcAndNotify();
        // 首发：库管 1 条 + 商户管理员 1 条（各角色 user_roles 推导单账号）
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRING, near.getId())).isEqualTo(2);
        Batch after = batchMapper.selectById(near.getId());
        assertThat(after.getExpiringNotifiedAt()).isNotNull();
        List<Notification> notices = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, Notification.TYPE_BATCH_EXPIRING)
                .eq(Notification::getRefId, near.getId()));
        assertThat(notices).extracting(Notification::getRecipientUserId)
                .containsExactlyInAnyOrder(c.wkUserId(), c.waUserId());

        // 幂等重跑：状态不变不重发（D-12 锚点已落）
        batchService.runDailyRecalcAndNotify();
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRING, near.getId())).isEqualTo(2);
        // 远期批次不属临期集合，零通知
        Batch far = batchByNo(c, "FAR-1");
        assertThat(far.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRING, far.getId())).isZero();
    }

    // ==================== 02:30 Job：归零标记边界（关卡） ====================

    @Test
    @DisplayName("T4W2-JOB-02 归零标记边界：昨日到期标 PENDING_CLEARANCE+通知；当日到期不标；remaining=0 不标；重跑幂等")
    void markExpiredBoundary() {
        Ctx c = seedAll();
        enableBatch(c);
        // A：昨日过期，remaining>0 → 该标
        submitAcceptRegister(c, 30, null, "EXP-YDA",
                LocalDate.now().minusDays(100), LocalDate.now().minusDays(1), true);
        // B：当日到期 → 不标（expiry_date < CURDATE() 严格小于）
        submitAcceptRegister(c, 20, null, "EXP-TODAY",
                LocalDate.now().minusDays(100), LocalDate.now(), true);
        // C：昨日过期但 remaining=0（推算滞后窗口模拟）→ 不标
        submitAcceptRegister(c, 10, null, "EXP-EMPTY",
                LocalDate.now().minusDays(100), LocalDate.now().minusDays(1), true);
        batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, batchByNo(c, "EXP-EMPTY").getId())
                .set(Batch::getRemainingQty, 0));

        batchService.markExpiredBatches();
        Batch a = batchByNo(c, "EXP-YDA");
        assertThat(a.getStatus()).isEqualTo(Batch.STATUS_PENDING_CLEARANCE);
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRED, a.getId())).isEqualTo(1);
        assertThat(batchByNo(c, "EXP-TODAY").getStatus()).isEqualTo(Batch.STATUS_EXPIRING);
        assertThat(batchByNo(c, "EXP-EMPTY").getStatus()).isEqualTo(Batch.STATUS_EXPIRING);

        // 重跑幂等：已 PENDING_CLEARANCE 不在 (IN_STOCK, EXPIRING) 扫描集，不重复标/不重发
        batchService.markExpiredBatches();
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRED, a.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("T4W2-JOB-03 02:00 复算不降级 PENDING_CLEARANCE（T4-W1 备注 10 回归）")
    void recalcDoesNotDowngradePendingClearance() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch b = seedPendingClearanceBatch(c, "PC-KEEP", 30, null);
        batchService.recalcTenant(c.tenantId());
        Batch after = batchMapper.selectById(b.getId());
        assertThat(after.getStatus()).isEqualTo(Batch.STATUS_PENDING_CLEARANCE);
        assertThat(after.getRemainingQty()).isEqualTo(30);
    }

    // ==================== 手动一键通知：24h 限 1（关卡） ====================

    @Test
    @DisplayName("T4W2-NTF-01 手动通知：商户收 1 条；同批次 24h 内再发 50367；回拨 25h 后可再发；非临期 50330；非 WK 拒")
    void manualNotifyRateLimit() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch near = seedExpiringBatch(c, "MN-1", 20, 5);
        submitAcceptRegister(c, 20, null, "MN-FAR",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(300), null);

        asWk(c);
        batchService.notifyWholesalerManually(near.getId(), c.wkUserId());
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRING, near.getId())).isEqualTo(1);
        assertThat(batchMapper.selectById(near.getId()).getManualNotifiedAt()).isNotNull();

        // 24h 内第 2 次 → 50367
        assertThat(errCode(() -> batchService.notifyWholesalerManually(near.getId(), c.wkUserId())))
                .isEqualTo(ErrorCode.EXPIRY_NOTIFY_RATE_LIMITED.getCode());

        // 回拨 25h（SQL 数据库时间比对）→ 冷却结束可再发
        batchMapper.update(null, new LambdaUpdateWrapper<Batch>()
                .eq(Batch::getId, near.getId())
                .set(Batch::getManualNotifiedAt, LocalDateTime.now().minusHours(25)));
        batchService.notifyWholesalerManually(near.getId(), c.wkUserId());
        assertThat(notifyCount(Notification.TYPE_BATCH_EXPIRING, near.getId())).isEqualTo(2);

        // 非临期/待清理批次 → 50330 语义
        Batch far = batchByNo(c, "MN-FAR");
        assertThat(errCode(() -> batchService.notifyWholesalerManually(far.getId(), c.wkUserId())))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        // 仅 WK：商户管理员调用被拒
        asWa(c);
        assertThat(errCode(() -> batchService.notifyWholesalerManually(near.getId(), c.waUserId())))
                .isEqualTo(ErrorCode.PERMISSION_ROLE_001.getCode());
        // 批次不存在（不泄漏存在性）
        asWk(c);
        assertThat(errCode(() -> batchService.notifyWholesalerManually(snowflakeIdUtil.nextId(), c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NOT_FOUND.getCode());
    }

    // ==================== 预警列表 ====================

    @Test
    @DisplayName("T4W2-LST-01 预警列表：EXPIRING∪PENDING_CLEARANCE 剩余天数升序；含 manualNotifiedAt；WA 拒")
    void expiringListOrderAndScope() {
        Ctx c = seedAll();
        enableBatch(c);
        seedPendingClearanceBatch(c, "L-EXPIRED", 10, null);
        seedExpiringBatch(c, "L-NEAR5", 10, 5);
        seedExpiringBatch(c, "L-NEAR20", 10, 20);
        submitAcceptRegister(c, 10, null, "L-FAR",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(300), null);

        asWk(c);
        BatchListVo list = batchService.listExpiring(c.tenantId(), c.wkUserId());
        assertThat(list.getList()).extracting(v -> v.getBatchNo())
                .containsExactly("L-EXPIRED", "L-NEAR5", "L-NEAR20");
        // 到期已过剩余天数为负（FE 展示「过期 N 天」契约）
        assertThat(list.getList().get(0).getRemainingDays()).isLessThan(0);
        assertThat(list.getList().get(0).getManualNotifiedAt()).isNull();

        asWa(c);
        assertThat(errCode(() -> batchService.listExpiring(c.tenantId(), c.waUserId())))
                .isEqualTo(ErrorCode.PERMISSION_ROLE_001.getCode());
    }

    // ==================== QK：建单校验（50365/50366/50251/原因三选） ====================

    @Test
    @DisplayName("T4W2-QK-01 建单校验：非待清理 50365；缺照片 50366；>3 张 40001；外链 50340；原因非法 40001；OTHER 缺备注 40003；超池 50251")
    void clearanceCreateValidations() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch expiring = seedExpiringBatch(c, "QKV-EXPIRING", 20, 5);
        Batch pc = seedPendingClearanceBatch(c, "QKV-PC", 50, null);
        asWk(c);

        // 非待清理（EXPIRING）→ 50365
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(expiring.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null,
                        List.of(fakeUrl())), c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE.getCode());
        // 实物照片必填 ≥1（R19 刚性）→ 50366
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null, List.of()),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.CLEARANCE_PHOTO_REQUIRED.getCode());
        // >3 张 → 40001
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null,
                        List.of(fakeUrl(), fakeUrl(), fakeUrl(), fakeUrl())), c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_001.getCode());
        // 外链（N2 白名单）→ 50340
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null,
                        List.of("https://evil.example.com/x.jpg")), c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.FILE_UPLOAD_INVALID.getCode());
        // 原因非法 → 40001
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, "BROKEN", null, null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_001.getCode());
        // OTHER 缺备注 → 40003
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_OTHER, "  ", null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode());
        // 现场核数超池当前在库（池=20+50=70，两批同 SKU 共池）→ 50251（PRD §3.5 表单口径）
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 80, ClearanceRequest.REASON_EXPIRED, null, null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH.getCode());
        // 托盘覆盖负值 → 40001
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, -1, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_001.getCode());

        // qty 空 → 默认=推算剩余；QK- 前缀；wholesaler/sku 随批次推导
        ClearanceRequestVo vo = clearanceRequestService.createByWk(
                createDto(pc.getId(), null, ClearanceRequest.REASON_EXPIRED, null, null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId());
        assertThat(vo.getDocNo()).startsWith("QK-");
        assertThat(vo.getQty()).isEqualTo(50);
        assertThat(vo.getWholesalerId()).isEqualTo(c.wholesalerId());
        assertThat(vo.getSkuId()).isEqualTo(c.skuId());
        assertThat(vo.getStatus()).isEqualTo(ClearanceRequest.STATUS_DRAFT);

        // 同批次在途至多一张 → 50365
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE.getCode());
        // 删除草稿释放唯一位 → 可重建
        clearanceRequestService.deleteByWk(vo.getId(), c.wkUserId());
        ClearanceRequestVo again = createQk(c, pc.getId(), 10, ClearanceRequest.REASON_DAMAGED);
        assertThat(again.getStatus()).isEqualTo(ClearanceRequest.STATUS_DRAFT);
    }

    // ==================== QK：矩阵逐格（关卡） ====================

    @Test
    @DisplayName("T4W2-QK-02 清库矩阵逐格：4×4 仅 4 条可达；端点红线 50330（草稿直批/提后编辑/重复提交/非草稿删除/终态再裁）")
    void clearanceMatrixPerCell() {
        // 矩阵纯函数逐格
        List<String> states = List.of(ClearanceRequest.STATUS_DRAFT, ClearanceRequest.STATUS_PENDING_APPROVAL,
                ClearanceRequest.STATUS_REJECTED, ClearanceRequest.STATUS_APPROVED);
        Set<String> allowed = Set.of("DRAFT>PENDING_APPROVAL", "PENDING_APPROVAL>REJECTED",
                "PENDING_APPROVAL>APPROVED", "REJECTED>DRAFT");
        for (String from : states) {
            for (String to : states) {
                assertThat(DocStateMachine.canGo(DocKind.CLEARANCE, from, to))
                        .as("%s → %s", from, to)
                        .isEqualTo(allowed.contains(from + ">" + to));
            }
        }

        // 端点红线
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKM-1", 30, null);
        ClearanceRequestVo draft = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        // 草稿直批 ❌（必须经提交）
        assertThat(errCode(() -> decideQk(c, draft.getId(), ClearanceRequest.STATUS_APPROVED, null)))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        submitQk(c, draft.getId());
        // 提交后编辑 ❌ / 重复提交 ❌ / 非草稿删除 ❌
        ClearanceUpdateDto upd = new ClearanceUpdateDto();
        upd.setQty(10);
        upd.setReason(ClearanceRequest.REASON_EXPIRED);
        upd.setAttachments(List.of(fakeUrl()));
        asWk(c);
        assertThat(errCode(() -> clearanceRequestService.updateByWk(draft.getId(), upd, c.wkUserId())))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        assertThat(errCode(() -> clearanceRequestService.submitByWk(draft.getId(), c.wkUserId())))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        assertThat(errCode(() -> clearanceRequestService.deleteByWk(draft.getId(), c.wkUserId())))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        // 驳回缺理由 → 40003；驳回后终态再裁 ❌
        assertThat(errCode(() -> decideQk(c, draft.getId(), ClearanceRequest.STATUS_REJECTED, null)))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode());
        decideQk(c, draft.getId(), ClearanceRequest.STATUS_REJECTED, "照片不清晰");
        assertThat(errCode(() -> decideQk(c, draft.getId(), ClearanceRequest.STATUS_APPROVED, null)))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        // 结论非法 → 40001
        assertThat(errCode(() -> decideQk(c, draft.getId(), "CANCELLED", null)))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_001.getCode());
    }

    // ==================== QK：原因三选处置流水锚点（关卡） ====================

    @Test
    @DisplayName("T4W2-QK-03 原因三选全链：EXPIRED/DAMAGED/OTHER 各生成 EXPIRY_CLEARANCE 锚点（qty/batch_id/biz_time=审批日）+批次 CLEARED")
    void threeReasonsFullChainMovementAnchors() {
        Ctx c = seedAll();
        enableBatch(c);
        String[][] cases = {
                {"QK3-EXP", ClearanceRequest.REASON_EXPIRED, null},
                {"QK3-DMG", ClearanceRequest.REASON_DAMAGED, null},
                {"QK3-OTH", ClearanceRequest.REASON_OTHER, "客户投诉变质"},
        };
        for (String[] cs : cases) {
            Batch pc = seedPendingClearanceBatch(c, cs[0], 20, null);
            asWk(c);
            ClearanceRequestVo vo = clearanceRequestService.createByWk(
                    createDto(pc.getId(), 20, cs[1], cs[2], 0, List.of(fakeUrl())),
                    c.tenantId(), c.wkUserId());
            submitQk(c, vo.getId());
            assertThat(notifyCount(Notification.TYPE_CLEARANCE_PENDING, vo.getId())).isEqualTo(1);
            ClearanceRequestVo approved = decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
            assertThat(approved.getStatus()).isEqualTo(ClearanceRequest.STATUS_APPROVED);

            List<StockMovement> mvs = movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE);
            assertThat(mvs).as("reason=%s", cs[1]).hasSize(1);
            StockMovement mv = mvs.get(0);
            assertThat(mv.getQty()).isEqualTo(20);
            assertThat(mv.getBatchId()).isEqualTo(pc.getId());
            assertThat(mv.getPalletDelta()).isZero();
            // biz_time=清库日（仓储费当日截止锚点，零金额只保锚点）
            assertThat(mv.getBizTime().toLocalDate()).isEqualTo(LocalDate.now());
            Batch cleared = batchMapper.selectById(pc.getId());
            assertThat(cleared.getStatus()).isEqualTo(Batch.STATUS_CLEARED);
            assertThat(cleared.getRemainingQty()).isZero();
            assertThat(cleared.getClearedAt()).isNotNull();
            // 商户凭证通知（含照片 URL）+ 库管结论通知
            List<Notification> decidedNotices = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getType, Notification.TYPE_CLEARANCE_DECIDED)
                    .eq(Notification::getRefId, vo.getId()));
            assertThat(decidedNotices).extracting(Notification::getRecipientUserId)
                    .containsExactlyInAnyOrder(c.waUserId(), c.wkUserId());
            assertThat(decidedNotices.stream()
                    .filter(n -> n.getRecipientUserId().equals(c.waUserId()))
                    .findFirst().orElseThrow().getContent()).contains("凭证照片", "/files/");
        }
        // 三批全清 → 池归零
        assertThat(qtyOf(c)).isZero();
    }

    // ==================== QK：封顶三值（关卡） ====================

    @Test
    @DisplayName("T4W2-QK-04 封顶三值：推算剩余 50、现场核数 40、审批时在库 25 → applied=25、差额 15 备注、pallet_release 回写")
    void clearanceCapThreeValues() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKC-1", 50, null);
        // WK 现场核数 40（< 推算剩余 50，建单时在库 50 校验通过）
        ClearanceRequestVo vo = createQk(c, pc.getId(), 40, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());
        // 等待审批期被出库 25 → 审批时刻在库仅 25（两时点语义分离，G9 同构）
        deduct(c, 25, "QKC-OUT");

        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        List<StockMovement> mvs = movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE);
        assertThat(mvs).hasSize(1);
        assertThat(mvs.get(0).getQty()).isEqualTo(25);
        assertThat(qtyOf(c)).isZero();
        ClearanceRequest after = clearanceRequestMapper.selectById(vo.getId());
        assertThat(after.getRemark()).contains("差额 15 件");
        assertThat(after.getPalletRelease()).isZero();
        assertThat(batchMapper.selectById(pc.getId()).getStatus()).isEqualTo(Batch.STATUS_CLEARED);
    }

    @Test
    @DisplayName("T4W2-QK-05 applied=0（审批时售罄）：零冲销不写流水，单据照常 APPROVED、批次照常 CLEARED")
    void clearanceAppliedZero() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKZ-1", 30, null);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());
        deduct(c, 30, "QKZ-OUT"); // 审批前全部出完

        ClearanceRequestVo approved = decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        assertThat(approved.getStatus()).isEqualTo(ClearanceRequest.STATUS_APPROVED);
        assertThat(movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE)).isEmpty();
        assertThat(qtyOf(c)).isZero();
        Batch cleared = batchMapper.selectById(pc.getId());
        assertThat(cleared.getStatus()).isEqualTo(Batch.STATUS_CLEARED);
        assertThat(cleared.getRemainingQty()).isZero();
        assertThat(clearanceRequestMapper.selectById(vo.getId()).getRemark()).contains("差额 30 件");
    }

    @Test
    @DisplayName("T4W2-QK-06 托盘全出清零：覆盖值 NULL 默认释放全部占用托盘（pallet_delta=−6，回写 6）")
    void clearancePalletFullReleaseOnClearOut() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKP-1", 30, 6);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_DAMAGED);
        submitQk(c, vo.getId());
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);

        List<StockMovement> mvs = movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE);
        assertThat(mvs).hasSize(1);
        assertThat(mvs.get(0).getPalletDelta()).isEqualTo(-6);
        assertThat(clearanceRequestMapper.selectById(vo.getId()).getPalletRelease()).isEqualTo(6);
        var inv = inventoryService.queryInventory(c.wholesalerId(), c.skuId()).get(0);
        assertThat(inv.getQty()).isZero();
        assertThat(inv.getPalletQty()).isZero();
    }

    // ==================== CLEARED 后不复算（关卡） ====================

    @Test
    @DisplayName("T4W2-QK-07 批次 CLEARED 后不复算：清库流水带 batch_id 不进池分摊，同 SKU 其余批次推算不受影响")
    void clearedBatchExcludedFromRecalc() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKR-OLD", 30, null);
        submitAcceptRegister(c, 50, null, "QKR-NEW",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(200), null);

        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        assertThat(qtyOf(c)).isEqualTo(50);

        var result = batchService.recalcTenant(c.tenantId());
        // CLEARED 不入扫描集
        assertThat(result.getScannedBatches()).isEqualTo(1);
        Batch cleared = batchMapper.selectById(pc.getId());
        assertThat(cleared.getStatus()).isEqualTo(Batch.STATUS_CLEARED);
        assertThat(cleared.getRemainingQty()).isZero();
        // 清库 30 件流水带 batch_id → 不计入池净出，新批次推算剩余保持 50（无批次在池量=0）
        Batch fresh = batchByNo(c, "QKR-NEW");
        assertThat(fresh.getRemainingQty()).isEqualTo(50);
        asWk(c);
        BatchListVo list = batchService.listForTenant(c.tenantId(), c.wkUserId(), c.wholesalerId(), c.skuId(), null);
        assertThat(list.getUnpooledQty()).isZero();
    }

    // ==================== 驳回重提 / 冻结走完 ====================

    @Test
    @DisplayName("T4W2-QK-08 驳回不动账→编辑重提（复检批次仍待清理）→再审通过；全程恰一条清库流水")
    void rejectEditResubmit() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKJ-1", 30, null);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_REJECTED, "请补拍整批照片");
        // 驳回零库存零流水
        assertThat(qtyOf(c)).isEqualTo(30);
        assertThat(movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE)).isEmpty();
        assertThat(clearanceRequestMapper.selectById(vo.getId()).getPendingFlag()).isNull();

        asWk(c);
        ClearanceUpdateDto upd = new ClearanceUpdateDto();
        upd.setQty(25);
        upd.setReason(ClearanceRequest.REASON_OTHER);
        upd.setReasonRemark("部分已由商户折价取回");
        upd.setAttachments(List.of(fakeUrl(), fakeUrl()));
        ClearanceRequestVo edited = clearanceRequestService.updateByWk(vo.getId(), upd, c.wkUserId());
        assertThat(edited.getStatus()).isEqualTo(ClearanceRequest.STATUS_DRAFT);
        assertThat(edited.getQty()).isEqualTo(25);
        submitQk(c, vo.getId());
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        List<StockMovement> mvs = movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE);
        assertThat(mvs).hasSize(1);
        assertThat(mvs.get(0).getQty()).isEqualTo(25);
        assertThat(qtyOf(c)).isEqualTo(5);
    }

    @Test
    @DisplayName("T4W2-QK-09 启→关冻结（批次 CLOSED）不阻在途清库单走完（提交时策略）：审批照常生效、批次终态 CLEARED")
    void inflightClearanceSurvivesBatchToggleOff() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKF-1", 30, null);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());

        asTa(c);
        BatchToggleDto off = new BatchToggleDto();
        off.setEnable(false);
        off.setConfirmed(true);
        batchService.toggle(c.taUserId(), off);
        assertThat(batchMapper.selectById(pc.getId()).getStatus()).isEqualTo(Batch.STATUS_CLOSED);

        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        assertThat(movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE)).hasSize(1);
        assertThat(batchMapper.selectById(pc.getId()).getStatus()).isEqualTo(Batch.STATUS_CLEARED);
        assertThat(qtyOf(c)).isZero();
    }

    // ==================== R13 未结扩展（关卡） ====================

    @Test
    @DisplayName("T4W2-R13-01 未结扩展：清库 DRAFT/PENDING_APPROVAL 计入、REJECTED/APPROVED 不计（阻退驻口径）")
    void openCountExtension() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKR13", 30, null);
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        submitQk(c, vo.getId());
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_REJECTED, "重拍");
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
        // 重提回到在途 → 1；通过 → 0
        asWk(c);
        ClearanceUpdateDto upd = new ClearanceUpdateDto();
        upd.setQty(30);
        upd.setReason(ClearanceRequest.REASON_EXPIRED);
        upd.setAttachments(List.of(fakeUrl()));
        clearanceRequestService.updateByWk(vo.getId(), upd, c.wkUserId());
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        submitQk(c, vo.getId());
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        assertThat(clearanceRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
    }

    // ==================== TA 看板 ====================

    @Test
    @DisplayName("T4W2-DSH-01 临期看板：四卡（临期/待清理/已清库/清库单待审批）+按 SKU 分组推算件数；WK 拒")
    void expiryDashboard() {
        Ctx c = seedAll();
        enableBatch(c);
        seedExpiringBatch(c, "DSH-NEAR", 20, 5);
        Batch pc = seedPendingClearanceBatch(c, "DSH-PC", 10, null);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 10, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());

        asTa(c);
        ExpiryDashboardVo dash = batchService.expiryDashboard(c.tenantId(), c.taUserId());
        dash.setPendingClearanceDocCount(clearanceRequestService.countPendingApprovalForTenant(c.tenantId()));
        assertThat(dash.getExpiringBatchCount()).isEqualTo(1);
        assertThat(dash.getExpiringQtyTotal()).isEqualTo(20);
        assertThat(dash.getExpiredBatchCount()).isEqualTo(1);
        assertThat(dash.getExpiredQtyTotal()).isEqualTo(10);
        assertThat(dash.getClearedBatchCount()).isZero();
        assertThat(dash.getPendingClearanceDocCount()).isEqualTo(1);
        assertThat(dash.getBySku()).hasSize(1);
        ExpiryDashboardVo.SkuGroup g = dash.getBySku().get(0);
        assertThat(g.getSkuId()).isEqualTo(String.valueOf(c.skuId()));
        assertThat(g.getExpiringBatchCount()).isEqualTo(1);
        assertThat(g.getExpiredBatchCount()).isEqualTo(1);
        assertThat(g.getRemainingQtyTotal()).isEqualTo(30);
        assertThat(g.getNearestExpiryDate()).isEqualTo(LocalDate.now().minusDays(1));

        // 审批通过后：待清理 0、已清库 1、待审批 0
        decideQk(c, vo.getId(), ClearanceRequest.STATUS_APPROVED, null);
        asTa(c);
        ExpiryDashboardVo after = batchService.expiryDashboard(c.tenantId(), c.taUserId());
        assertThat(after.getExpiredBatchCount()).isZero();
        assertThat(after.getClearedBatchCount()).isEqualTo(1);
        assertThat(clearanceRequestService.countPendingApprovalForTenant(c.tenantId())).isZero();

        // 仅 TA
        asWk(c);
        assertThat(errCode(() -> batchService.expiryDashboard(c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.PERMISSION_ROLE_001.getCode());
    }

    // ==================== 越权矩阵 ====================

    @Test
    @DisplayName("T4W2-SEC-01 越权矩阵：WA 建单拒；WK 审批拒；跨租户批次按不存在 50363")
    void permissionMatrix() {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKS-1", 30, null);

        // WA 建单 → 拒（仅 WK）
        asWa(c);
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(pc.getId(), 10, ClearanceRequest.REASON_EXPIRED, null, null, List.of(fakeUrl())),
                c.tenantId(), c.waUserId())))
                .isEqualTo(ErrorCode.PERMISSION_ROLE_001.getCode());

        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());
        // WK 审批 → 拒（仅 TA）
        asWk(c);
        ClearanceDecideDto d = new ClearanceDecideDto();
        d.setConclusion(ClearanceRequest.STATUS_APPROVED);
        assertThat(errCode(() -> clearanceRequestService.decideByTa(vo.getId(), d, c.wkUserId())))
                .isEqualTo(ErrorCode.PERMISSION_ROLE_001.getCode());

        // 跨租户批次 → 50363（不泄漏存在性）
        Ctx other = seedAll();
        enableBatch(other);
        Batch otherPc = seedPendingClearanceBatch(other, "QKS-X", 10, null);
        asWk(c);
        assertThat(errCode(() -> clearanceRequestService.createByWk(
                createDto(otherPc.getId(), 5, ClearanceRequest.REASON_EXPIRED, null, null, List.of(fakeUrl())),
                c.tenantId(), c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NOT_FOUND.getCode());
    }

    // ==================== 虚拟线程并发（关卡） ====================

    @Test
    @DisplayName("T4W2-CONC-01 并发双建（虚拟线程）：同批次两路 create 恰一成功，败方 50365（uk_qk_batch_pending 兜底）")
    void concurrentDoubleCreate() throws Exception {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKCC-1", 30, null);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Object> task = () -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return clearanceRequestService.createByWk(
                            createDto(pc.getId(), 30, ClearanceRequest.REASON_EXPIRED, null, null,
                                    List.of(fakeUrl())), c.tenantId(), c.wkUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            };
            Future<Object> f1 = vt.submit(task);
            Future<Object> f2 = vt.submit(task);
            start.countDown();
            Object r1 = f1.get(60, TimeUnit.SECONDS);
            Object r2 = f2.get(60, TimeUnit.SECONDS);

            long wins = List.of(r1, r2).stream().filter(r -> r instanceof ClearanceRequestVo).count();
            assertThat(wins).isEqualTo(1);
            BizException loser = (BizException) (r1 instanceof BizException ? r1 : r2);
            assertThat(loser.getCode()).isEqualTo(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE.getCode());
        }
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        Long open = clearanceRequestMapper.selectCount(new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getBatchId, pc.getId()));
        assertThat(open).isEqualTo(1);
    }

    @Test
    @DisplayName("T4W2-CONC-02 清库审批 × 出库并发（虚拟线程）：同锁串行——锁内封顶不超卖、qty 恒 ≥0、流水对账闭合")
    void concurrentClearanceVsOutbound() throws Exception {
        Ctx c = seedAll();
        enableBatch(c);
        Batch pc = seedPendingClearanceBatch(c, "QKCO-1", 30, null);
        ClearanceRequestVo vo = createQk(c, pc.getId(), 30, ClearanceRequest.REASON_EXPIRED);
        submitQk(c, vo.getId());

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> approveF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
                start.await();
                try {
                    ClearanceDecideDto d = new ClearanceDecideDto();
                    d.setConclusion(ClearanceRequest.STATUS_APPROVED);
                    return clearanceRequestService.decideByTa(vo.getId(), d, c.taUserId());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            Future<Object> deductF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return inventoryService.deductStock(OutboundContext.builder()
                            .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                            .qty(20).refDocNo("QKCO-OUT").operatorUserId(c.wkUserId()).build());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object approveR = approveF.get(60, TimeUnit.SECONDS);
            Object deductR = deductF.get(60, TimeUnit.SECONDS);

            // 审批必成功（封顶语义永不因不足失败）
            assertThat(approveR).isInstanceOf(ClearanceRequestVo.class);
            TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
            int clearedQty = movementsOf(vo.getDocNo(), StockMovement.TYPE_EXPIRY_CLEARANCE).stream()
                    .mapToInt(StockMovement::getQty).sum();
            if (deductR instanceof BizException loser) {
                // 清库先行全额 30 → 池 0 不够出 20 → 出库 50251
                assertThat(loser.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
                assertThat(clearedQty).isEqualTo(30);
                assertThat(qtyOf(c)).isZero();
            } else {
                // 出库先行 20 → 审批时刻仅剩 10 → 锁内封顶生效 10、差额 20 备注
                assertThat(clearedQty).isEqualTo(10);
                assertThat(qtyOf(c)).isZero();
                assertThat(clearanceRequestMapper.selectById(vo.getId()).getRemark()).contains("差额 20 件");
            }
            // 批次终态一致
            assertThat(batchMapper.selectById(pc.getId()).getStatus()).isEqualTo(Batch.STATUS_CLEARED);
        }
        // 终局对账：INBOUND − OUTBOUND − EXPIRY_CLEARANCE ≡ 在库 ≥ 0
        List<StockMovement> all = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId()));
        int in = all.stream().filter(m -> StockMovement.TYPE_INBOUND.equals(m.getType()))
                .mapToInt(StockMovement::getQty).sum();
        int out = all.stream().filter(m -> StockMovement.TYPE_OUTBOUND.equals(m.getType()))
                .mapToInt(StockMovement::getQty).sum();
        int cleared = all.stream().filter(m -> StockMovement.TYPE_EXPIRY_CLEARANCE.equals(m.getType()))
                .mapToInt(StockMovement::getQty).sum();
        assertThat(qtyOf(c)).isGreaterThanOrEqualTo(0).isEqualTo(in - out - cleared);
    }
}
