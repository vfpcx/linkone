package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.InboundRejectDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.GainStockContext;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchRecalcResultVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3b T4-W1 批次登记 + FIFO 离线推算场景测试（13 §6 T4-W1 测试关卡 + T1 收尾两项）。
 *
 * <p>覆盖（沿用 InboundForwardChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>开关：关→启默认批次吸收存量快照；幂等空转（不计次/不动切割时点/不重复生成）；
 *       24h 限 2（50361）；启→关登记簿冻结 CLOSED；再启用生成新默认批次不复活。</li>
 *   <li>登记簿追加零侵入：登记后批次行追加 + INBOUND 流水 batch_id 回填，
 *       库存/流水行为与关闭档完全一致（入库事务零侵入验证）。</li>
 *   <li>批次三字段校验（40003/40205/40206）、批次号唯一（50362 同批内/跨单）、
 *       过期二次确认（50364，凭据放行 + 入库即 EXPIRING）。</li>
 *   <li>FIFO 推算三场景：单批消耗（含 GAIN 抵扣）/ 跨批分摊（expiry NULLS LAST，
 *       默认批次垫底）/ 切割点前历史入默认批不重复扣抵；幂等重跑。</li>
 *   <li>T1 收尾：提交附件落申请单（N2 白名单）；ACCEPTED→REJECTED（受理后驳回+通知沿用，
 *       REJECTED→ACCEPTED 红线不破）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class BatchChainScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private BatchMapper batchMapper;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
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
    @Autowired
    private RedissonClient redissonClient;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed 工具（InboundForwardChainScenarioTest 同构） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId, long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = snowflakeIdUtil.nextId();
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode("T" + (tenantId % 1_000_000));
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

    private BatchToggleDto toggleDto(boolean enable, Boolean confirmed) {
        BatchToggleDto d = new BatchToggleDto();
        d.setEnable(enable);
        d.setConfirmed(confirmed);
        return d;
    }

    private BatchToggleVo enableBatch(Ctx c) {
        asTa(c);
        return batchService.toggle(c.taUserId(), toggleDto(true, true));
    }

    /** 24h 计数清零（跨断言场景的测试基建；生产 TTL 24h 自然过期） */
    private void resetToggleCounter(Ctx c) {
        redissonClient.getAtomicLong("batch:toggle:" + c.tenantId()).delete();
    }

    /** 直接 addStock/deductStock 造历史流水（refDocNo 供时间戳定位） */
    private void seedStock(Ctx c, int qty, String refDocNo) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).palletQty(0).refDocNo(refDocNo).operatorUserId(c.wkUserId()).build());
    }

    private void deduct(Ctx c, int qty, String refDocNo) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo(refDocNo).operatorUserId(c.wkUserId()).build());
    }

    /** 强制指定流水的 created_at（H2/MySQL 秒级精度下确保推算切割点比较确定性） */
    private void forceMovementTime(Ctx c, String refDocNo, LocalDateTime at) {
        stockMovementMapper.update(null, new LambdaUpdateWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(StockMovement::getRefDocNo, refDocNo)
                .set(StockMovement::getCreatedAt, at));
    }

    private LocalDateTime enabledAt(Ctx c) {
        return tenantService.getBatchConfig(c.tenantId()).getBatchEnabledAt();
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

    private InboundSubmitDto submitDto(Ctx c, InboundSubmitDto.Item... items) {
        InboundSubmitDto d = new InboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setItems(List.of(items));
        return d;
    }

    /** 正向链全程：提交（带批次）→ 受理 → 登记 → CONFIRMED。 */
    private InboundRequestVo submitAcceptRegister(Ctx c, int qty, String batchNo,
                                                  LocalDate prod, LocalDate exp, Boolean expiredConfirmed) {
        asWa(c);
        InboundRequestVo vo = inboundRequestService.submitByWa(
                submitDto(c, item(c, qty, batchNo, prod, exp)), c.waUserId()).get(0);
        asWk(c);
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
        InboundForwardRegisterDto reg = new InboundForwardRegisterDto();
        reg.setActualQty(qty);
        reg.setExpiredConfirmed(expiredConfirmed);
        return inboundRequestService.registerForwardByWk(vo.getId(), reg, c.wkUserId());
    }

    private Batch batchByNo(Ctx c, String batchNo) {
        return batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId())
                .eq(Batch::getSkuId, c.skuId())
                .eq(Batch::getBatchNo, batchNo));
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

    // ==================== 开关（13 §3.5） ====================

    @Test
    @DisplayName("关→启：默认批次吸收存量在库（initial=当刻池 qty 快照，expiry NULL 可补录）")
    void enableGeneratesDefaultBatchAbsorbingStock() {
        Ctx c = seedAll();
        seedStock(c, 100, "HIST-IN-1");
        deduct(c, 30, "HIST-OUT-1");

        BatchToggleVo vo = enableBatch(c);
        assertThat(vo.getBatchEnabled()).isEqualTo(1);
        assertThat(vo.getBatchEnabledAt()).isNotNull();
        assertThat(vo.getDefaultBatchCount()).isEqualTo(1);

        List<Batch> batches = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId()));
        assertThat(batches).hasSize(1);
        Batch def = batches.get(0);
        assertThat(def.getSource()).isEqualTo(Batch.SOURCE_DEFAULT);
        assertThat(def.getBatchNo()).startsWith("DEFAULT-");
        assertThat(def.getInitialQty()).isEqualTo(70);
        assertThat(def.getRemainingQty()).isEqualTo(70);
        assertThat(def.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);
        assertThat(def.getExpiryDate()).isNull();
    }

    @Test
    @DisplayName("开启幂等：同态重复调用不计次、不重复生成默认批次、batch_enabled_at 不动")
    void enableIsIdempotent() {
        Ctx c = seedAll();
        seedStock(c, 50, "HIST-IN-1");
        BatchToggleVo first = enableBatch(c);
        LocalDateTime firstAt = first.getBatchEnabledAt();

        // 同态重复（无 confirmed 也放行——幂等空转不翻转不需要凭据）
        BatchToggleVo again = batchService.toggle(c.taUserId(), toggleDto(true, null));
        assertThat(again.getBatchEnabled()).isEqualTo(1);
        assertThat(again.getDefaultBatchCount()).isZero();
        assertThat(again.getBatchEnabledAt()).isEqualTo(firstAt);
        assertThat(batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId()))).isEqualTo(1);
        // 幂等空转不计次：仍可再做一次真实翻转（关）+ 一次开（共 2 次真实操作后才限流）
        assertThat(redissonClient.getAtomicLong("batch:toggle:" + c.tenantId()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("真实翻转缺二次确认凭据 → 40003；24h 第 3 次真实操作 → 50361")
    void toggleConfirmAndRateLimit() {
        Ctx c = seedAll();
        asTa(c);
        assertThat(errCode(() -> batchService.toggle(c.taUserId(), toggleDto(true, null))))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode());

        batchService.toggle(c.taUserId(), toggleDto(true, true));   // 1
        batchService.toggle(c.taUserId(), toggleDto(false, true));  // 2
        assertThat(errCode(() -> batchService.toggle(c.taUserId(), toggleDto(true, true))))
                .isEqualTo(ErrorCode.BATCH_TOGGLE_RATE_LIMITED.getCode());
    }

    @Test
    @DisplayName("启→关冻结 CLOSED；再启用生成新默认批次（同日后缀），不复活 CLOSED")
    void disableFreezesAndReenableCreatesNewDefault() {
        Ctx c = seedAll();
        seedStock(c, 40, "HIST-IN-1");
        enableBatch(c);
        Batch firstDefault = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId())).get(0);

        asTa(c);
        BatchToggleVo off = batchService.toggle(c.taUserId(), toggleDto(false, true));
        assertThat(off.getClosedBatchCount()).isEqualTo(1);
        assertThat(batchMapper.selectById(firstDefault.getId()).getStatus()).isEqualTo(Batch.STATUS_CLOSED);

        resetToggleCounter(c);
        BatchToggleVo on2 = batchService.toggle(c.taUserId(), toggleDto(true, true));
        assertThat(on2.getDefaultBatchCount()).isEqualTo(1);
        List<Batch> all = batchMapper.selectList(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId()).orderByAsc(Batch::getCreatedAt));
        assertThat(all).hasSize(2);
        // 旧默认批次保持 CLOSED 不复活；新默认批次同日 uk 冲突走后缀
        assertThat(all.get(0).getStatus()).isEqualTo(Batch.STATUS_CLOSED);
        Batch second = all.get(1);
        assertThat(second.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);
        assertThat(second.getBatchNo()).isNotEqualTo(firstDefault.getBatchNo());
        assertThat(second.getBatchNo()).startsWith("DEFAULT-");
    }

    // ==================== 登记簿追加 + 零侵入（13 §3.1，关卡） ====================

    @Test
    @DisplayName("登记簿追加断言：登记后批次行追加 + INBOUND 流水 batch_id 回填；库存事务零侵入")
    void registerAppendsBatchRowWithZeroIntrusionOnStockTx() {
        // 开启档
        Ctx on = seedAll();
        enableBatch(on);
        InboundRequestVo confirmed = submitAcceptRegister(on, 80, "B001",
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(180), null);

        Batch b = batchByNo(on, "B001");
        assertThat(b).isNotNull();
        assertThat(b.getSource()).isEqualTo(Batch.SOURCE_INBOUND);
        assertThat(b.getInitialQty()).isEqualTo(80);
        assertThat(b.getRemainingQty()).isEqualTo(80);
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);
        assertThat(b.getProductionDate()).isEqualTo(LocalDate.now().minusDays(10));

        List<StockMovement> onMvs = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, confirmed.getDocNo()));
        assertThat(onMvs).hasSize(1);
        StockMovement onMv = onMvs.get(0);
        assertThat(onMv.getType()).isEqualTo(StockMovement.TYPE_INBOUND);
        assertThat(onMv.getQty()).isEqualTo(80);
        assertThat(onMv.getBatchId()).isEqualTo(b.getId());

        // 关闭档对照：同链路流水形态完全一致（仅 batch_id 恒 NULL、无批次行）——入库事务零侵入
        Ctx off = seedAll();
        InboundRequestVo offConfirmed = submitAcceptRegister(off, 80, null, null, null, null);
        List<StockMovement> offMvs = stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, offConfirmed.getDocNo()));
        assertThat(offMvs).hasSize(1);
        StockMovement offMv = offMvs.get(0);
        assertThat(offMv.getType()).isEqualTo(StockMovement.TYPE_INBOUND);
        assertThat(offMv.getQty()).isEqualTo(onMv.getQty());
        assertThat(offMv.getPalletDelta()).isEqualTo(onMv.getPalletDelta());
        assertThat(offMv.getBatchId()).isNull();
        assertThat(batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, off.tenantId()))).isZero();
        // 两档库存终值一致
        assertThat(inventoryService.queryInventory(on.wholesalerId(), on.skuId()).get(0).getQty()).isEqualTo(80);
        assertThat(inventoryService.queryInventory(off.wholesalerId(), off.skuId()).get(0).getQty()).isEqualTo(80);
    }

    @Test
    @DisplayName("代建登记（registerByWk）同样追加登记簿行 + 回填 batch_id")
    void wkCreatedRegisterAppendsBatchRow() {
        Ctx c = seedAll();
        enableBatch(c);
        asWk(c);
        InboundRegisterDto dto = new InboundRegisterDto();
        dto.setWholesalerId(c.wholesalerId());
        dto.setSkuId(c.skuId());
        dto.setQty(30);
        dto.setBatchNo("WK-B1");
        dto.setProductionDate(LocalDate.now().minusDays(5));
        dto.setExpiryDate(LocalDate.now().plusDays(90));
        InboundRequestVo vo = inboundRequestService.registerByWk(dto, c.wkUserId());

        Batch b = batchByNo(c, "WK-B1");
        assertThat(b).isNotNull();
        assertThat(b.getInitialQty()).isEqualTo(30);
        StockMovement mv = stockMovementMapper.selectOne(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getRefDocNo, vo.getDocNo()));
        assertThat(mv.getBatchId()).isEqualTo(b.getId());

        // 代建缺批次字段（开关启用时必填）→ 40003
        InboundRegisterDto missing = new InboundRegisterDto();
        missing.setWholesalerId(c.wholesalerId());
        missing.setSkuId(c.skuId());
        missing.setQty(10);
        assertThat(errCode(() -> inboundRequestService.registerByWk(missing, c.wkUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode());
    }

    // ==================== 批次校验（40205/40206/50362/50364） ====================

    @Test
    @DisplayName("批次三字段校验：生产日期晚于今天 40205 / 到效期不晚于生产 40206 / 缺字段 40003")
    void batchFieldValidation() {
        Ctx c = seedAll();
        enableBatch(c);
        asWa(c);
        LocalDate today = LocalDate.now();

        assertThat(errCode(() -> inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, "BX", today.plusDays(1), today.plusDays(30))), c.waUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BUSINESS_005.getCode());
        assertThat(errCode(() -> inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, "BX", today.minusDays(10), today.minusDays(10))), c.waUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BUSINESS_006.getCode());
        assertThat(errCode(() -> inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, null, today.minusDays(10), today.plusDays(30))), c.waUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_003.getCode());
        // 开关关闭档零校验回归：批次字段留空可提交
        Ctx off = seedAll();
        asWa(off);
        Assertions.assertDoesNotThrow(() -> inboundRequestService.submitByWa(
                submitDto(off, item(off, 10, null, null, null)), off.waUserId()));
    }

    @Test
    @DisplayName("批次号唯一 50362：同批提交内重复 / 已登记批次再提交预检 / 并发窗口 uk 兜底整体回滚")
    void batchNoDuplicate() {
        Ctx c = seedAll();
        enableBatch(c);
        LocalDate prod = LocalDate.now().minusDays(3);
        LocalDate exp = LocalDate.now().plusDays(200);

        // 同批提交内 (sku, batchNo) 重复
        asWa(c);
        assertThat(errCode(() -> inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, "DUP", prod, exp), item(c, 20, "DUP", prod, exp)), c.waUserId())))
                .isEqualTo(ErrorCode.BATCH_NO_DUPLICATE.getCode());

        // 登记落簿后，同批次号再提交 → 预检 50362
        submitAcceptRegister(c, 50, "DUP", prod, exp, null);
        asWa(c);
        assertThat(errCode(() -> inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, "DUP", prod, exp)), c.waUserId())))
                .isEqualTo(ErrorCode.BATCH_NO_DUPLICATE.getCode());

        // 提交时未占用、登记时已被占用（并发窗口）→ uk 兜底 50362，登记整体回滚保持 ACCEPTED
        asWa(c);
        InboundRequestVo racing = inboundRequestService.submitByWa(
                submitDto(c, item(c, 10, "RACE", prod, exp)), c.waUserId()).get(0);
        asWk(c);
        inboundRequestService.acceptByWk(racing.getId(), c.wkUserId());
        // 竞争者先落簿
        submitAcceptRegisterOtherDoc(c, "RACE", prod, exp);
        InboundForwardRegisterDto reg = new InboundForwardRegisterDto();
        reg.setActualQty(10);
        int stockBefore = inventoryService.queryInventory(c.wholesalerId(), c.skuId()).get(0).getQty();
        assertThat(errCode(() -> inboundRequestService.registerForwardByWk(racing.getId(), reg, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NO_DUPLICATE.getCode());
        InboundRequest after = inboundRequestMapper.selectById(racing.getId());
        assertThat(after.getStatus()).isEqualTo(InboundRequest.STATUS_ACCEPTED);
        assertThat(inventoryService.queryInventory(c.wholesalerId(), c.skuId()).get(0).getQty())
                .isEqualTo(stockBefore);
    }

    /** 绕过 submit 预检直造 RACE 批次行（模拟并发竞争者已落簿）。 */
    private void submitAcceptRegisterOtherDoc(Ctx c, String batchNo, LocalDate prod, LocalDate exp) {
        Batch b = new Batch();
        b.setId(snowflakeIdUtil.nextId());
        b.setTenantId(c.tenantId());
        b.setWholesalerId(c.wholesalerId());
        b.setSkuId(c.skuId());
        b.setBatchNo(batchNo);
        b.setProductionDate(prod);
        b.setExpiryDate(exp);
        b.setInitialQty(5);
        b.setRemainingQty(5);
        b.setStatus(Batch.STATUS_IN_STOCK);
        b.setSource(Batch.SOURCE_INBOUND);
        batchMapper.insert(b);
    }

    @Test
    @DisplayName("过期批次强警告 50364：无凭据拒登记；expiredConfirmed=true 放行且入库即 EXPIRING")
    void expiredBatchRequiresConfirm() {
        Ctx c = seedAll();
        enableBatch(c);
        LocalDate prod = LocalDate.now().minusDays(100);
        LocalDate expired = LocalDate.now().minusDays(1);

        asWa(c);
        InboundRequestVo vo = inboundRequestService.submitByWa(
                submitDto(c, item(c, 20, "EXP-1", prod, expired)), c.waUserId()).get(0);
        asWk(c);
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());
        InboundForwardRegisterDto noConfirm = new InboundForwardRegisterDto();
        noConfirm.setActualQty(20);
        assertThat(errCode(() -> inboundRequestService.registerForwardByWk(vo.getId(), noConfirm, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_EXPIRED_CONFIRM_REQUIRED.getCode());

        InboundForwardRegisterDto withConfirm = new InboundForwardRegisterDto();
        withConfirm.setActualQty(20);
        withConfirm.setExpiredConfirmed(true);
        inboundRequestService.registerForwardByWk(vo.getId(), withConfirm, c.wkUserId());
        Batch b = batchByNo(c, "EXP-1");
        // 过期（≤阈值窗口内）→ 入库后立即进入临期列表（PRD §3.2 临期档口径）
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_EXPIRING);
    }

    // ==================== FIFO 推算三场景（13 §3.2，关卡） ====================

    @Test
    @DisplayName("FIFO 场景一（单批）：出库消耗 + GAIN 抵扣净出；幂等重跑")
    void fifoSingleBatch() {
        Ctx c = seedAll();
        enableBatch(c);
        LocalDateTime at = enabledAt(c);
        submitAcceptRegister(c, 100, "F1", LocalDate.now().minusDays(1), LocalDate.now().plusDays(300), null);

        deduct(c, 30, "OUT-A");
        forceMovementTime(c, "OUT-A", at.plusSeconds(60));
        // GAIN（盘盈，无批次入量）先行抵扣净出：poolNetOut = 30 − 10 = 20
        inventoryService.gainStock(GainStockContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(10).refDocNo("GAIN-A").operatorUserId(c.wkUserId()).build());
        forceMovementTime(c, "GAIN-A", at.plusSeconds(61));

        BatchRecalcResultVo r1 = batchService.recalcTenant(c.tenantId());
        assertThat(r1.getScannedBatches()).isEqualTo(1);
        Batch b = batchByNo(c, "F1");
        assertThat(b.getRemainingQty()).isEqualTo(80);
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);

        // 幂等重跑：结果不变
        batchService.recalcTenant(c.tenantId());
        assertThat(batchByNo(c, "F1").getRemainingQty()).isEqualTo(80);
    }

    @Test
    @DisplayName("FIFO 场景二（跨批分摊）：expiry ASC 先耗，NULLS LAST 默认批次垫底")
    void fifoCrossBatchAllocation() {
        Ctx c = seedAll();
        // 历史存量 20 → 启用吸收为默认批次（expiry NULL 垫底）
        seedStock(c, 20, "HIST-IN-1");
        enableBatch(c);
        LocalDateTime at = enabledAt(c);
        forceMovementTime(c, "HIST-IN-1", at.minusSeconds(60));

        submitAcceptRegister(c, 50, "NEAR", LocalDate.now().minusDays(1), LocalDate.now().plusDays(60), null);
        submitAcceptRegister(c, 50, "FAR", LocalDate.now().minusDays(1), LocalDate.now().plusDays(300), null);

        // 净出 105：NEAR(60d) 50 → FAR(300d) 50 → 默认批次(NULL 垫底) 5
        deduct(c, 105, "OUT-B");
        forceMovementTime(c, "OUT-B", at.plusSeconds(60));

        batchService.recalcTenant(c.tenantId());
        assertThat(batchByNo(c, "NEAR").getRemainingQty()).isZero();
        assertThat(batchByNo(c, "NEAR").getStatus()).isEqualTo(Batch.STATUS_SOLD_OUT);
        assertThat(batchByNo(c, "FAR").getRemainingQty()).isZero();
        assertThat(batchByNo(c, "FAR").getStatus()).isEqualTo(Batch.STATUS_SOLD_OUT);
        Batch def = batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId())
                .eq(Batch::getSource, Batch.SOURCE_DEFAULT));
        assertThat(def.getRemainingQty()).isEqualTo(15);
        assertThat(def.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK);
    }

    @Test
    @DisplayName("FIFO 场景三（切割点）：启用前历史流水被默认批次吸收，不重复扣抵")
    void fifoCutoffHistoryAbsorbedByDefaultBatch() {
        Ctx c = seedAll();
        seedStock(c, 100, "HIST-IN-1");
        deduct(c, 30, "HIST-OUT-1");
        enableBatch(c);
        LocalDateTime at = enabledAt(c);
        // 历史流水强制早于切割点；启用后新增净出 20
        forceMovementTime(c, "HIST-IN-1", at.minusSeconds(120));
        forceMovementTime(c, "HIST-OUT-1", at.minusSeconds(60));
        deduct(c, 20, "OUT-C");
        forceMovementTime(c, "OUT-C", at.plusSeconds(60));

        batchService.recalcTenant(c.tenantId());
        Batch def = batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTenantId, c.tenantId()));
        // 默认批次快照 70；仅扣启用后的 20 → 50（若历史 30 被重复吃则为 20——切割失败）
        assertThat(def.getInitialQty()).isEqualTo(70);
        assertThat(def.getRemainingQty()).isEqualTo(50);

        // 下钻「无批次在池量」= 账面 50 − Σ推算剩余 50 = 0
        asWk(c);
        BatchListVo list = batchService.listForTenant(c.tenantId(), c.wkUserId(),
                c.wholesalerId(), c.skuId(), null);
        assertThat(list.getUnpooledQty()).isZero();
        assertThat(list.getList()).hasSize(1);
    }

    @Test
    @DisplayName("推算不触已冻结/未启用租户空转：关后 recalc 扫 0 批")
    void recalcSkipsDisabledTenant() {
        Ctx c = seedAll();
        seedStock(c, 10, "HIST-IN-1");
        enableBatch(c);
        asTa(c);
        batchService.toggle(c.taUserId(), toggleDto(false, true));
        BatchRecalcResultVo r = batchService.recalcTenant(c.tenantId());
        assertThat(r.getScannedBatches()).isZero();
    }

    // ==================== T1 收尾两项（关卡） ====================

    @Test
    @DisplayName("T1-①：提交附件（≤5，N2 白名单）落申请单；外链拒 50340；>5 拒 40001")
    void submitAttachmentsPersistedToRequests() {
        Ctx c = seedAll();
        asWa(c);
        List<String> urls = List.of(fakeUrl(), fakeUrl());
        InboundSubmitDto dto = submitDto(c, item(c, 10, null, null, null), item(c, 20, null, null, null));
        dto.setAttachments(urls);
        List<InboundRequestVo> created = inboundRequestService.submitByWa(dto, c.waUserId());
        assertThat(created).hasSize(2);
        for (InboundRequestVo vo : created) {
            assertThat(vo.getAttachments()).containsExactlyElementsOf(urls);
            InboundRequest row = inboundRequestMapper.selectById(vo.getId());
            assertThat(row.getAttachments()).isNotBlank();
        }

        // N2 白名单：外链拒 50340
        InboundSubmitDto evil = submitDto(c, item(c, 10, null, null, null));
        evil.setAttachments(List.of("https://evil.example.com/x.jpg"));
        assertThat(errCode(() -> inboundRequestService.submitByWa(evil, c.waUserId())))
                .isEqualTo(ErrorCode.FILE_UPLOAD_INVALID.getCode());

        // 超 5 张拒 40001
        InboundSubmitDto tooMany = submitDto(c, item(c, 10, null, null, null));
        tooMany.setAttachments(List.of(fakeUrl(), fakeUrl(), fakeUrl(), fakeUrl(), fakeUrl(), fakeUrl()));
        assertThat(errCode(() -> inboundRequestService.submitByWa(tooMany, c.waUserId())))
                .isEqualTo(ErrorCode.VALIDATION_BASIC_001.getCode());
    }

    @Test
    @DisplayName("T1-②：ACCEPTED→REJECTED 可达（受理后驳回+通知沿用）；REJECTED→ACCEPTED 红线仍不可达")
    void acceptedCanBeRejected() {
        Ctx c = seedAll();
        asWa(c);
        InboundRequestVo vo = inboundRequestService.submitByWa(
                submitDto(c, item(c, 100, null, null, null)), c.waUserId()).get(0);
        asWk(c);
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());

        // 实收差异 >5% 场景：受理后驳回
        InboundRejectDto reject = new InboundRejectDto();
        reject.setReason("QTY");
        reject.setRemark("实收 80 件与申请 100 件差异超 5%，请重新申请");
        InboundRequestVo rejected = inboundRequestService.rejectByWk(vo.getId(), reject, c.wkUserId());
        assertThat(rejected.getStatus()).isEqualTo(InboundRequest.STATUS_REJECTED);

        // 驳回通知沿用（TYPE_INBOUND_REJECTED → 归属 WA）
        List<Notification> notices = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, Notification.TYPE_INBOUND_REJECTED)
                .eq(Notification::getRefId, vo.getId()));
        assertThat(notices).isNotEmpty();
        assertThat(notices.get(0).getRecipientUserId()).isEqualTo(c.waUserId());

        // 红线不破：REJECTED→ACCEPTED 仍 50330
        assertThat(errCode(() -> inboundRequestService.acceptByWk(vo.getId(), c.wkUserId())))
                .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID.getCode());
        // 全程零库存
        assertThat(inventoryService.queryInventory(c.wholesalerId(), c.skuId())).isEmpty();
    }
}
