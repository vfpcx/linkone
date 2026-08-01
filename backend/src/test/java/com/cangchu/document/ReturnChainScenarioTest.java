package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.OutboundRegisterDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.ReturnCreateDto;
import com.cangchu.document.dto.ReturnRegisterDto;
import com.cangchu.document.dto.ReturnWithdrawDto;
import com.cangchu.document.entity.ReturnRequest;
import com.cangchu.document.mapper.ReturnRequestMapper;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.service.ReturnRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.document.vo.ReturnRequestVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.mapper.NotificationMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.StoreSettingsDto;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * P3b T3-W1 退货链 + 托盘账场景测试（13 §6 T3-W1 测试关卡）。
 *
 * <p>覆盖（沿 InboundForwardChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>RTN 矩阵逐格（4×4 全交叉；红线：受理后不可撤 / 待受理不可直登）。</li>
 *   <li>D-7 登记时扣三态：足额 / 不足拒绝（50251，单据保持 ACCEPTED 整体回滚）/ 边界 0（在库==退货数，
 *       全出清零默认释放全部托盘）。发起/受理/撤回全程零库存零新增流水。</li>
 *   <li>D-9：WE 发起/撤回一律 42004（即使持全部现有授权位）；WE 只读列表可见。</li>
 *   <li>托盘 D-8=A：比例默认建议值 / WK 覆盖（含 0）/ 双重封顶不打负（含池 pallet=0）；
 *       出库登记 PALLET_RELEASE（qty=0）；Σpallet_delta ≡ inventories.pallet_qty 对账；
 *       存量流水 pallet_delta 默认 0 不回填；CORRECTION/DISPUTE 流水列+remark 快照双写。</li>
 *   <li>D-13：店铺设置改 batchEnabled=1 → 50360；V20 含存量校准 UPDATE + flyway 已应用。</li>
 *   <li>R13 未结扩展（退货 PENDING_ACCEPT/ACCEPTED）；虚拟线程并发：退货登记 × 出库扣减同锁串行。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class ReturnChainScenarioTest {

    @Autowired
    private ReturnRequestService returnRequestService;
    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private ReturnRequestMapper returnRequestMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private void asWa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.waUserId(), "WA"));
    }

    private void asWk(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
    }

    private void asTa(Ctx c) {
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.taUserId(), "TA"));
    }

    /** 直加库存（INBOUND 流水随 V20 落 pallet_delta=+pallet）。 */
    private void seedStock(Ctx c, int qty, int pallet) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).palletQty(pallet).refDocNo("WK-SEED-" + snowflakeIdUtil.nextId())
                .operatorUserId(c.wkUserId()).build());
    }

    private void deduct(Ctx c, int qty) {
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).refDocNo("CK-TEST").operatorUserId(c.wkUserId()).build());
    }

    private InventoryVo inv(Ctx c) {
        List<InventoryVo> list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? null : list.get(0);
    }

    private int qtyOf(Ctx c) {
        InventoryVo v = inv(c);
        return v == null ? 0 : v.getQty();
    }

    private int palletOf(Ctx c) {
        InventoryVo v = inv(c);
        return v == null ? 0 : v.getPalletQty();
    }

    private List<StockMovement> movements(Ctx c, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(type != null, StockMovement::getType, type));
    }

    private long countNotifications(long recipient, String type) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, recipient)
                .eq(Notification::getType, type));
    }

    /** 托盘账不变量（13 §0/05 §3.4）：inventories.pallet_qty ≡ Σ stock_movements.pallet_delta。 */
    private void assertPalletInvariant(Ctx c) {
        long sum = movements(c, null).stream()
                .mapToLong(m -> m.getPalletDelta() == null ? 0 : m.getPalletDelta()).sum();
        assertThat((long) palletOf(c)).as("托盘公式不变量 pallet_qty ≡ Σpallet_delta").isEqualTo(sum);
        assertThat(sum).isGreaterThanOrEqualTo(0);
    }

    private ReturnRequestVo createReturn(Ctx c, int qty, String remark) {
        asWa(c);
        ReturnCreateDto d = new ReturnCreateDto();
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setRemark(remark);
        return returnRequestService.createByWa(d, c.waUserId());
    }

    private ReturnRequestVo createAndAccept(Ctx c, int qty) {
        ReturnRequestVo vo = createReturn(c, qty, null);
        asWk(c);
        return returnRequestService.acceptByWk(vo.getId(), c.wkUserId());
    }

    private ReturnRegisterDto registerDto(Integer actualQty, Integer palletRelease, String remark) {
        ReturnRegisterDto d = new ReturnRegisterDto();
        d.setActualQty(actualQty);
        d.setPalletRelease(palletRelease);
        d.setRemark(remark);
        return d;
    }

    private ReturnWithdrawDto withdrawDto(String reason) {
        ReturnWithdrawDto d = new ReturnWithdrawDto();
        d.setReason(reason);
        return d;
    }

    private BizException expectBiz(org.junit.jupiter.api.function.Executable e) {
        return Assertions.assertThrows(BizException.class, e);
    }

    // ======================================================================
    // RTN 状态机矩阵逐格
    // ======================================================================

    @Test
    @DisplayName("T3-SM-01 RTN 矩阵逐格：4 状态×4 目标全交叉，可达集精确相等（红线：受理后不可撤/待受理不可直登）")
    void returnMatrixExhaustive() {
        Map<String, Set<String>> expected = Map.of(
                ReturnRequest.STATUS_PENDING_ACCEPT, Set.of(
                        ReturnRequest.STATUS_WITHDRAWN, ReturnRequest.STATUS_ACCEPTED),
                ReturnRequest.STATUS_ACCEPTED, Set.of(ReturnRequest.STATUS_COMPLETED),
                ReturnRequest.STATUS_WITHDRAWN, Set.of(),
                ReturnRequest.STATUS_COMPLETED, Set.of());
        for (String from : expected.keySet()) {
            for (String to : expected.keySet()) {
                boolean can = DocStateMachine.canGo(DocStateMachine.DocKind.RETURN, from, to);
                assertThat(can).as("%s -> %s", from, to).isEqualTo(expected.get(from).contains(to));
            }
        }
        // 红线显式重申（13 §2.1）
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.RETURN,
                ReturnRequest.STATUS_ACCEPTED, ReturnRequest.STATUS_WITHDRAWN)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.RETURN,
                ReturnRequest.STATUS_PENDING_ACCEPT, ReturnRequest.STATUS_COMPLETED)).isFalse();
        assertThat(DocStateMachine.canGo(DocStateMachine.DocKind.RETURN,
                ReturnRequest.STATUS_COMPLETED, ReturnRequest.STATUS_PENDING_ACCEPT)).isFalse();
    }

    // ======================================================================
    // 发起 / 撤回 / 受理（全程零库存——D-7）
    // ======================================================================

    @Test
    @DisplayName("T3-CRT-01 发起：RTN- 前缀+通知库管；提交/受理零库存零新增流水；超量提交软校验 50251 拒绝")
    void createSoftCheckAndZeroStock() {
        Ctx c = seedAll();
        seedStock(c, 50, 5);
        List<StockMovement> baseline = movements(c, null);

        ReturnRequestVo vo = createReturn(c, 10, "客户退单");
        assertThat(vo.getDocNo()).startsWith("RTN-");
        assertThat(vo.getStatus()).isEqualTo(ReturnRequest.STATUS_PENDING_ACCEPT);
        assertThat(vo.getQty()).isEqualTo(10);
        assertThat(vo.getWaUserId()).isEqualTo(c.waUserId());
        assertThat(vo.getRemark()).isEqualTo("客户退单");
        // 通知①：发起 → 库管
        assertThat(countNotifications(c.wkUserId(), Notification.TYPE_RETURN_CREATED)).isEqualTo(1);

        // 受理仍零库存（D-7：退货前货仍可售）
        asWk(c);
        ReturnRequestVo accepted = returnRequestService.acceptByWk(vo.getId(), c.wkUserId());
        assertThat(accepted.getStatus()).isEqualTo(ReturnRequest.STATUS_ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
        // 登记页提示数据（受理链路填充）
        assertThat(accepted.getCurrentStock()).isEqualTo(50);
        assertThat(accepted.getSuggestedPalletRelease()).isEqualTo(1); // ceil(5×10/50)=1

        assertThat(qtyOf(c)).isEqualTo(50);
        assertThat(palletOf(c)).isEqualTo(5);
        assertThat(movements(c, null)).hasSize(baseline.size()); // 零新增流水

        // 软校验：退货件数 > 当前在库 → 50251（不锁定库存，仅拒超量提交）
        assertThat(expectBiz(() -> createReturn(c, 51, null))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        // 无库存行 SKU → 50250
        Ctx empty = seedAll();
        assertThat(expectBiz(() -> createReturn(empty, 1, null))
                .getErrorCode()).isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
    }

    @Test
    @DisplayName("T3-PERM-01 D-9：WE 发起/撤回一律 42004（含持全部现有授权位）；陌生人 42001；WE 只读列表可见")
    void wePermissionDenied() {
        Ctx c = seedAll();
        seedStock(c, 30, 0);
        long weFull = seedRole(null, "WE", c.tenantId(), c.wholesalerId(),
                "[\"PRICE_EDIT\",\"INQUIRY_CONFIRM\",\"INBOUND_CONFIRM\",\"INBOUND_SUBMIT\"]");

        // WE 发起 → 42004（退货授权位未开放，与 WE 授权三态不同——任何位都不放行）
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weFull, "WE"));
        ReturnCreateDto d = new ReturnCreateDto();
        d.setSkuId(c.skuId());
        d.setQty(5);
        assertThat(expectBiz(() -> returnRequestService.createByWa(d, weFull))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_004);

        // WA 建单后 WE 撤回 → 42004
        ReturnRequestVo vo = createReturn(c, 5, null);
        TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), weFull, "WE"));
        assertThat(expectBiz(() -> returnRequestService.withdrawByWa(vo.getId(), withdrawDto("代撤"), weFull))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_004);
        // WE 只读列表可见（13 §5.2）
        assertThat(returnRequestService.listForWa(weFull, null, 1, 20).getRecords())
                .extracting(ReturnRequestVo::getId).contains(vo.getId());

        // 同租户无绑定用户（WK 冒充商户）→ 42001
        asWk(c);
        assertThat(expectBiz(() -> returnRequestService.createByWa(d, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
        // WA 冒充受理 → 42001（仅本仓库管员）
        asWa(c);
        assertThat(expectBiz(() -> returnRequestService.acceptByWk(vo.getId(), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);
    }

    @Test
    @DisplayName("T3-WD-01 撤回：仅待受理（受理后 50330）；缺理由 40003；撤回终态不可受理；全程零库存零流水")
    void withdrawOnlyPending() {
        Ctx c = seedAll();
        seedStock(c, 20, 2);
        List<StockMovement> baseline = movements(c, null);

        ReturnRequestVo vo = createReturn(c, 8, null);
        // 缺理由 → 40003
        assertThat(expectBiz(() -> returnRequestService.withdrawByWa(vo.getId(), withdrawDto("  "), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.VALIDATION_BASIC_003);
        ReturnRequestVo withdrawn = returnRequestService.withdrawByWa(vo.getId(), withdrawDto("填错件数"), c.waUserId());
        assertThat(withdrawn.getStatus()).isEqualTo(ReturnRequest.STATUS_WITHDRAWN);
        assertThat(withdrawn.getWithdrawReason()).isEqualTo("填错件数");

        // 撤回终态：受理 → 50330
        asWk(c);
        assertThat(expectBiz(() -> returnRequestService.acceptByWk(vo.getId(), c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        // 受理锁单后撤回 → 50330（矩阵红线；D-7 架构红利：登记前撤回无需回补）
        ReturnRequestVo accepted = createAndAccept(c, 6);
        asWa(c);
        assertThat(expectBiz(() -> returnRequestService.withdrawByWa(accepted.getId(), withdrawDto("晚了"), c.waUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);

        assertThat(qtyOf(c)).isEqualTo(20);
        assertThat(movements(c, null)).hasSize(baseline.size());
    }

    // ======================================================================
    // D-7 登记时扣三态
    // ======================================================================

    @Test
    @DisplayName("T3-REG-01 登记时扣三态①足额：扣件数+释放托盘+RETURN 流水（负托盘），通知商户含实退与托盘")
    void registerSufficient() {
        Ctx c = seedAll();
        seedStock(c, 50, 5);
        ReturnRequestVo vo = createAndAccept(c, 10);

        asWk(c);
        ReturnRequestVo done = returnRequestService.registerByWk(vo.getId(), null, c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(ReturnRequest.STATUS_COMPLETED);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(done.getQty()).isEqualTo(10);
        assertThat(done.getPalletRelease()).isEqualTo(1); // 默认建议值 ceil(5×10/50)=1

        assertThat(qtyOf(c)).isEqualTo(40);
        assertThat(palletOf(c)).isEqualTo(4);
        List<StockMovement> rets = movements(c, StockMovement.TYPE_RETURN);
        assertThat(rets).hasSize(1);
        assertThat(rets.get(0).getQty()).isEqualTo(10);
        assertThat(rets.get(0).getPalletDelta()).isEqualTo(-1);
        assertThat(rets.get(0).getRefDocNo()).isEqualTo(done.getDocNo());
        assertThat(rets.get(0).getBizTime()).isNotNull(); // 计费当日截止锚点
        // 通知②：登记完成 → 商户（含实退件数、释放托盘）
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_RETURN_COMPLETED)).isEqualTo(1);
        Notification n = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, c.waUserId())
                .eq(Notification::getType, Notification.TYPE_RETURN_COMPLETED)).get(0);
        assertThat(n.getContent()).contains("10 件").contains("1 个");
        assertPalletInvariant(c);

        // 终态不可重复登记 → 50330
        assertThat(expectBiz(() -> returnRequestService.registerByWk(vo.getId(), null, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("T3-REG-02 登记时扣三态②不足：受理后被出库剩 5 退 10 → 50251，单据保持 ACCEPTED、零流水零扣减")
    void registerInsufficientRejected() {
        Ctx c = seedAll();
        seedStock(c, 12, 0);
        ReturnRequestVo vo = createAndAccept(c, 10);
        // 受理期间货仍可售（D-7）：被出库 7，剩 5
        deduct(c, 7);

        asWk(c);
        assertThat(expectBiz(() -> returnRequestService.registerByWk(vo.getId(), null, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        // 整体回滚：单据保持 ACCEPTED（联系商户改单——撤回不了，走 WA 改单重提口径）
        assertThat(returnRequestMapper.selectById(vo.getId()).getStatus())
                .isEqualTo(ReturnRequest.STATUS_ACCEPTED);
        assertThat(qtyOf(c)).isEqualTo(5);
        assertThat(movements(c, StockMovement.TYPE_RETURN)).isEmpty();
        assertThat(countNotifications(c.waUserId(), Notification.TYPE_RETURN_COMPLETED)).isZero();

        // 按实覆写为剩余在库 5 → 成功（remark 自动留痕差异）
        ReturnRequestVo done = returnRequestService.registerByWk(vo.getId(), registerDto(5, null, "现场核数"), c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(ReturnRequest.STATUS_COMPLETED);
        assertThat(done.getQty()).isEqualTo(5);
        assertThat(done.getRemark()).contains("申请 10 件").contains("实退 5 件").contains("现场核数");
        assertThat(qtyOf(c)).isZero();
        assertPalletInvariant(c);
    }

    @Test
    @DisplayName("T3-REG-03 登记时扣三态③边界 0：在库==退货数 → 放行、剩 0；全出清零默认释放全部托盘")
    void registerBoundaryZero() {
        Ctx c = seedAll();
        seedStock(c, 10, 3);
        ReturnRequestVo vo = createAndAccept(c, 10);

        asWk(c);
        ReturnRequestVo done = returnRequestService.registerByWk(vo.getId(), null, c.wkUserId());
        assertThat(done.getQty()).isEqualTo(10);
        assertThat(done.getPalletRelease()).isEqualTo(3); // 全出清零（05 §3.3）：默认释放全部
        assertThat(qtyOf(c)).isZero();
        assertThat(palletOf(c)).isZero();
        List<StockMovement> rets = movements(c, StockMovement.TYPE_RETURN);
        assertThat(rets).hasSize(1);
        assertThat(rets.get(0).getPalletDelta()).isEqualTo(-3);
        assertPalletInvariant(c);

        // 待受理直登红线（50330）：另建一张不受理直接登记
        seedStock(c, 5, 0);
        ReturnRequestVo pending = createReturn(c, 3, null);
        asWk(c);
        assertThat(expectBiz(() -> returnRequestService.registerByWk(pending.getId(), null, c.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID);
    }

    // ======================================================================
    // 托盘 D-8=A：比例 / 覆盖 / 不打负
    // ======================================================================

    @Test
    @DisplayName("T3-PLT-01 托盘三例：比例默认 ceil；WK 覆盖（含 0 与超池封顶）；池 pallet=0 不打负")
    void palletRatioOverrideCap() {
        // ① 比例：100 件 10 托，退 30 → ceil(10×30/100)=3
        Ctx a = seedAll();
        seedStock(a, 100, 10);
        ReturnRequestVo va = createAndAccept(a, 30);
        asWk(a);
        ReturnRequestVo da = returnRequestService.registerByWk(va.getId(), null, a.wkUserId());
        assertThat(da.getPalletRelease()).isEqualTo(3);
        assertThat(palletOf(a)).isEqualTo(7);
        assertPalletInvariant(a);

        // ② 覆盖：WK 覆盖 0（托盘未腾空）→ 释放 0；覆盖 99 超池 → 封顶到在库托盘
        Ctx b = seedAll();
        seedStock(b, 100, 10);
        ReturnRequestVo vb1 = createAndAccept(b, 20);
        asWk(b);
        ReturnRequestVo db1 = returnRequestService.registerByWk(vb1.getId(), registerDto(null, 0, null), b.wkUserId());
        assertThat(db1.getPalletRelease()).isZero();
        assertThat(palletOf(b)).isEqualTo(10);
        ReturnRequestVo vb2 = createAndAccept(b, 20);
        asWk(b);
        ReturnRequestVo db2 = returnRequestService.registerByWk(vb2.getId(), registerDto(null, 99, null), b.wkUserId());
        assertThat(db2.getPalletRelease()).isEqualTo(10); // min(99, 在库 10) 封顶
        assertThat(palletOf(b)).isZero(); // 恒 ≥0 不打负
        assertPalletInvariant(b);
        // 覆盖负数 → 50252
        seedStock(b, 5, 0);
        ReturnRequestVo vb3 = createAndAccept(b, 2);
        asWk(b);
        assertThat(expectBiz(() -> returnRequestService.registerByWk(vb3.getId(), registerDto(null, -1, null), b.wkUserId()))
                .getErrorCode()).isEqualTo(ErrorCode.STOCK_QTY_INVALID);

        // ③ 池 pallet=0：默认建议值 0，托盘账不动不打负
        Ctx d = seedAll();
        seedStock(d, 40, 0);
        ReturnRequestVo vd = createAndAccept(d, 10);
        asWk(d);
        ReturnRequestVo dd = returnRequestService.registerByWk(vd.getId(), null, d.wkUserId());
        assertThat(dd.getPalletRelease()).isZero();
        assertThat(palletOf(d)).isZero();
        List<StockMovement> rets = movements(d, StockMovement.TYPE_RETURN);
        assertThat(rets.get(0).getPalletDelta()).isZero();
        assertPalletInvariant(d);
    }

    @Test
    @DisplayName("T3-PLT-02 出库登记 PALLET_RELEASE：qty=0 独立流水；默认值分母=变动前在库；覆盖生效；Σpallet_delta 对账")
    void outboundRegisterPalletRelease() {
        Ctx c = seedAll();
        seedStock(c, 100, 10);
        // WA 手动出库 30（创建即扣件数；OUTBOUND 流水 pallet_delta=0——托盘登记时才释放）
        asWa(c);
        OutboundSubmitDto od = new OutboundSubmitDto();
        od.setWholesalerId(c.wholesalerId());
        od.setSkuId(c.skuId());
        od.setQty(30);
        OutboundRequestVo out = outboundRequestService.submitByWa(od, c.waUserId());
        assertThat(qtyOf(c)).isEqualTo(70);
        assertThat(palletOf(c)).isEqualTo(10); // 创建不释放托盘
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND).get(0).getPalletDelta()).isZero();

        // 打印 → 登记（默认建议值：分母=变动前在库 70+30=100 → ceil(10×30/100)=3）
        asWk(c);
        outboundRequestService.printByWk(out.getId(), c.wkUserId());
        outboundRequestService.registerByWk(out.getId(), null, c.wkUserId());
        List<StockMovement> releases = movements(c, StockMovement.TYPE_PALLET_RELEASE);
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).getQty()).isZero(); // qty=0 恒定不进件数公式
        assertThat(releases.get(0).getPalletDelta()).isEqualTo(-3);
        assertThat(releases.get(0).getRefDocNo()).isEqualTo(out.getDocNo());
        assertThat(palletOf(c)).isEqualTo(7);
        assertThat(qtyOf(c)).isEqualTo(70); // 件数不重复扣
        assertPalletInvariant(c);

        // 第二单：WK 覆盖 2（走 dto 入参）
        asWa(c);
        OutboundSubmitDto od2 = new OutboundSubmitDto();
        od2.setWholesalerId(c.wholesalerId());
        od2.setSkuId(c.skuId());
        od2.setQty(20);
        OutboundRequestVo out2 = outboundRequestService.submitByWa(od2, c.waUserId());
        asWk(c);
        outboundRequestService.printByWk(out2.getId(), c.wkUserId());
        OutboundRegisterDto rd = new OutboundRegisterDto();
        rd.setPalletRelease(2);
        outboundRequestService.registerByWk(out2.getId(), rd, c.wkUserId());
        assertThat(palletOf(c)).isEqualTo(5);
        assertThat(movements(c, StockMovement.TYPE_PALLET_RELEASE)).hasSize(2);
        assertPalletInvariant(c);
    }

    @Test
    @DisplayName("T3-PLT-03 出库全出清零：登记后在库 0 → 默认释放全部托盘；释放 0 不写流水")
    void outboundFullClearAndZeroRelease() {
        Ctx c = seedAll();
        seedStock(c, 30, 4);
        asWa(c);
        OutboundSubmitDto od = new OutboundSubmitDto();
        od.setWholesalerId(c.wholesalerId());
        od.setSkuId(c.skuId());
        od.setQty(30);
        OutboundRequestVo out = outboundRequestService.submitByWa(od, c.waUserId());
        asWk(c);
        outboundRequestService.printByWk(out.getId(), c.wkUserId());
        outboundRequestService.registerByWk(out.getId(), null, c.wkUserId());
        // 全出清零：变动后在库 0 → 默认释放全部 4 托
        assertThat(palletOf(c)).isZero();
        assertThat(movements(c, StockMovement.TYPE_PALLET_RELEASE).get(0).getPalletDelta()).isEqualTo(-4);
        assertPalletInvariant(c);

        // 覆盖 0 → 不写流水（qty=0 且 pallet_delta=0 的空流水无对账意义）
        Ctx d = seedAll();
        seedStock(d, 10, 2);
        asWa(d);
        OutboundSubmitDto od2 = new OutboundSubmitDto();
        od2.setWholesalerId(d.wholesalerId());
        od2.setSkuId(d.skuId());
        od2.setQty(5);
        OutboundRequestVo out2 = outboundRequestService.submitByWa(od2, d.waUserId());
        asWk(d);
        outboundRequestService.printByWk(out2.getId(), d.wkUserId());
        OutboundRegisterDto rd = new OutboundRegisterDto();
        rd.setPalletRelease(0);
        outboundRequestService.registerByWk(out2.getId(), rd, d.wkUserId());
        assertThat(movements(d, StockMovement.TYPE_PALLET_RELEASE)).isEmpty();
        assertThat(palletOf(d)).isEqualTo(2);
        assertPalletInvariant(d);
    }

    @Test
    @DisplayName("T3-PLT-04 存量口径与双写：mapper 直插存量流水 pallet_delta 默认 0 不回填；DISPUTE/CORRECTION 列+remark 双写由既有链路回归覆盖")
    void legacyMovementDefaultZero() {
        Ctx c = seedAll();
        // 模拟存量（V20 前）流水写入路径：不带 pallet_delta → 库默认 0（13 §2.4-4：不回填、不回溯）
        StockMovement legacy = new StockMovement();
        legacy.setId(snowflakeIdUtil.nextId());
        legacy.setSkuId(c.skuId());
        legacy.setWholesalerId(c.wholesalerId());
        legacy.setTenantId(c.tenantId());
        legacy.setType(StockMovement.TYPE_OUTBOUND);
        legacy.setQty(1);
        legacy.setRefDocNo("CK-LEGACY");
        legacy.setBizTime(java.time.LocalDateTime.now());
        legacy.setPalletDelta(null); // 实体不落值 → 列默认 0
        stockMovementMapper.insert(legacy);
        StockMovement read = stockMovementMapper.selectById(legacy.getId());
        assertThat(read.getPalletDelta()).isZero();
    }

    // ======================================================================
    // D-13 batch_enabled 禁改 + 存量校准
    // ======================================================================

    @Test
    @DisplayName("T3-BATCH-01 D-13：店铺设置改 batchEnabled=1 → 50360；传 0/其余开关不受影响；V20 含存量校准且已应用")
    void batchEnabledFrozen() throws Exception {
        Ctx c = seedAll();
        // 店铺行（updateMyStore 依赖）
        Store store = new Store();
        store.setId(snowflakeIdUtil.nextId());
        store.setTenantId(c.tenantId());
        store.setName("店-" + c.tenantId());
        storeMapper.insert(store);

        asTa(c);
        // 开启批次 → 50360（T4-W1 专用端点解除）
        StoreSettingsDto enable = new StoreSettingsDto();
        enable.setBatchEnabled(1);
        assertThat(expectBiz(() -> tenantService.updateMyStore(c.taUserId(), enable))
                .getErrorCode()).isEqualTo(ErrorCode.BATCH_FEATURE_NOT_READY);

        // 传 0（无害回显/校准）+ 其余开关正常生效
        StoreSettingsDto ok = new StoreSettingsDto();
        ok.setBatchEnabled(0);
        ok.setPhotoMode("REQUIRED");
        tenantService.updateMyStore(c.taUserId(), ok);
        var detail = tenantService.getMyStore(c.taUserId());
        assertThat(detail.getBatchEnabled()).isZero();
        assertThat(detail.getPhotoMode()).isEqualTo("REQUIRED");

        // 存量校准（V20 迁移语句回归护栏）：脚本含 UPDATE 归 0 且 flyway 已应用
        String sql = new String(getClass().getResourceAsStream("/db/migration/V20__p3b_return_pallet.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("UPDATE `tenant_settings` SET `batch_enabled` = 0");
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '20' AND \"success\" = TRUE",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    // ======================================================================
    // R13 未结计数扩展
    // ======================================================================

    @Test
    @DisplayName("T3-R13-01 未结扩展：PENDING_ACCEPT/ACCEPTED 计入，WITHDRAWN/COMPLETED 不计（阻退驻口径）")
    void openCountExtension() {
        Ctx c = seedAll();
        seedStock(c, 100, 0);
        ReturnRequestVo r1 = createReturn(c, 10, null);
        ReturnRequestVo r2 = createReturn(c, 20, null);
        assertThat(returnRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(2);

        // 撤 1 → 1
        returnRequestService.withdrawByWa(r1.getId(), withdrawDto("不退了"), c.waUserId());
        assertThat(returnRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 受理仍未结 → 1
        asWk(c);
        returnRequestService.acceptByWk(r2.getId(), c.wkUserId());
        assertThat(returnRequestService.countOpenForWholesaler(c.wholesalerId())).isEqualTo(1);
        // 登记已结 → 0
        returnRequestService.registerByWk(r2.getId(), null, c.wkUserId());
        assertThat(returnRequestService.countOpenForWholesaler(c.wholesalerId())).isZero();
    }

    // ======================================================================
    // 队列与列表
    // ======================================================================

    @Test
    @DisplayName("T3-LIST-01 队列：待受理升序先到先受理（附在库提示）；WK/TA 可见、WA 拒绝；商户侧 status 过滤")
    void listQueues() {
        Ctx c = seedAll();
        seedStock(c, 100, 0);
        ReturnRequestVo first = createReturn(c, 5, null);
        ReturnRequestVo second = createReturn(c, 6, null);

        asWk(c);
        List<ReturnRequestVo> queue = returnRequestService.listByTenant(
                c.tenantId(), c.wkUserId(), c.wholesalerId(), ReturnRequest.STATUS_PENDING_ACCEPT);
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getId()).isEqualTo(first.getId()); // 创建升序
        assertThat(queue.get(0).getCurrentStock()).isEqualTo(100); // 队列附在库提示
        asTa(c);
        assertThat(returnRequestService.listByTenant(c.tenantId(), c.taUserId(), null, null)).hasSize(2);
        asWa(c);
        assertThat(expectBiz(() -> returnRequestService.listByTenant(c.tenantId(), c.waUserId(), null, null))
                .getErrorCode()).isEqualTo(ErrorCode.PERMISSION_ROLE_001);

        // 商户侧列表 + status 过滤
        var mine = returnRequestService.listForWa(c.waUserId(), ReturnRequest.STATUS_PENDING_ACCEPT, 1, 20);
        assertThat(mine.getRecords()).hasSize(2);
        returnRequestService.withdrawByWa(second.getId(), withdrawDto("重报"), c.waUserId());
        assertThat(returnRequestService.listForWa(c.waUserId(), ReturnRequest.STATUS_WITHDRAWN, 1, 20)
                .getRecords()).hasSize(1);
    }

    // ======================================================================
    // 并发（虚拟线程）：退货登记 × 出库扣减同锁串行
    // ======================================================================

    @Test
    @DisplayName("T3-CONC-01 并发（虚拟线程）：在库 10，退货登记 8 × 出库扣减 8 同锁串行——恰一方成功，败方 50251，不超卖")
    void concurrentReturnVsDeduct() throws Exception {
        Ctx c = seedAll();
        seedStock(c, 10, 0);
        ReturnRequestVo vo = createAndAccept(c, 8);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> returnF = vt.submit(() -> {
                TenantContext.set(TenantContext.TenantInfo.of(c.tenantId(), c.wkUserId(), "WK"));
                start.await();
                try {
                    return returnRequestService.registerByWk(vo.getId(), null, c.wkUserId());
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
            Object r1 = returnF.get(60, TimeUnit.SECONDS);
            Object r2 = deductF.get(60, TimeUnit.SECONDS);

            long failures = List.of(r1, r2).stream().filter(r -> r instanceof BizException).count();
            assertThat(failures).as("在库 10 不够 8+8：同锁串行后恰一方 50251").isEqualTo(1);
            BizException loser = (BizException) (r1 instanceof BizException ? r1 : r2);
            assertThat(loser.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        }
        // 终局一致：10−8=2，恒 ≥0 不超卖；流水与库存对账
        assertThat(qtyOf(c)).isEqualTo(2);
        long netOut = movements(c, StockMovement.TYPE_RETURN).stream().mapToLong(StockMovement::getQty).sum()
                + movements(c, StockMovement.TYPE_OUTBOUND).stream().mapToLong(StockMovement::getQty).sum();
        assertThat(netOut).isEqualTo(8);
        // 退货赢时单据 COMPLETED、出库赢时保持 ACCEPTED——两态皆合法
        String docStatus = returnRequestMapper.selectById(vo.getId()).getStatus();
        assertThat(docStatus).isIn(ReturnRequest.STATUS_COMPLETED, ReturnRequest.STATUS_ACCEPTED);
        assertPalletInvariant(c);
    }
}
