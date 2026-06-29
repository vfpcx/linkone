package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 询价→确认→自动转出库 场景测试（phase-1 C2 · 交易闭环核心验证）。
 *
 * <p>沿用 {@code InboundScenarioTest} 风格：mapper 直接 seed（store/wholesaler/sku/userRole）+
 * 经 {@link InventoryService} 入库种库存 + 操控 {@link TenantContext} 模拟登录态。
 * RT 提交询价为公开端点（无登录态），故 submit 前清空 TenantContext。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 RT 提交询价 → inquiry PENDING + items 含价格快照。</li>
 *   <li>S1 WA 确认 → inquiry CONFIRMED→COMPLETED + 每 item 生成 outbound + 库存扣减 + OUTBOUND 流水。</li>
 *   <li>S2 qty<=0 / 缺 sku → 拒绝。</li>
 *   <li>S4 非 WA / 跨 wholesaler → 拒绝。</li>
 *   <li>S5 库存不足 → 整个确认事务回滚（inquiry 仍 PENDING、无 outbound、库存未扣）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class InquiryScenarioTest {

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private OutboundRequestMapper outboundRequestMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed helpers ====================

    private long seedStore(long tenantId) {
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);
        return s.getId();
    }

    private long seedWholesaler(long tenantId) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return w.getId();
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

    /** 经 service 入库种库存（B1 不暴露公开加库存 HTTP）。 */
    private void seedStock(long tenantId, long wholesalerId, long skuId, int qty) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(wholesalerId)
                .tenantId(tenantId)
                .skuId(skuId)
                .qty(qty)
                .refDocNo("IN-SEED")
                .operatorUserId(1L)
                .build());
    }

    /** seed 一个 user 在 wholesaler 下的 WA 角色，返回 userId。 */
    private long seedWaUser(long tenantId, long wholesalerId) {
        long userId = snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(userId);
        r.setRole("WA");
        r.setTenantId(tenantId);
        r.setWholesalerId(wholesalerId);
        r.setStatus("ACTIVE");
        r.setPriority(5);
        userRoleMapper.insert(r);
        return userId;
    }

    private SubmitInquiryDto dto(long storeId, long wholesalerId, long skuId, Integer qty) {
        SubmitInquiryDto d = new SubmitInquiryDto();
        d.setStoreId(storeId);
        d.setWholesalerId(wholesalerId);
        d.setRtPhone("13800001111");
        SubmitInquiryDto.InquiryItemDto it = new SubmitInquiryDto.InquiryItemDto();
        it.setSkuId(skuId);
        it.setQty(qty);
        d.setItems(List.of(it));
        return d;
    }

    private long countOutbound(long wholesalerId, long skuId) {
        return outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getWholesalerId, wholesalerId)
                .eq(OutboundRequest::getSkuId, skuId));
    }

    private long countOutboundMovements(long wholesalerId, long skuId) {
        return stockMovementMapper.selectCount(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, wholesalerId)
                .eq(StockMovement::getSkuId, skuId)
                .eq(StockMovement::getType, StockMovement.TYPE_OUTBOUND));
    }

    private int currentStock(long wholesalerId, long skuId) {
        var list = inventoryService.queryInventory(wholesalerId, skuId);
        return list.isEmpty() ? 0 : list.get(0).getQty();
    }

    private long baseTenant(long bucket) {
        return bucket + (snowflakeIdUtil.nextId() & 0xFFFF);
    }

    // ======================================================================
    // S1 RT 提交 → WA 确认 → 出库扣库存（交易闭环）
    // ======================================================================

    @Test
    @DisplayName("INQ-S1-01 RT 提交询价 → PENDING + items 价格快照")
    void s1_rtSubmit() {
        long tenantId = baseTenant(710_000_100_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 100);

        TenantContext.clear(); // RT 公开端点无登录态
        InquiryVo vo = inquiryService.submitByRt(dto(store, wid, sku, 20));

        assertThat(vo.getStatus()).isEqualTo(InquiryRequest.STATUS_PENDING);
        assertThat(vo.getDocNo()).startsWith("XJ-");
        assertThat(vo.getTenantId()).isEqualTo(tenantId);
        assertThat(vo.getItems()).hasSize(1);
        InquiryVo.InquiryItemVo item = vo.getItems().get(0);
        assertThat(item.getQty()).isEqualTo(20);
        assertThat(item.getUnitPriceSnapshot()).isEqualByComparingTo("9.90");
        assertThat(item.getMoqPriceSnapshot()).isEqualByComparingTo("8.50");
        assertThat(item.getMoqQtySnapshot()).isEqualTo(10);
        assertThat(item.getDealPrice()).isEqualByComparingTo("9.90"); // phase-1 = 单价快照
    }

    @Test
    @DisplayName("INQ-S1-02 WA 确认 → CONFIRMED→COMPLETED + 出库单 + 库存扣减 + OUTBOUND 流水")
    void s1_waConfirmAutoOutbound() {
        long tenantId = baseTenant(710_000_200_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 100);
        long wa = seedWaUser(tenantId, wid);

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(dto(store, wid, sku, 30));
        long inquiryId = submitted.getId();

        // WA 确认（登录态 = 本租户）
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wa, "WA"));
        InquiryVo confirmed = inquiryService.confirmByWa(inquiryId, wa);

        assertThat(confirmed.getStatus()).isEqualTo(InquiryRequest.STATUS_COMPLETED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        // 每 item 一条出库单（COMPLETED）
        assertThat(countOutbound(wid, sku)).isEqualTo(1);
        OutboundRequest out = outboundRequestMapper.selectList(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getInquiryId, inquiryId)).get(0);
        assertThat(out.getStatus()).isEqualTo(OutboundRequest.STATUS_COMPLETED);
        assertThat(out.getQty()).isEqualTo(30);
        assertThat(out.getDocNo()).startsWith("CK-");

        // 库存扣减 100 → 70，OUTBOUND 流水 1 条
        assertThat(currentStock(wid, sku)).isEqualTo(70);
        assertThat(countOutboundMovements(wid, sku)).isEqualTo(1);
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("INQ-S2-01 qty<=0 → 拒绝（INQUIRY_QTY_INVALID）")
    void s2_qtyInvalid() {
        long tenantId = baseTenant(710_000_300_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 50);

        TenantContext.clear();
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.submitByRt(dto(store, wid, sku, 0)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INQUIRY_QTY_INVALID);
    }

    @Test
    @DisplayName("INQ-S2-02 sku 不属该 wholesaler → 拒绝（INQUIRY_SKU_NOT_BELONG）")
    void s2_skuNotBelong() {
        long tenantId = baseTenant(710_000_400_001L);
        long store = seedStore(tenantId);
        long widA = seedWholesaler(tenantId);
        long widB = seedWholesaler(tenantId);
        long skuOfB = seedSku(tenantId, widB);
        seedStock(tenantId, widB, skuOfB, 50);
        // 让 A 也有在售品，保证 A 在店内（getStorePage 仅返回有在售 SKU 的 WA）
        long skuOfA = seedSku(tenantId, widA);
        seedStock(tenantId, widA, skuOfA, 50);

        TenantContext.clear();
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.submitByRt(dto(store, widA, skuOfB, 5)));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INQUIRY_SKU_NOT_BELONG);
    }

    // ======================================================================
    // S4 越权
    // ======================================================================

    @Test
    @DisplayName("INQ-S4-01 非 WA（无该商户 WA 角色）确认 → 拒绝（INQUIRY_OPERATOR_NOT_WA）")
    void s4_notWa() {
        long tenantId = baseTenant(710_000_500_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 100);

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(dto(store, wid, sku, 10));

        long notWa = snowflakeIdUtil.nextId(); // 无任何 WA 角色
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, notWa, "TA"));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.confirmByWa(submitted.getId(), notWa));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INQUIRY_OPERATOR_NOT_WA);

        // 未扣库存、无出库
        assertThat(currentStock(wid, sku)).isEqualTo(100);
        assertThat(countOutbound(wid, sku)).isZero();
    }

    @Test
    @DisplayName("INQ-S4-02 跨 wholesaler：B 商户 WA 确认 A 商户的询价 → 拒绝")
    void s4_crossWholesaler() {
        long tenantId = baseTenant(710_000_600_001L);
        long store = seedStore(tenantId);
        long widA = seedWholesaler(tenantId);
        long skuA = seedSku(tenantId, widA);
        seedStock(tenantId, widA, skuA, 100);
        long widB = seedWholesaler(tenantId);
        long waOfB = seedWaUser(tenantId, widB); // WA 仅属 B

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(dto(store, widA, skuA, 10));

        TenantContext.set(TenantContext.TenantInfo.of(tenantId, waOfB, "WA"));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.confirmByWa(submitted.getId(), waOfB));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INQUIRY_OPERATOR_NOT_WA);
        assertThat(currentStock(widA, skuA)).isEqualTo(100);
    }

    // ======================================================================
    // S5 库存不足 → 整个确认事务回滚
    // ======================================================================

    @Test
    @DisplayName("INQ-S5-01 库存不足 → 确认整体回滚（inquiry 仍 PENDING、无 outbound、库存未扣）")
    void s5_stockNotEnoughRollback() {
        long tenantId = baseTenant(710_000_700_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 5); // 仅 5 件

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(dto(store, wid, sku, 20)); // 询价 20 > 库存 5
        long inquiryId = submitted.getId();

        long wa = seedWaUser(tenantId, wid);
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wa, "WA"));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.confirmByWa(inquiryId, wa));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        // 整体回滚：inquiry 仍 PENDING、无 outbound、库存未扣、无 OUTBOUND 流水
        List<InquiryVo> list = inquiryService.listForWa(tenantId, wa);
        InquiryVo after = list.stream().filter(v -> v.getId().equals(inquiryId)).findFirst().orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InquiryRequest.STATUS_PENDING);
        assertThat(countOutbound(wid, sku)).isZero();
        assertThat(currentStock(wid, sku)).isEqualTo(5);
        assertThat(countOutboundMovements(wid, sku)).isZero();
    }
}
