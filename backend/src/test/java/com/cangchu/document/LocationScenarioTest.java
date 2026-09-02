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
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.dto.OutboundRegisterDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.inventory.dto.BatchLocationUpdateDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.entity.BatchLocationLog;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.BatchLocationLogMapper;
import com.cangchu.inventory.mapper.BatchMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchLocationLogVo;
import com.cangchu.inventory.vo.BatchVo;
import com.cangchu.inventory.vo.BatchToggleVo;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.StoreSettingsDto;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.TenantBatchConfigVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-D C2 货位功能（US-WK-05）场景测试（25-p5-c-c2 §7，LV-01~06）。
 *
 * <p>覆盖（沿用 BatchChainScenarioTest 风格：mapper seed + TenantContext 模拟登录态）：
 * <ul>
 *   <li>LV-01 默认关闭：出入库登记不带货位成功、单据 location 为 NULL、零货位校验（回归兼容）。</li>
 *   <li>LV-02 开启后入库：代建/正向登记缺货位 50822；带货位成功落单据 + 批次行（batchEnabled=1）。</li>
 *   <li>LV-03 开启后出库：登记出库/代建出库缺拣货位 50822；带货位落 outbound_requests.location；
 *       库存/流水零副作用断言（仅原 OUTBOUND 一条，方案 C 铁律）。</li>
 *   <li>LV-04 移库：改货位 + 变更日志 from/to 两态；相同幂等不落日志；清空(null)成功；批次不存在/跨租户
 *       50363；超长 50823。</li>
 *   <li>LV-05 关闭开关：存量货位保留、登记恢复免填。</li>
 *   <li>LV-06 各端读开关：GET batch-config 语义（tenantService.getBatchConfig）含 locationEnabled。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class LocationScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private OutboundRequestService outboundRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private BatchMapper batchMapper;
    @Autowired
    private BatchLocationLogMapper batchLocationLogMapper;
    @Autowired
    private InboundRequestMapper inboundRequestMapper;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private com.cangchu.tenant.mapper.StoreMapper storeMapper;
    @Autowired
    private PiiCrypto piiCrypto;
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

    // ==================== seed 工具（BatchChainScenarioTest 同构） ====================

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
        Store store = new Store();
        store.setId(snowflakeIdUtil.nextId());
        store.setTenantId(tenantId);
        store.setName("店-" + tenantId);
        store.setStatus("ACTIVE");
        storeMapper.insert(store);
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

    private int errCode(Runnable r) {
        try {
            r.run();
            return -1;
        } catch (BizException e) {
            return e.getCode();
        }
    }

    // ==================== 货位开关（经通用店铺设置 PUT /tenant/me 语义） ====================

    private void setLocationEnabled(Ctx c, int on) {
        asTa(c);
        StoreSettingsDto dto = new StoreSettingsDto();
        dto.setLocationEnabled(on);
        tenantService.updateMyStore(c.taUserId(), dto);
    }

    private void enableBatch(Ctx c) {
        asTa(c);
        BatchToggleDto d = new BatchToggleDto();
        d.setEnable(true);
        d.setConfirmed(true);
        batchService.toggle(c.taUserId(), d);
        // 24h 计数清零（跨断言场景的测试基建；生产 TTL 24h 自然过期）
        redissonClient.getAtomicLong("batch:toggle:" + c.tenantId()).delete();
    }

    private void seedStock(Ctx c, int qty, String refDocNo) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(c.wholesalerId()).tenantId(c.tenantId()).skuId(c.skuId())
                .qty(qty).palletQty(0).refDocNo(refDocNo).operatorUserId(c.wkUserId()).build());
    }

    private int qtyOf(Ctx c) {
        var list = inventoryService.queryInventory(c.wholesalerId(), c.skuId());
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private List<StockMovement> movements(Ctx c, String type) {
        return stockMovementMapper.selectList(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, c.wholesalerId())
                .eq(StockMovement::getSkuId, c.skuId())
                .eq(StockMovement::getType, type));
    }

    // ==================== 出入库登记构造 ====================

    /** 代建入库登记（C2：可带 location/batchNo）。 */
    private InboundRequestVo proxyInbound(Ctx c, int qty, String batchNo, String location) {
        asWk(c);
        InboundRegisterDto d = new InboundRegisterDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(0);
        d.setBatchNo(batchNo);
        if (batchNo != null) {
            d.setProductionDate(LocalDate.now().minusDays(10));
            d.setExpiryDate(LocalDate.now().plusDays(120));
        }
        d.setLocation(location);
        return inboundRequestService.registerByWk(d, c.wkUserId());
    }

    /** WA 手动出库申请 → WK 打印 → 可登记出库（PENDING_ACCEPT→PRINTED）。 */
    private OutboundRequestVo submitPrintedOutbound(Ctx c, int qty) {
        asWa(c);
        OutboundSubmitDto d = new OutboundSubmitDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(0);
        OutboundRequestVo vo = outboundRequestService.submitByWa(d, c.waUserId());
        asWk(c);
        return outboundRequestService.printByWk(vo.getId(), c.wkUserId());
    }

    private Batch batchByNo(Ctx c, String batchNo) {
        return batchMapper.selectOne(new LambdaQueryWrapper<Batch>()
                .eq(Batch::getWholesalerId, c.wholesalerId())
                .eq(Batch::getSkuId, c.skuId())
                .eq(Batch::getBatchNo, batchNo));
    }

    private String inboundLocation(Long inboundId) {
        InboundRequest r = inboundRequestMapper.selectById(inboundId);
        return r != null ? r.getLocation() : null;
    }

    private String outboundLocation(Long outboundId) {
        OutboundRequest r = outboundRequestMapper.selectById(outboundId);
        return r != null ? r.getLocation() : null;
    }

    // ==================== LV-01：默认关闭零货位（回归兼容） ====================

    @Test
    @DisplayName("LV-01 默认关闭：出入库登记不带货位成功、单据 location=NULL、无货位校验")
    void lv01_defaultOff() {
        Ctx c = seedAll();
        asWk(c);
        TenantBatchConfigVo cfg = tenantService.getBatchConfig(c.tenantId());
        assertThat(cfg.getLocationEnabled()).isZero();

        // 代建入库（批次关、货位关）：不带货位成功，单据 location 为 NULL
        InboundRequestVo in = proxyInbound(c, 10, null, null);
        assertThat(inboundLocation(in.getId())).isNull();

        // WA 出库申请 → 打印 → 登记出库：不带拣货位成功，单据 location 为 NULL
        OutboundRequestVo printed = submitPrintedOutbound(c, 3);
        OutboundRegisterDto reg = new OutboundRegisterDto();
        OutboundRequestVo done = outboundRequestService.registerByWk(printed.getId(), reg, c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(outboundLocation(done.getId())).isNull();
        assertThat(qtyOf(c)).isEqualTo(7);
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND)).hasSize(1);
    }

    // ==================== LV-02：开启后入库必填（50822）+ 落单据/批次 ====================

    @Test
    @DisplayName("LV-02 开启后入库：缺货位 50822；带货位落单据 + 批次行")
    void lv02_enabledInbound() {
        Ctx c = seedAll();
        enableBatch(c);                 // 开批次（批次登记簿才有行可挂货位）
        setLocationEnabled(c, 1);       // 开货位

        // 代建登记缺货位 → 50822（即使批次字段齐全）
        asWk(c);
        int missing = errCode(() -> proxyInbound(c, 8, "B-LV02-1", null));
        assertThat(missing).isEqualTo(ErrorCode.LOCATION_REQUIRED.getCode());

        // 带货位成功：单据 location 落值 + 批次行 location 落值
        InboundRequestVo in = proxyInbound(c, 8, "B-LV02-1", "A-01-03");
        assertThat(inboundLocation(in.getId())).isEqualTo("A-01-03");
        assertThat(batchByNo(c, "B-LV02-1").getLocation()).isEqualTo("A-01-03");

        // 批次登记簿列表带货位（拣货联想数据源）
        asWk(c);
        BatchListVo list = batchService.listForTenant(c.tenantId(), c.wkUserId(),
                c.wholesalerId(), c.skuId(), null);
        assertThat(list.getList()).anyMatch(v -> "B-LV02-1".equals(v.getBatchNo())
                && "A-01-03".equals(v.getLocation()));
    }

    @Test
    @DisplayName("LV-02b 正向链登记：缺货位 50822；带货位成功落单据 + 批次行")
    void lv02b_forwardInbound() {
        Ctx c = seedAll();
        enableBatch(c);
        setLocationEnabled(c, 1);

        // 正向链：提交（带批次）→ 受理 → 登记（C2：登记时才填货位）
        asWa(c);
        InboundSubmitDto.Item item = new InboundSubmitDto.Item();
        item.setSkuId(c.skuId());
        item.setQty(6);
        item.setBatchNo("B-LV02b-1");
        item.setProductionDate(LocalDate.now().minusDays(5));
        item.setExpiryDate(LocalDate.now().plusDays(90));
        InboundSubmitDto submitDto = new InboundSubmitDto();
        submitDto.setWholesalerId(c.wholesalerId());
        submitDto.setItems(List.of(item));
        InboundRequestVo vo = inboundRequestService.submitByWa(submitDto, c.waUserId()).get(0);

        asWk(c);
        inboundRequestService.acceptByWk(vo.getId(), c.wkUserId());

        InboundForwardRegisterDto reg = new InboundForwardRegisterDto();
        reg.setActualQty(6);
        int missing = errCode(() -> inboundRequestService.registerForwardByWk(vo.getId(), reg, c.wkUserId()));
        assertThat(missing).isEqualTo(ErrorCode.LOCATION_REQUIRED.getCode());

        InboundForwardRegisterDto reg2 = new InboundForwardRegisterDto();
        reg2.setActualQty(6);
        reg2.setLocation("A-02-01");
        InboundRequestVo done = inboundRequestService.registerForwardByWk(vo.getId(), reg2, c.wkUserId());
        assertThat(inboundLocation(done.getId())).isEqualTo("A-02-01");
        assertThat(batchByNo(c, "B-LV02b-1").getLocation()).isEqualTo("A-02-01");
    }

    // ==================== LV-03：开启后出库拣货位必填（零记账副作用） ====================

    @Test
    @DisplayName("LV-03 开启后出库：登记/代建缺拣货位 50822；带货位落单且库存/流水零副作用")
    void lv03_enabledOutbound() {
        Ctx c = seedAll();
        setLocationEnabled(c, 1);   // 仅货位开、批次关（K-9：解耦场景）
        seedStock(c, 100, "SEED-LV03");

        // 登记出库（PRINTED→COMPLETED）缺拣货位 → 50822
        OutboundRequestVo printed = submitPrintedOutbound(c, 10);
        OutboundRegisterDto noLoc = new OutboundRegisterDto();
        assertThat(errCode(() -> outboundRequestService.registerByWk(
                printed.getId(), noLoc, c.wkUserId())))
                .isEqualTo(ErrorCode.LOCATION_REQUIRED.getCode());

        // 带货位成功：outbound_requests.location 落值；库存仅原扣账、无新增流水
        OutboundRegisterDto withLoc = new OutboundRegisterDto();
        withLoc.setLocation("B-03-06");
        OutboundRequestVo done = outboundRequestService.registerByWk(
                printed.getId(), withLoc, c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(outboundLocation(done.getId())).isEqualTo("B-03-06");
        assertThat(qtyOf(c)).isEqualTo(90);
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND)).hasSize(1);

        // 代建出库缺拣货位 → 50822；带位成功落单
        WkOutboundCreateDto noLoc2 = wkCreateDto(c, 5, null);
        asWk(c);
        assertThat(errCode(() -> outboundRequestService.createByWk(noLoc2, c.wkUserId())))
                .isEqualTo(ErrorCode.LOCATION_REQUIRED.getCode());

        WkOutboundCreateDto withLoc2 = wkCreateDto(c, 5, "C-01-09");
        OutboundRequestVo proxyDone = outboundRequestService.createByWk(withLoc2, c.wkUserId());
        assertThat(proxyDone.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(outboundLocation(proxyDone.getId())).isEqualTo("C-01-09");
        assertThat(qtyOf(c)).isEqualTo(85);
        assertThat(movements(c, StockMovement.TYPE_OUTBOUND)).hasSize(2);
    }

    private WkOutboundCreateDto wkCreateDto(Ctx c, int qty, String location) {
        WkOutboundCreateDto d = new WkOutboundCreateDto();
        d.setWholesalerId(c.wholesalerId());
        d.setSkuId(c.skuId());
        d.setQty(qty);
        d.setPalletQty(0);
        d.setConfirmed(true);
        d.setLocation(location);
        return d;
    }

    // ==================== LV-04：移库 + 变更日志 ====================

    @Test
    @DisplayName("LV-04 移库：改位/清空落日志、相同幂等、不存在/跨租户 50363、超长 50823")
    void lv04_moveBatch() {
        Ctx c = seedAll();
        enableBatch(c);
        setLocationEnabled(c, 1);
        InboundRequestVo in = proxyInbound(c, 10, "B-LV04-1", "D-01");
        Batch b = batchByNo(c, "B-LV04-1");

        asWk(c);
        // 改货位 → 成功 + from=D-01 → to=E-02
        BatchLocationUpdateDto move = new BatchLocationUpdateDto();
        move.setLocation("E-02");
        BatchVo moved = batchService.updateBatchLocation(c.tenantId(), b.getId(), move, c.wkUserId());
        assertThat(moved.getLocation()).isEqualTo("E-02");
        assertLogs(c, b.getId(), 1);
        BatchLocationLog first = latestLog(b.getId());
        assertThat(first.getFromLocation()).isEqualTo("D-01");
        assertThat(first.getToLocation()).isEqualTo("E-02");
        assertThat(first.getOperatorUserId()).isEqualTo(c.wkUserId());

        // 相同值幂等：不落新日志
        BatchLocationUpdateDto same = new BatchLocationUpdateDto();
        same.setLocation("E-02");
        batchService.updateBatchLocation(c.tenantId(), b.getId(), same, c.wkUserId());
        assertLogs(c, b.getId(), 1);

        // 清空（null）→ 成功，to=null
        BatchLocationUpdateDto clear = new BatchLocationUpdateDto();
        clear.setLocation(null);
        BatchVo cleared = batchService.updateBatchLocation(c.tenantId(), b.getId(), clear, c.wkUserId());
        assertThat(cleared.getLocation()).isNull();
        assertLogs(c, b.getId(), 2);
        assertThat(latestLog(b.getId()).getToLocation()).isNull();

        // 批次不存在 → 50363；超长 → 50823
        assertThat(errCode(() -> batchService.updateBatchLocation(
                c.tenantId(), snowflakeIdUtil.nextId(), move, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NOT_FOUND.getCode());
        BatchLocationUpdateDto tooLong = new BatchLocationUpdateDto();
        tooLong.setLocation("X".repeat(65));
        assertThat(errCode(() -> batchService.updateBatchLocation(
                c.tenantId(), b.getId(), tooLong, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_LOCATION_TOO_LONG.getCode());

        // 跨租户批次（他仓批次视为不存在，50363——不泄漏存在性）
        Ctx other = seedAll();
        enableBatch(other);
        setLocationEnabled(other, 1);
        InboundRequestVo oIn = proxyInbound(other, 5, "B-LV04-2", "F-01");
        Batch oBatch = batchByNo(other, "B-LV04-2");
        assertThat(errCode(() -> batchService.updateBatchLocation(
                c.tenantId(), oBatch.getId(), move, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NOT_FOUND.getCode());
        assertThat(errCode(() -> batchService.listLocationLogs(
                c.tenantId(), oBatch.getId(), 1, 20, c.wkUserId())))
                .isEqualTo(ErrorCode.BATCH_NOT_FOUND.getCode());
    }

    private void assertLogs(Ctx c, long batchId, int expected) {
        assertThat(batchLocationLogMapper.selectList(new LambdaQueryWrapper<BatchLocationLog>()
                .eq(BatchLocationLog::getBatchId, batchId))).hasSize(expected);
    }

    private BatchLocationLog latestLog(long batchId) {
        return batchLocationLogMapper.selectList(new LambdaQueryWrapper<BatchLocationLog>()
                .eq(BatchLocationLog::getBatchId, batchId)
                .orderByDesc(BatchLocationLog::getCreatedAt)
                .last("limit 1")).get(0);
    }

    @Test
    @DisplayName("LV-04b 变更记录分页端点语义：倒序 + 操作人/时间 + 越权拒绝")
    void lv04b_locationLogsEndpoint() {
        Ctx c = seedAll();
        enableBatch(c);
        setLocationEnabled(c, 1);
        proxyInbound(c, 10, "B-LV04b-1", "G-01");
        Batch b = batchByNo(c, "B-LV04b-1");

        asWk(c);
        BatchLocationUpdateDto move = new BatchLocationUpdateDto();
        move.setLocation("H-05");
        batchService.updateBatchLocation(c.tenantId(), b.getId(), move, c.wkUserId());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BatchLocationLogVo> page =
                batchService.listLocationLogs(c.tenantId(), b.getId(), 1, 20, c.wkUserId());
        assertThat(page.getTotal()).isEqualTo(1);
        BatchLocationLogVo vo = page.getRecords().get(0);
        assertThat(vo.getFromLocation()).isEqualTo("G-01");
        assertThat(vo.getToLocation()).isEqualTo("H-05");
        assertThat(vo.getOperatorUserId()).isEqualTo(c.wkUserId());
        assertThat(vo.getCreatedAt()).isNotNull();
    }

    // ==================== LV-05：关闭开关（存量保留 + 恢复免填） ====================

    @Test
    @DisplayName("LV-05 关闭开关：存量货位保留，出入库登记恢复免填")
    void lv05_disable() {
        Ctx c = seedAll();
        enableBatch(c);
        setLocationEnabled(c, 1);
        InboundRequestVo in = proxyInbound(c, 10, "B-LV05-1", "K-01");

        // 关货位（通用设置）
        setLocationEnabled(c, 0);
        assertThat(tenantService.getBatchConfig(c.tenantId()).getLocationEnabled()).isZero();

        // 存量保留：单据与批次行货位不变
        assertThat(inboundLocation(in.getId())).isEqualTo("K-01");
        assertThat(batchByNo(c, "B-LV05-1").getLocation()).isEqualTo("K-01");

        // 登记恢复免填：代建入库不带货位成功（批次已开须带批次）
        asWk(c);
        InboundRequestVo in2 = proxyInbound(c, 5, "B-LV05-2", null);
        assertThat(inboundLocation(in2.getId())).isNull();

        // 出库登记恢复免填
        OutboundRequestVo printed = submitPrintedOutbound(c, 3);
        OutboundRegisterDto reg = new OutboundRegisterDto();
        OutboundRequestVo done = outboundRequestService.registerByWk(printed.getId(), reg, c.wkUserId());
        assertThat(done.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(outboundLocation(done.getId())).isNull();
    }
}
