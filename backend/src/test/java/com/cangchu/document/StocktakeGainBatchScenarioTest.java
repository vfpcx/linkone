package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.CountSheetCreateDto;
import com.cangchu.document.dto.CountSheetDecideDto;
import com.cangchu.document.dto.CountSheetItemDto;
import com.cangchu.document.entity.CountSheet;
import com.cangchu.document.entity.CountSheetItem;
import com.cangchu.document.mapper.CountSheetItemMapper;
import com.cangchu.document.mapper.CountSheetMapper;
import com.cangchu.document.service.CountSheetService;
import com.cangchu.document.vo.CountSheetVo;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
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
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 顺延（13 §5.2 注 7 / 08 §2.2 D24 / 99-open-questions 2026-05 选 A）：盘点批次分支——盘盈按批入库。
 *
 * <p>盘点单对商户一行一个 SKU、同一 (w,sku) 在途至多一张盘点单，故盘盈 GAIN 流水一单一唯一；
 * 审批通过时以明细盘盈批次三字段（gainBatchNo/gainProductionDate/gainExpiryDate）建批次登记簿行
 * （source=STOCKTAKE、initial_qty=审批 diff）并回填 GAIN 流水 batch_id——FIFO 推算按 initial_qty
 * 吃进，盘盈不再混入「无批次在池量」。沿 StocktakeChainScenarioTest 基建（mapper seed + TenantContext）。
 *
 * <p>覆盖（V41 契约）：
 * <ul>
 *   <li>GB-01 批次开启盘盈按批审批通过：库存/托盘、GAIN 流水回填 batch_id、STOCKTAKE 批次行
 *       （initial_qty=diff、临期 → EXPIRING）、明细 VO 留存（appliedDiff/gainBatchNo/palletDelta）。</li>
 *   <li>GB-02 开关关闭拦截：审批拒绝 50355 且 CAS/库存/通知整体回滚（仍待审批、零流水）。</li>
 *   <li>GB-03 批次号撞既有行 → 50362 整体回滚（gainStock 已回滚）。</li>
 *   <li>GB-04 盘亏行/GB-05 字段规则（缺批次号/缺效期/生产晚于今天 40205/效期早于生产 40206）。</li>
 *   <li>GB-06 空仓盘盈按批 + 出库 FIFO 逐批吃进至 SOLD_OUT（推算联动，无默认批次干扰）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class StocktakeGainBatchScenarioTest {

    @Autowired
    private CountSheetService countSheetService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private CountSheetMapper countSheetMapper;
    @Autowired
    private CountSheetItemMapper countSheetItemMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private BatchMapper batchMapper;
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
    private RedissonClient redissonClient;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed 工具（StocktakeChainScenarioTest 同构） ====================

    private record Ctx(long tenantId, long taUserId, long wholesalerId, long waUserId, long skuId, long wkUserId) {
    }

    private Ctx seedAll() {
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
        return new Ctx(tenantId, taUserId, w.getId(), waUserId, skuId, wkUserId);
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

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    /** 开启批次并清零 24h 计数（跨场景测试基建；生产 TTL 24h 自然过期）。 */
    private void enableBatch(Ctx c) {
        asTa(c);
        BatchToggleDto d = new BatchToggleDto();
        d.setEnable(true);
        d.setConfirmed(true);
        batchService.toggle(c.taUserId(), d);
        redissonClient.getAtomicLong("batch:toggle:" + c.tenantId()).delete();
    }

    private void seedStock(Ctx c, int qty, int pallet) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).palletQty(pallet).refDocNo("WK-SEED-" + snowflakeIdUtil.nextId())
                .operatorUserId(c.wkUserId()).build());
    }

    private void deduct(Ctx c, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo("CK-GB-" + snowflakeIdUtil.nextId()).operatorUserId(c.wkUserId()).build());
    }

    private int qtyOf(Ctx c) {
        List<InventoryVo> list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private int palletOf(Ctx c) {
        List<InventoryVo> list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? 0 : list.get(0).getPalletQty();
    }

    private List<StockMovement> gains(Ctx c) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(StockMovement::getType, StockMovement.TYPE_GAIN));
    }

    private CountSheetItemDto item(long skuId, int actualQty, Integer palletDelta, String remark,
                                   String gainBatchNo, LocalDate gainProd, LocalDate gainExp) {
        CountSheetItemDto d = new CountSheetItemDto();
        d.setSkuId(skuId);
        d.setActualQty(actualQty);
        d.setPalletDelta(palletDelta);
        d.setRemark(remark);
        d.setGainBatchNo(gainBatchNo);
        d.setGainProductionDate(gainProd);
        d.setGainExpiryDate(gainExp);
        return d;
    }

    private CountSheetVo createSheet(Ctx c, CountSheetItemDto... items) {
        asWk(c);
        CountSheetCreateDto dto = new CountSheetCreateDto();
        dto.setWholesalerId(c.wholesalerId());
        dto.setItems(List.of(items));
        return countSheetService.createByWk(dto, c.wkUserId());
    }

    private CountSheetVo createAndSubmit(Ctx c, CountSheetItemDto... items) {
        CountSheetVo vo = createSheet(c, items);
        return countSheetService.submitByWk(vo.getId(), c.wkUserId());
    }

    private CountSheetVo decide(Ctx c, long sheetId, String conclusion, String remark) {
        asTa(c);
        CountSheetDecideDto d = new CountSheetDecideDto();
        d.setConclusion(conclusion);
        d.setRemark(remark);
        return countSheetService.decideByTa(sheetId, d, c.taUserId());
    }

    private Batch batchOf(Ctx c, String batchNo) {
        return batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId())
                .eq(Batch::getSkuId, c.skuId())
                .eq(Batch::getBatchNo, batchNo));
    }

    private BizException expectBiz(org.junit.jupiter.api.function.Executable e) {
        return Assertions.assertThrows(BizException.class, e);
    }

    // ==================== GB 用例 ====================

    @Test
    @DisplayName("GB-01 盘盈按批审批通过：GAIN 流水回填批次 + STOCKTAKE 批次行(临期→EXPIRING) + 明细留存 + 推算保持")
    void gainBatchApproved() {
        Ctx c = seedAll();
        seedStock(c, 40, 4);
        enableBatch(c);
        LocalDate today = LocalDate.now();
        LocalDate exp = today.plusDays(5); // 阈值 30 天内 → EXPIRING

        CountSheetVo vo = createAndSubmit(c,
                item(c.skuId(), 46, null, null, "GB-1", today.minusDays(30), exp));
        CountSheetVo decided = decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(decided.getStatus()).isEqualTo(CountSheet.STATUS_APPROVED);

        // 库存 +6、托盘不变
        assertThat(qtyOf(c)).isEqualTo(46);
        assertThat(palletOf(c)).isEqualTo(4);

        // GAIN 流水唯一且已回填 batch_id
        List<StockMovement> g = gains(c);
        assertThat(g).hasSize(1);
        StockMovement gain = g.get(0);
        assertThat(gain.getQty()).isEqualTo(6);
        assertThat(gain.getRefDocNo()).isEqualTo(decided.getDocNo());

        // STOCKTAKE 批次行：initial_qty=diff、生产/效期落列、临期 → EXPIRING
        Batch b = batchOf(c, "GB-1");
        assertThat(b).isNotNull();
        assertThat(b.getSource()).isEqualTo(Batch.SOURCE_STOCKTAKE);
        assertThat(b.getInitialQty()).isEqualTo(6);
        assertThat(b.getRemainingQty()).isEqualTo(6);
        assertThat(b.getProductionDate()).isEqualTo(today.minusDays(30));
        assertThat(b.getExpiryDate()).isEqualTo(exp);
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_EXPIRING);
        assertThat(gain.getBatchId()).isEqualTo(b.getId());

        // 明细 VO 留存（appliedDiff=6 / gainBatchNo / palletDelta 回写 0）
        CountSheetVo detail = countSheetService.getDetail(vo.getId(), c.taUserId());
        assertThat(detail.getItems()).hasSize(1);
        assertThat(detail.getItems().get(0).getAppliedDiff()).isEqualTo(6);
        assertThat(detail.getItems().get(0).getGainBatchNo()).isEqualTo("GB-1");
        assertThat(detail.getItems().get(0).getPalletDelta()).isZero();

        // FIFO 推算保持（无池出）：批次剩余 6 不变
        batchService.recalcTenant(c.tenantId());
        assertThat(batchMapper.selectById(b.getId()).getRemainingQty()).isEqualTo(6);
    }

    @Test
    @DisplayName("GB-02 批次未开启盘盈按批：提交即拒 50355（草稿可改），库存零变动、无批次行")
    void gainBatchRequiresEnabled() {
        Ctx c = seedAll();
        seedStock(c, 40, 4);
        // 不开启批次：草稿期允许录入（开关状态以提交时为准），提交被前端护栏拦截
        CountSheetVo vo = createSheet(c,
                item(c.skuId(), 45, null, null, "GB-2", LocalDate.now().minusDays(10), LocalDate.now().plusDays(60)));
        assertThat(vo.getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);

        BizException e = expectBiz(() -> countSheetService.submitByWk(vo.getId(), c.wkUserId()));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);

        // 仍为草稿（可移除批次重提）、库存零变动、无批次行
        assertThat(countSheetMapper.selectById(vo.getId()).getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);
        assertThat(qtyOf(c)).isEqualTo(40);
        assertThat(batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId()))).isZero();
    }

    @Test
    @DisplayName("GB-03 盘盈批次号撞既有批次行：审批 50362 整体回滚（gainStock 已回滚、在途保留）")
    void gainBatchDuplicateNo() {
        Ctx c = seedAll();
        seedStock(c, 40, 4);
        enableBatch(c);
        Batch exist = new Batch();
        exist.setId(snowflakeIdUtil.nextId());
        exist.setTenantId(c.tenantId());
        exist.setWholesalerId(c.wholesalerId());
        exist.setSkuId(c.skuId());
        exist.setBatchNo("GB-3");
        exist.setInitialQty(100);
        exist.setRemainingQty(100);
        exist.setStatus(Batch.STATUS_IN_STOCK);
        exist.setSource(Batch.SOURCE_INBOUND);
        exist.setCreatedAt(LocalDateTime.now());
        exist.setUpdatedAt(LocalDateTime.now());
        batchMapper.insert(exist);

        CountSheetVo vo = createAndSubmit(c,
                item(c.skuId(), 45, null, null, "GB-3", LocalDate.now().minusDays(10), LocalDate.now().plusDays(60)));
        BizException e = expectBiz(() -> decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BATCH_NO_DUPLICATE);

        // 整体回滚：仍待审批、库存未 +5、同号批次仍仅 1 行
        assertThat(countSheetMapper.selectById(vo.getId()).getStatus()).isEqualTo(CountSheet.STATUS_PENDING_APPROVAL);
        assertThat(qtyOf(c)).isEqualTo(40);
        assertThat(batchMapper.selectCount(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId())
                .eq(Batch::getSkuId, c.skuId())
                .eq(Batch::getBatchNo, "GB-3"))).isEqualTo(1);
    }

    @Test
    @DisplayName("GB-04 盘亏行带盘盈批次字段：建草稿 50355")
    void gainBatchOnLossRejected() {
        Ctx c = seedAll();
        seedStock(c, 40, 4);
        BizException e = expectBiz(() -> createSheet(c,
                item(c.skuId(), 38, null, null, "GB-4", LocalDate.now().minusDays(10), LocalDate.now().plusDays(60))));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
    }

    @Test
    @DisplayName("GB-05 盘盈批次字段规则：缺批次号/缺效期 → 50355；生产晚于今天 40205；效期早于生产 40206")
    void gainBatchFieldRules() {
        Ctx c = seedAll();
        seedStock(c, 40, 4);
        LocalDate today = LocalDate.now();
        // 只给效期缺批次号
        assertThat(expectBiz(() -> createSheet(c,
                item(c.skuId(), 46, null, null, null, today.minusDays(10), today.plusDays(60))))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        // 只给批次号缺效期
        assertThat(expectBiz(() -> createSheet(c,
                item(c.skuId(), 46, null, null, "GB-5", today.minusDays(10), null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        // 生产日期晚于今天
        assertThat(expectBiz(() -> createSheet(c,
                item(c.skuId(), 46, null, null, "GB-6", today.plusDays(1), today.plusDays(60))))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BUSINESS_005);
        // 效期早于生产日期
        assertThat(expectBiz(() -> createSheet(c,
                item(c.skuId(), 46, null, null, "GB-7", today.minusDays(10), today.minusDays(20))))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BUSINESS_006);
        // 三字段齐全放行
        CountSheetVo vo = createSheet(c,
                item(c.skuId(), 46, null, null, "GB-8", today.minusDays(10), today.plusDays(60)));
        assertThat(vo.getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);
    }

    @Test
    @DisplayName("GB-06 空仓盘盈按批（无默认批次干扰）：出库 FIFO 逐批吃进至 SOLD_OUT")
    void gainBatchFifoConsumes() {
        Ctx c = seedAll();
        enableBatch(c); // 空仓启用：无存量 → 无默认批次
        LocalDate today = LocalDate.now();

        CountSheetVo vo = createAndSubmit(c,
                item(c.skuId(), 6, null, null, "GB-9", today.minusDays(10), today.plusDays(120)));
        decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(qtyOf(c)).isEqualTo(6);

        Batch b = batchOf(c, "GB-9");
        assertThat(b.getInitialQty()).isEqualTo(6);
        assertThat(b.getStatus()).isEqualTo(Batch.STATUS_IN_STOCK); // 效期远离阈值

        // 出库 4 → FIFO 吃剩 2
        deduct(c, 4);
        batchService.recalcTenant(c.tenantId());
        assertThat(batchMapper.selectById(b.getId()).getRemainingQty()).isEqualTo(2);

        // 出库余 2 → SOLD_OUT
        deduct(c, 2);
        batchService.recalcTenant(c.tenantId());
        Batch after = batchMapper.selectById(b.getId());
        assertThat(after.getRemainingQty()).isZero();
        assertThat(after.getStatus()).isEqualTo(Batch.STATUS_SOLD_OUT);
    }
}
