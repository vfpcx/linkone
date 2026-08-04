package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.CountSheetCreateDto;
import com.cangchu.document.dto.CountSheetDecideDto;
import com.cangchu.document.dto.CountSheetItemDto;
import com.cangchu.document.dto.CountSheetUpdateDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.ReturnCreateDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.entity.CountSheet;
import com.cangchu.document.entity.CountSheetItem;
import com.cangchu.document.entity.ReturnRequest;
import com.cangchu.document.mapper.CountSheetItemMapper;
import com.cangchu.document.mapper.CountSheetMapper;
import com.cangchu.document.service.CountSheetService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.service.ReturnRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.vo.CountSheetItemVo;
import com.cangchu.document.vo.CountSheetVo;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.document.vo.ReturnRequestVo;
import com.cangchu.document.vo.StocktakeInTransitHintVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.InboundDisputeContext;
import com.cangchu.inventory.dto.DisputeReversalResult;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
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
import java.util.ArrayList;
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
 * P3b T3-W2 盘点链场景测试（13 §6 T3-W2 测试关卡）。
 *
 * <p>覆盖（沿 ReturnChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>PD 矩阵逐格（4×4 全交叉；红线：DRAFT 直批 ❌ / 提交后撤回 ❌ / APPROVED 不可逆）。</li>
 *   <li>50355（空/重复 SKU/实物数&lt;0/托盘负）；50356（在途唯一 + 虚拟线程并发双建 +
 *       REJECTED 重提撞新在途单）；驳回后重建放行。</li>
 *   <li>system_qty 两时点语义：提交时刻快照定格（提交后出库不改 diff）；
 *       生效量审批时刻锁内封顶（D-10/G9）。</li>
 *   <li>盘盈/盘亏双向流水（GAIN/LOSS 的 qty/biz_time/pallet_delta/ref_doc_no）；
 *       盘亏封顶三态（足额/部分被卖/售罄零冲销+差额备注+TA/WK 通知）。</li>
 *   <li>审批 × 出库并发（虚拟线程，同锁串行，锁内封顶不超卖）；托盘释放不打负
 *       （覆盖超池封顶/覆盖 0/池 0/盘盈 +M）；Σpallet_delta ≡ pallet_qty 对账。</li>
 *   <li>驳回不动账 + 改回 DRAFT 重提再审；在途提示条聚合（出库+退货）；R13 未结扩展；
 *       DISPUTE 冲销 × LOSS 交叉（§2.5 快照口径）；代建出库 PALLET_RELEASE（T3-W1 备注 8 收口）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class StocktakeChainScenarioTest {

    @Autowired
    private CountSheetService countSheetService;
    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private ReturnRequestService returnRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private CountSheetMapper countSheetMapper;
    @Autowired
    private CountSheetItemMapper countSheetItemMapper;
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

    // ==================== seed 工具（ReturnChainScenarioTest 同构） ====================

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

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    private void seedStock(Ctx c, long skuId, int qty, int pallet) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(skuId)
                .qty(qty).palletQty(pallet).refDocNo("WK-SEED-" + snowflakeIdUtil.nextId())
                .operatorUserId(c.wkUserId()).build());
    }

    private void deduct(Ctx c, long skuId, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(skuId)
                .qty(qty).refDocNo("CK-TEST").operatorUserId(c.wkUserId()).build());
    }

    private InventoryVo inv(Ctx c, long skuId) {
        List<InventoryVo> list = inventoryService.queryInventory(c.wholesalerId(), skuId);
        return list.isEmpty() ? null : list.get(0);
    }

    private int qtyOf(Ctx c, long skuId) {
        InventoryVo v = inv(c, skuId);
        return v == null ? 0 : v.getQty();
    }

    private int palletOf(Ctx c, long skuId) {
        InventoryVo v = inv(c, skuId);
        return v == null ? 0 : v.getPalletQty();
    }

    private List<StockMovement> movements(Ctx c, long skuId, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, skuId)
                .eq(type != null, StockMovement::getType, type));
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    /** 托盘账不变量（13 §0/05 §3.4）：pallet_qty ≡ Σpallet_delta 且恒 ≥0。 */
    private void assertPalletInvariant(Ctx c, long skuId) {
        long sum = movements(c, skuId, null).stream()
                .mapToLong(m -> m.getPalletDelta() == null ? 0 : m.getPalletDelta()).sum();
        assertThat((long) palletOf(c, skuId)).as("托盘公式不变量").isEqualTo(sum);
        assertThat(sum).isGreaterThanOrEqualTo(0);
    }

    private CountSheetItemDto item(long skuId, int actualQty, Integer palletDelta, String remark) {
        CountSheetItemDto d = new CountSheetItemDto();
        d.setSkuId(skuId);
        d.setActualQty(actualQty);
        d.setPalletDelta(palletDelta);
        d.setRemark(remark);
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

    private List<CountSheetItem> itemsOf(long sheetId) {
        return countSheetItemMapper.selectList(new LambdaQueryWrapper<CountSheetItem>()
                .eq(CountSheetItem::getSheetId, sheetId).orderByAsc(CountSheetItem::getId));
    }

    private BizException expectBiz(org.junit.jupiter.api.function.Executable e) {
        return Assertions.assertThrows(BizException.class, e);
    }

    // ======================================================================
    // PD 状态机矩阵逐格
    // ======================================================================

    @Test
    @DisplayName("T3W2-SM-01 PD 矩阵逐格：4 状态×4 目标全交叉（红线：DRAFT 直批/提交后撤回/APPROVED 不可逆）")
    void stocktakeMatrixExhaustive() {
        Map<String, Set<String>> expected = Map.of(
                CountSheet.STATUS_DRAFT, Set.of(CountSheet.STATUS_PENDING_APPROVAL),
                CountSheet.STATUS_PENDING_APPROVAL, Set.of(
                        CountSheet.STATUS_REJECTED, CountSheet.STATUS_APPROVED),
                CountSheet.STATUS_REJECTED, Set.of(CountSheet.STATUS_DRAFT),
                CountSheet.STATUS_APPROVED, Set.of());
        for (String from : expected.keySet()) {
            for (String to : expected.keySet()) {
                boolean can = DocStateMachine.canGo(DocStateMachine.DocKind.STOCKTAKE, from, to);
                assertThat(can).as("%s -> %s", from, to).isEqualTo(expected.get(from).contains(to));
            }
        }
        // 红线显式重申（13 §2.2）
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.STOCKTAKE,
                CountSheet.STATUS_DRAFT, CountSheet.STATUS_APPROVED)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.STOCKTAKE,
                CountSheet.STATUS_PENDING_APPROVAL, CountSheet.STATUS_DRAFT)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.STOCKTAKE,
                CountSheet.STATUS_APPROVED, CountSheet.STATUS_DRAFT)).isFalse();
    }

    // ======================================================================
    // 建草稿：校验 50355 / 在途唯一 50356 / 权限
    // ======================================================================

    @Test
    @DisplayName("T3W2-CRT-01 建草稿：PD- 前缀+system_qty 预填账面+pending_flag=1；50355 四态；跨商户 SKU 按不存在")
    void createDraftAndValidation() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 40, 4);
        List<StockMovement> baseline = movements(c, c.skuId(), null);

        CountSheetVo vo = createSheet(c, item(c.skuId(), 38, null, "少 2 件"));
        assertThat(vo.getDocNo()).startsWith("PD-");
        assertThat(vo.getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);
        assertThat(vo.getWkUserId()).isEqualTo(c.wkUserId());
        CountSheet row = countSheetMapper.selectById(vo.getId());
        assertThat(row.getPendingFlag()).isEqualTo(1);
        List<CountSheetItem> items = itemsOf(vo.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getSystemQty()).isEqualTo(40); // 预填当刻账面
        assertThat(items.get(0).getDiff()).isEqualTo(-2);
        // 建草稿零库存零流水
        assertThat(qtyOf(c, c.skuId())).isEqualTo(40);
        assertThat(movements(c, c.skuId(), null)).hasSize(baseline.size());

        // 50355：空明细 / 重复 SKU / 实物数<0 / 托盘负
        Ctx d = seedAll();
        asWk(d);
        CountSheetCreateDto empty = new CountSheetCreateDto();
        empty.setWholesalerId(d.wholesalerId());
        empty.setItems(List.of());
        assertThat(expectBiz(() -> countSheetService.createByWk(empty, d.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        assertThat(expectBiz(() -> createSheet(d, item(d.skuId(), 1, null, null), item(d.skuId(), 2, null, null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        assertThat(expectBiz(() -> createSheet(d, item(d.skuId(), -1, null, null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        assertThat(expectBiz(() -> createSheet(d, item(d.skuId(), 1, -1, null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        // 跨商户 SKU → 按不存在（S4）
        assertThat(expectBiz(() -> createSheet(d, item(c.skuId(), 1, null, null)))
                .getErrorCode()).isEqualTo(ErrorCode.SKU_NOT_FOUND);
    }

    @Test
    @DisplayName("T3W2-CRT-02 在途唯一 50356：草稿在途拒二建；驳回/通过后放行；REJECTED 重提撞新在途单 → 50356")
    void openExistsGuard() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 20, 0);
        CountSheetVo first = createSheet(c, item(c.skuId(), 20, null, null));
        // 草稿在途 → 二建 50356
        assertThat(expectBiz(() -> createSheet(c, item(c.skuId(), 19, null, null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        // 提交仍在途 → 50356
        countSheetService.submitByWk(first.getId(), c.wkUserId());
        assertThat(expectBiz(() -> createSheet(c, item(c.skuId(), 19, null, null)))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        // 驳回释放唯一位 → 新建放行
        decide(c, first.getId(), CountSheet.STATUS_REJECTED, "数据存疑");
        CountSheetVo second = createSheet(c, item(c.skuId(), 20, null, null));
        assertThat(second.getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);
        // REJECTED 单编辑重提（pending_flag 回置）撞新在途单 → 50356
        asWk(c);
        CountSheetUpdateDto upd = new CountSheetUpdateDto();
        upd.setItems(List.of(item(c.skuId(), 18, null, null)));
        assertThat(expectBiz(() -> countSheetService.updateByWk(first.getId(), upd, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        // 新单通过（终态释放）后，REJECTED 单可重提
        countSheetService.submitByWk(second.getId(), c.wkUserId());
        decide(c, second.getId(), CountSheet.STATUS_APPROVED, null);
        asWk(c);
        CountSheetVo revived = countSheetService.updateByWk(first.getId(), upd, c.wkUserId());
        assertThat(revived.getStatus()).isEqualTo(CountSheet.STATUS_DRAFT);
    }

    @Test
    @DisplayName("T3W2-CONC-01 并发双建（虚拟线程）：同商户同时建两张——恰一张成功，败方 50356（uk_cs_ws_pending 兜底）")
    void concurrentDoubleCreate() throws Exception {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30, 0);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int actual = 30 - i;
                futures.add(vt.submit(() -> {
                    TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                    start.await();
                    try {
                        CountSheetCreateDto dto = new CountSheetCreateDto();
                        dto.setWholesalerId(c.wholesalerId());
                        dto.setItems(List.of(item(c.skuId(), actual, null, null)));
                        return countSheetService.createByWk(dto, c.wkUserId());
                    } catch (BizException e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            long wins = results.stream().filter(r -> r instanceof CountSheetVo).count();
            long fails = results.stream().filter(r -> r instanceof BizException).count();
            assertThat(wins).as("恰一张成功").isEqualTo(1);
            assertThat(fails).isEqualTo(1);
            BizException loser = (BizException) results.stream()
                    .filter(r -> r instanceof BizException).findFirst().orElseThrow();
            assertThat(loser.getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        }
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
        Long open = countSheetMapper.selectCount(new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getWholesalerId, c.wholesalerId())
                .eq(CountSheet::getStatus, CountSheet.STATUS_DRAFT));
        assertThat(open).isEqualTo(1);
    }

    @Test
    @DisplayName("T3W2-PERM-01 权限矩阵：WA 建/审 42001；WK 审批 42001；TA 建 42001；WA 查列表 42001；TA 查看放行")
    void permissionMatrix() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 10, 0);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 10, null, null));

        asWa(c);
        CountSheetCreateDto dto = new CountSheetCreateDto();
        dto.setWholesalerId(c.wholesalerId());
        dto.setItems(List.of(item(c.skuId(), 9, null, null)));
        assertThat(expectBiz(() -> countSheetService.createByWk(dto, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        CountSheetDecideDto approve = new CountSheetDecideDto();
        approve.setConclusion(CountSheet.STATUS_APPROVED);
        assertThat(expectBiz(() -> countSheetService.decideByTa(vo.getId(), approve, c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        assertThat(expectBiz(() -> countSheetService.listByTenant(c.tenantId(), c.waUserId(), null, null))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        // WK 冒充审批 → 42001（仅 TA，D23 全量审批）
        asWk(c);
        assertThat(expectBiz(() -> countSheetService.decideByTa(vo.getId(), approve, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        // TA 建草稿 → 42001（作业=WK；TA 查看走 requireWkOrTa 放行）
        asTa(c);
        assertThat(expectBiz(() -> countSheetService.createByWk(dto, c.taUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        assertThat(countSheetService.listByTenant(c.tenantId(), c.taUserId(),
                c.wholesalerId(), CountSheet.STATUS_PENDING_APPROVAL))
                .extracting(CountSheetVo::getId).contains(vo.getId());
        assertThat(countSheetService.getDetail(vo.getId(), c.taUserId()).getItems()).hasSize(1);
    }

    // ======================================================================
    // 编辑 / 删除 / 提交快照
    // ======================================================================

    @Test
    @DisplayName("T3W2-EDIT-01 编辑与删除：草稿 items 全量替换；仅草稿可删（提交后删 50330）；删后释放在途唯一位")
    void editAndDeleteDraft() {
        Ctx c = seedAll();
        long sku2 = seedSku(c.tenantId(), c.wholesalerId());
        seedStock(c, c.skuId(), 30, 0);
        seedStock(c, sku2, 8, 0);

        CountSheetVo vo = createSheet(c, item(c.skuId(), 30, null, null));
        CountSheetUpdateDto upd = new CountSheetUpdateDto();
        upd.setRemark("覆盖说明");
        upd.setItems(List.of(item(c.skuId(), 28, null, null), item(sku2, 9, null, null)));
        CountSheetVo updated = countSheetService.updateByWk(vo.getId(), upd, c.wkUserId());
        assertThat(updated.getRemark()).isEqualTo("覆盖说明");
        assertThat(itemsOf(vo.getId())).hasSize(2);

        // 删除草稿 → 释放唯一位可重建
        countSheetService.deleteByWk(vo.getId(), c.wkUserId());
        assertThat(countSheetMapper.selectById(vo.getId())).isNull();
        assertThat(itemsOf(vo.getId())).isEmpty();
        CountSheetVo again = createAndSubmit(c, item(c.skuId(), 30, null, null));
        // 提交后编辑/删除 → 50330（快照定格语义）
        CountSheetUpdateDto upd2 = new CountSheetUpdateDto();
        upd2.setItems(List.of(item(c.skuId(), 1, null, null)));
        assertThat(expectBiz(() -> countSheetService.updateByWk(again.getId(), upd2, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        assertThat(expectBiz(() -> countSheetService.deleteByWk(again.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("T3W2-SNAP-01 两时点语义：system_qty 提交时刻定格（建后出库→提交重快照；提交后出库不改 diff）；通知 TA")
    void submitSnapshotTiming() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 50, 0);
        // 建草稿时账面 50（预填）
        CountSheetVo vo = createSheet(c, item(c.skuId(), 47, null, null));
        assertThat(itemsOf(vo.getId()).get(0).getSystemQty()).isEqualTo(50);
        // 草稿期被出库 10 → 提交时重快照 40，diff=47-40=+7
        deduct(c, c.skuId(), 10);
        asWk(c);
        countSheetService.submitByWk(vo.getId(), c.wkUserId());
        CountSheetItem snap = itemsOf(vo.getId()).get(0);
        assertThat(snap.getSystemQty()).isEqualTo(40);
        assertThat(snap.getDiff()).isEqualTo(7);
        // 提交后再出库 → diff 不变（快照定格；生效量以审批时刻封顶——两时点分离）
        deduct(c, c.skuId(), 5);
        assertThat(itemsOf(vo.getId()).get(0).getDiff()).isEqualTo(7);
        // 提交通知 → 租户管理员（contactUserId）
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_STOCKTAKE_PENDING)).isEqualTo(1);
        // 空明细单不可提交（防御：直插空单）
        Ctx d = seedAll();
        seedStock(d, d.skuId(), 5, 0);
        CountSheetVo empty = createSheet(d, item(d.skuId(), 5, null, null));
        countSheetItemMapper.delete(new LambdaQueryWrapper<CountSheetItem>()
                .eq(CountSheetItem::getSheetId, empty.getId()));
        assertThat(expectBiz(() -> countSheetService.submitByWk(empty.getId(), d.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCKTAKE_ITEMS_INVALID);
    }

    // ======================================================================
    // 审批：驳回不动账 / 盘盈盘亏双向流水
    // ======================================================================

    @Test
    @DisplayName("T3W2-REJ-01 驳回不动账：理由必填 40003；零库存零流水；REJECTED→DRAFT 重提再审可通过；并发双裁败方 50331")
    void rejectKeepsBooks() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 25, 2);
        List<StockMovement> baseline = movements(c, c.skuId(), null);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 20, null, null));

        // 驳回缺理由 → 40003
        asTa(c);
        CountSheetDecideDto noRemark = new CountSheetDecideDto();
        noRemark.setConclusion(CountSheet.STATUS_REJECTED);
        assertThat(expectBiz(() -> countSheetService.decideByTa(vo.getId(), noRemark, c.taUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);
        // 非法结论 → 40001
        CountSheetDecideDto bad = new CountSheetDecideDto();
        bad.setConclusion("MAYBE");
        assertThat(expectBiz(() -> countSheetService.decideByTa(vo.getId(), bad, c.taUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_001);

        CountSheetVo rejected = decide(c, vo.getId(), CountSheet.STATUS_REJECTED, "先核对在途");
        assertThat(rejected.getStatus()).isEqualTo(CountSheet.STATUS_REJECTED);
        assertThat(rejected.getRejectRemark()).isEqualTo("先核对在途");
        assertThat(countSheetMapper.selectById(vo.getId()).getPendingFlag()).isNull();
        // 驳回不动账：零库存变化、零新增流水、明细 applied_diff 恒 NULL
        assertThat(qtyOf(c, c.skuId())).isEqualTo(25);
        assertThat(palletOf(c, c.skuId())).isEqualTo(2);
        assertThat(movements(c, c.skuId(), null)).hasSize(baseline.size());
        assertThat(itemsOf(vo.getId()).get(0).getAppliedDiff()).isNull();
        // 结论通知 → 发起 WK
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isEqualTo(1);
        // 已驳回不可再裁 → 50330
        assertThat(expectBiz(() -> decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 重提再审可通过（改正后 DRAFT 重提先例）
        asWk(c);
        CountSheetUpdateDto upd = new CountSheetUpdateDto();
        upd.setItems(List.of(item(c.skuId(), 24, null, null)));
        countSheetService.updateByWk(vo.getId(), upd, c.wkUserId());
        countSheetService.submitByWk(vo.getId(), c.wkUserId());
        CountSheetVo approved = decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(approved.getStatus()).isEqualTo(CountSheet.STATUS_APPROVED);
        assertThat(approved.getTaUserId()).isEqualTo(c.taUserId());
        assertThat(approved.getDecidedAt()).isNotNull();
        // 盘亏 1 件生效（25→24）
        assertThat(qtyOf(c, c.skuId())).isEqualTo(24);
        // APPROVED 终态不可逆
        assertThat(expectBiz(() -> decide(c, vo.getId(), CountSheet.STATUS_REJECTED, "反悔"))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("T3W2-APR-01 盘盈/盘亏双向流水：一单两 SKU——GAIN(+2, 托盘+1)/LOSS(-3, 默认比例托盘)；锚点与回写断言")
    void approveGainAndLoss() {
        Ctx c = seedAll();
        long sku2 = seedSku(c.tenantId(), c.wholesalerId());
        seedStock(c, c.skuId(), 10, 0);   // 盘盈：账面 10 实物 12
        seedStock(c, sku2, 30, 6);        // 盘亏：账面 30 实物 27
        CountSheetVo vo = createAndSubmit(c,
                item(c.skuId(), 12, 1, "多出两件"),
                item(sku2, 27, null, null));

        CountSheetVo approved = decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(approved.getStatus()).isEqualTo(CountSheet.STATUS_APPROVED);

        // GAIN：qty=2、pallet_delta=+1、ref=PD-、biz_time 落值（盘盈次日起算锚点，零金额）
        List<StockMovement> gains = movements(c, c.skuId(), StockMovement.TYPE_GAIN);
        assertThat(gains).hasSize(1);
        assertThat(gains.get(0).getQty()).isEqualTo(2);
        assertThat(gains.get(0).getPalletDelta()).isEqualTo(1);
        assertThat(gains.get(0).getRefDocNo()).isEqualTo(vo.getDocNo());
        assertThat(gains.get(0).getBizTime()).isNotNull();
        assertThat(qtyOf(c, c.skuId())).isEqualTo(12);
        assertThat(palletOf(c, c.skuId())).isEqualTo(1);

        // LOSS：qty=3、托盘默认比例 ceil(6×3/30)=1、ref=PD-（盘亏当日截止锚点）
        List<StockMovement> losses = movements(c, sku2, StockMovement.TYPE_LOSS);
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getQty()).isEqualTo(3);
        assertThat(losses.get(0).getPalletDelta()).isEqualTo(-1);
        assertThat(losses.get(0).getRefDocNo()).isEqualTo(vo.getDocNo());
        assertThat(qtyOf(c, sku2)).isEqualTo(27);
        assertThat(palletOf(c, sku2)).isEqualTo(5);

        // 明细回写：applied_diff 带符号；pallet_delta 回写生效带符号值
        List<CountSheetItem> items = itemsOf(vo.getId());
        CountSheetItem gainRow = items.stream().filter(i -> i.getSkuId() == c.skuId()).findFirst().orElseThrow();
        CountSheetItem lossRow = items.stream().filter(i -> i.getSkuId() == sku2).findFirst().orElseThrow();
        assertThat(gainRow.getAppliedDiff()).isEqualTo(2);
        assertThat(gainRow.getPalletDelta()).isEqualTo(1);
        assertThat(lossRow.getAppliedDiff()).isEqualTo(-3);
        assertThat(lossRow.getPalletDelta()).isEqualTo(-1);
        // 足额生效：无差额备注
        assertThat(lossRow.getRemark() == null || !lossRow.getRemark().contains("差额")).isTrue();
        assertPalletInvariant(c, c.skuId());
        assertPalletInvariant(c, sku2);
        // 结论通知 → WK（含盘盈/盘亏汇总）
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isEqualTo(1);
    }

    // ======================================================================
    // D-10 盘亏封顶三态
    // ======================================================================

    @Test
    @DisplayName("T3W2-CAP-01 盘亏封顶三态①足额：审批时在库≥盘亏 → 全额生效零差额")
    void lossCapSufficient() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 20, 0);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 15, null, null)); // 盘亏 5
        decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(15);
        List<StockMovement> losses = movements(c, c.skuId(), StockMovement.TYPE_LOSS);
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getQty()).isEqualTo(5);
        assertThat(itemsOf(vo.getId()).get(0).getAppliedDiff()).isEqualTo(-5);
        // 无封顶差额通知（TA 只收提交通知，不收差额提醒）
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isZero();
    }

    @Test
    @DisplayName("T3W2-CAP-02 盘亏封顶三态②部分被卖：盘亏 10 审批时仅剩 4 → 生效 4、差额 6 写明细+单据备注并通知 TA/WK")
    void lossCapPartial() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 30, 0);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 20, null, null)); // 快照 30，盘亏 10
        // 等待审批期间被出库 26 件 → 审批时刻在库仅 4（G9）
        deduct(c, c.skuId(), 26);
        decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);

        assertThat(qtyOf(c, c.skuId())).isZero(); // 4 全额封顶冲销，恒 ≥0 不打负
        List<StockMovement> losses = movements(c, c.skuId(), StockMovement.TYPE_LOSS);
        assertThat(losses).hasSize(1);
        assertThat(losses.get(0).getQty()).isEqualTo(4);
        CountSheetItem row = itemsOf(vo.getId()).get(0);
        assertThat(row.getAppliedDiff()).isEqualTo(-4);
        // 差额文案（PRD §2.2-5）：明细行 + 单据备注
        assertThat(row.getRemark()).contains("盘亏 10 件").contains("已按 4 件生效").contains("差额 6 件");
        assertThat(countSheetMapper.selectById(vo.getId()).getRemark()).contains("盘亏封顶差额");
        // 站内信 TA（差额提醒）+ WK（结论含封顶）双方
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isEqualTo(1);
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isEqualTo(1);
        Notification wkNote = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, c.wkUserId())
                .eq(Notification::getType, Notification.TYPE_STOCKTAKE_DECIDED)).get(0);
        assertThat(wkNote.getContent()).contains("封顶");
    }

    @Test
    @DisplayName("T3W2-CAP-03 盘亏封顶三态③售罄：审批时在库 0 → 零冲销不写流水、applied_diff=0、差额全额备注；单据照常 APPROVED")
    void lossCapSoldOut() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 8, 2);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 5, null, null)); // 盘亏 3
        deduct(c, c.skuId(), 8); // 售罄
        CountSheetVo approved = decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);

        assertThat(approved.getStatus()).isEqualTo(CountSheet.STATUS_APPROVED); // 售罄不阻审批留痕
        assertThat(qtyOf(c, c.skuId())).isZero();
        assertThat(movements(c, c.skuId(), StockMovement.TYPE_LOSS)).isEmpty(); // 零冲销不写流水
        CountSheetItem row = itemsOf(vo.getId()).get(0);
        assertThat(row.getAppliedDiff()).isZero();
        assertThat(row.getPalletDelta()).isZero(); // 零冲销零托盘
        assertThat(row.getRemark()).contains("在库仅 0 件").contains("差额 3 件");
        assertThat(countNotifications(c.taUserId(), Notification.TYPE_STOCKTAKE_DECIDED)).isEqualTo(1);
        assertPalletInvariant(c, c.skuId());
    }

    // ======================================================================
    // 审批 × 出库并发（虚拟线程，锁内封顶）
    // ======================================================================

    @Test
    @DisplayName("T3W2-CONC-02 审批×出库并发（虚拟线程）：在库 10、盘亏 8 × 出库 8 同锁串行——锁内封顶不超卖、qty 恒 ≥0")
    void concurrentApproveVsDeduct() throws Exception {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 10, 0);
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 2, null, null)); // 盘亏 8

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> approveF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
                start.await();
                try {
                    CountSheetDecideDto d = new CountSheetDecideDto();
                    d.setConclusion(CountSheet.STATUS_APPROVED);
                    return countSheetService.decideByTa(vo.getId(), d, c.taUserId());
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
                            .qty(8).refDocNo("CK-RACE").operatorUserId(c.wkUserId()).build());
                } catch (BizException e) {
                    return e;
                } finally {
                    TenantContext.clear();
                }
            });
            start.countDown();
            Object approveR = approveF.get(60, TimeUnit.SECONDS);
            Object deductR = deductF.get(60, TimeUnit.SECONDS);

            // 审批必成功（封顶语义永不因不足失败——D-10 不驳回重盘）
            assertThat(approveR).isInstanceOf(CountSheetVo.class);
            TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
            int lossApplied = movements(c, c.skuId(), StockMovement.TYPE_LOSS).stream()
                    .mapToInt(StockMovement::getQty).sum();
            int appliedDiff = itemsOf(vo.getId()).get(0).getAppliedDiff();
            assertThat(appliedDiff).isEqualTo(-lossApplied);
            if (deductR instanceof BizException loser) {
                // 盘亏先行全额 8 → 剩 2 不够出库 8 → 出库 50251
                assertThat(loser.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
                assertThat(lossApplied).isEqualTo(8);
                assertThat(qtyOf(c, c.skuId())).isEqualTo(2);
            } else {
                // 出库先行 → 审批时刻仅剩 2 → 锁内封顶生效 2、差额 6 备注
                assertThat(lossApplied).isEqualTo(2);
                assertThat(qtyOf(c, c.skuId())).isZero();
                assertThat(itemsOf(vo.getId()).get(0).getRemark()).contains("差额 6 件");
            }
        }
        // 终局对账：qty 恒 ≥0；INBOUND − OUTBOUND − LOSS ≡ 在库
        int in = movements(c, c.skuId(), StockMovement.TYPE_INBOUND).stream().mapToInt(StockMovement::getQty).sum();
        int out = movements(c, c.skuId(), StockMovement.TYPE_OUTBOUND).stream().mapToInt(StockMovement::getQty).sum();
        int loss = movements(c, c.skuId(), StockMovement.TYPE_LOSS).stream().mapToInt(StockMovement::getQty).sum();
        assertThat(qtyOf(c, c.skuId())).isGreaterThanOrEqualTo(0).isEqualTo(in - out - loss);
    }

    // ======================================================================
    // 托盘：不打负 / 覆盖 / 盘盈 +M
    // ======================================================================

    @Test
    @DisplayName("T3W2-PLT-01 托盘不打负：盘亏覆盖超池封顶/覆盖 0/池 0 零释放；全出清零默认释放全部；Σpallet_delta 对账")
    void palletNeverNegative() {
        // ① 覆盖 99 超池 → min(99, 池 3) 封顶
        Ctx a = seedAll();
        seedStock(a, a.skuId(), 20, 3);
        CountSheetVo va = createAndSubmit(a, item(a.skuId(), 15, 99, null)); // 盘亏 5，覆盖 99
        decide(a, va.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(palletOf(a, a.skuId())).isZero();
        assertThat(movements(a, a.skuId(), StockMovement.TYPE_LOSS).get(0).getPalletDelta()).isEqualTo(-3);
        assertThat(itemsOf(va.getId()).get(0).getPalletDelta()).isEqualTo(-3); // 回写封顶后生效值
        assertPalletInvariant(a, a.skuId());

        // ② 覆盖 0（托盘未腾空）→ 零释放
        Ctx b = seedAll();
        seedStock(b, b.skuId(), 20, 3);
        CountSheetVo vb = createAndSubmit(b, item(b.skuId(), 15, 0, null));
        decide(b, vb.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(palletOf(b, b.skuId())).isEqualTo(3);
        assertThat(movements(b, b.skuId(), StockMovement.TYPE_LOSS).get(0).getPalletDelta()).isZero();
        assertPalletInvariant(b, b.skuId());

        // ③ 池 pallet=0 → 默认建议值 0，不打负
        Ctx d = seedAll();
        seedStock(d, d.skuId(), 10, 0);
        CountSheetVo vd = createAndSubmit(d, item(d.skuId(), 6, null, null));
        decide(d, vd.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(palletOf(d, d.skuId())).isZero();
        assertThat(movements(d, d.skuId(), StockMovement.TYPE_LOSS).get(0).getPalletDelta()).isZero();
        assertPalletInvariant(d, d.skuId());

        // ④ 全部盘没（实物 0）→ 全出清零默认释放全部托盘
        Ctx e = seedAll();
        seedStock(e, e.skuId(), 10, 4);
        CountSheetVo ve = createAndSubmit(e, item(e.skuId(), 0, null, null)); // 盘亏 10 全额
        decide(e, ve.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(qtyOf(e, e.skuId())).isZero();
        assertThat(palletOf(e, e.skuId())).isZero();
        assertThat(movements(e, e.skuId(), StockMovement.TYPE_LOSS).get(0).getPalletDelta()).isEqualTo(-4);
        assertPalletInvariant(e, e.skuId());
    }

    // ======================================================================
    // 在途提示条 / 详情预览
    // ======================================================================

    @Test
    @DisplayName("T3W2-HINT-01 在途提示条：已确认未出库(PENDING_ACCEPT/PRINTED)按 SKU 聚合 + 已受理退货；终态不计；跨租户按不存在")
    void inTransitHintAggregation() {
        Ctx c = seedAll();
        long sku2 = seedSku(c.tenantId(), c.wholesalerId());
        seedStock(c, c.skuId(), 100, 0);
        seedStock(c, sku2, 50, 0);

        // 出库在途两张：PENDING_ACCEPT(sku1×10) + PRINTED(sku2×6)
        asWa(c);
        OutboundSubmitDto o1 = new OutboundSubmitDto();
        o1.setWholesalerId(c.wholesalerId());
        o1.setSkuId(c.skuId());
        o1.setQty(10);
        outboundRequestService.submitByWa(o1, c.waUserId());
        OutboundSubmitDto o2 = new OutboundSubmitDto();
        o2.setWholesalerId(c.wholesalerId());
        o2.setSkuId(sku2);
        o2.setQty(6);
        OutboundRequestVo printed = outboundRequestService.submitByWa(o2, c.waUserId());
        asWk(c);
        outboundRequestService.printByWk(printed.getId(), c.wkUserId());
        // 已完成出库一张（不计入在途）
        asWa(c);
        OutboundSubmitDto o3 = new OutboundSubmitDto();
        o3.setWholesalerId(c.wholesalerId());
        o3.setSkuId(c.skuId());
        o3.setQty(4);
        OutboundRequestVo done = outboundRequestService.submitByWa(o3, c.waUserId());
        asWk(c);
        outboundRequestService.printByWk(done.getId(), c.wkUserId());
        outboundRequestService.registerByWk(done.getId(), c.wkUserId());

        // 退货：已受理一张（计入）+ 待受理一张（不计——尚未锁单，账面语义不受 D-7 影响）
        asWa(c);
        ReturnCreateDto r1 = new ReturnCreateDto();
        r1.setSkuId(c.skuId());
        r1.setQty(7);
        ReturnRequestVo accepted = returnRequestService.createByWa(r1, c.waUserId());
        asWk(c);
        returnRequestService.acceptByWk(accepted.getId(), c.wkUserId());
        asWa(c);
        ReturnCreateDto r2 = new ReturnCreateDto();
        r2.setSkuId(sku2);
        r2.setQty(3);
        returnRequestService.createByWa(r2, c.waUserId());

        asWk(c);
        StocktakeInTransitHintVo hint = countSheetService.inTransitHint(
                c.tenantId(), c.wkUserId(), c.wholesalerId());
        assertThat(hint.getOutboundDocCount()).isEqualTo(2);
        assertThat(hint.getOutboundQtyTotal()).isEqualTo(16);
        assertThat(hint.getSkuOutboundQty())
                .containsEntry(String.valueOf(c.skuId()), 10)
                .containsEntry(String.valueOf(sku2), 6);
        assertThat(hint.getReturnDocCount()).isEqualTo(1);
        assertThat(hint.getReturnQtyTotal()).isEqualTo(7);
        assertThat(hint.getSkuReturnQty()).containsOnlyKeys(String.valueOf(c.skuId()));

        // 详情含同源提示条 + 明细封顶预览（currentStock/盘亏默认托盘建议）
        CountSheetVo sheet = createSheet(c, item(c.skuId(), 70, null, null));
        CountSheetVo detail = countSheetService.getDetail(sheet.getId(), c.wkUserId());
        assertThat(detail.getInTransitHint().getOutboundDocCount()).isEqualTo(2);
        CountSheetItemVo row = detail.getItems().get(0);
        assertThat(row.getCurrentStock()).isEqualTo(86); // 100−10−4
        assertThat(row.getSkuName()).isNotBlank();

        // 缺 wholesalerId → 40003；跨租户商户 → 按不存在
        assertThat(expectBiz(() -> countSheetService.inTransitHint(c.tenantId(), c.wkUserId(), null))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);
        Ctx other = seedAll();
        assertThat(expectBiz(() -> countSheetService.inTransitHint(c.tenantId(), c.wkUserId(), other.wholesalerId()))
                .getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_FOUND);
    }

    // ======================================================================
    // R13 / DISPUTE 交叉 / 代建托盘收口
    // ======================================================================

    @Test
    @DisplayName("T3W2-R13-01 未结扩展：DRAFT/PENDING_APPROVAL 计入、REJECTED/APPROVED 不计（阻退驻口径）")
    void openCountExtension() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 10, 0);
        CountSheetVo vo = createSheet(c, item(c.skuId(), 10, null, null));
        assertThat(countSheetService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        countSheetService.submitByWk(vo.getId(), c.wkUserId());
        assertThat(countSheetService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        decide(c, vo.getId(), CountSheet.STATUS_REJECTED, "重盘");
        assertThat(countSheetService.countOpenForWholesaler(c.wholesalerId())).isZero();
        // 重提回到在途 → 1；通过 → 0
        asWk(c);
        CountSheetUpdateDto upd = new CountSheetUpdateDto();
        upd.setItems(List.of(item(c.skuId(), 10, null, null)));
        countSheetService.updateByWk(vo.getId(), upd, c.wkUserId());
        assertThat(countSheetService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        countSheetService.submitByWk(vo.getId(), c.wkUserId());
        decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(countSheetService.countOpenForWholesaler(c.wholesalerId())).isZero();
    }

    @Test
    @DisplayName("T3W2-X-01 交叉（§2.5）：LOSS 使 onhand 变小 → 在途异议冲销以异议时刻剩余封顶（快照口径不追溯）")
    void lossThenDisputeReversalCapped() {
        Ctx c = seedAll();
        // 入库单登记 10 件（INBOUND 流水），随后盘亏 6 件生效 → 在库 4
        String inboundDoc = "WK-XTEST-" + snowflakeIdUtil.nextId();
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(10).palletQty(0).refDocNo(inboundDoc).operatorUserId(c.wkUserId()).build());
        CountSheetVo vo = createAndSubmit(c, item(c.skuId(), 4, null, null)); // 盘亏 6
        decide(c, vo.getId(), CountSheet.STATUS_APPROVED, null);
        assertThat(qtyOf(c, c.skuId())).isEqualTo(4);

        // WA 对原 10 件入库单异议冲销 → 12 §2.4 封顶：reversed=min(10, 4)=4、shortfall=6
        DisputeReversalResult r = inventoryService.reverseInboundForDispute(InboundDisputeContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .registeredQty(10).palletQty(0).refDocNo(inboundDoc).operatorUserId(c.waUserId()).build());
        assertThat(r.getReversedQty()).isEqualTo(4);
        assertThat(r.getShortfallQty()).isEqualTo(6);
        assertThat(qtyOf(c, c.skuId())).isZero(); // 恒 ≥0
        assertPalletInvariant(c, c.skuId());
    }

    @Test
    @DisplayName("T3W2-PROXY-01 代建出库托盘收口（T3-W1 备注 8）：createByWk 同事务默认比例 PALLET_RELEASE；全出清零释放全部")
    void proxyOutboundPalletRelease() {
        Ctx c = seedAll();
        seedStock(c, c.skuId(), 100, 10);
        asWk(c);
        WkOutboundCreateDto dto = new WkOutboundCreateDto();
        dto.setWholesalerId(c.wholesalerId());
        dto.setSkuId(c.skuId());
        dto.setQty(30);
        dto.setConfirmed(true);
        OutboundRequestVo out = outboundRequestService.createByWk(dto, c.wkUserId());
        // 件数扣 30 + 默认比例托盘 ceil(10×30/100)=3 同事务释放（代建直达 COMPLETED，无登记页）
        assertThat(qtyOf(c, c.skuId())).isEqualTo(70);
        assertThat(palletOf(c, c.skuId())).isEqualTo(7);
        List<StockMovement> releases = movements(c, c.skuId(), StockMovement.TYPE_PALLET_RELEASE);
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).getQty()).isZero(); // qty=0 恒定不进件数公式
        assertThat(releases.get(0).getPalletDelta()).isEqualTo(-3);
        assertThat(releases.get(0).getRefDocNo()).isEqualTo(out.getDocNo());
        assertPalletInvariant(c, c.skuId());

        // 全出清零：剩余 70 全部代建出库 → 默认释放全部 7 托
        WkOutboundCreateDto all = new WkOutboundCreateDto();
        all.setWholesalerId(c.wholesalerId());
        all.setSkuId(c.skuId());
        all.setQty(70);
        all.setConfirmed(true);
        all.setRestatedQty(70); // 大额复述（>50%）
        outboundRequestService.createByWk(all, c.wkUserId());
        assertThat(qtyOf(c, c.skuId())).isZero();
        assertThat(palletOf(c, c.skuId())).isZero();
        assertPalletInvariant(c, c.skuId());
    }
}
