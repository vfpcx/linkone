package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.document.service.InboundRequestService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库单（WK 登记）场景测试（phase-1 C1）。
 *
 * <p>直接面向 service 层验证（沿用 {@code InventoryScenarioTest} 风格：mapper 直接 seed 数据 +
 * 操控 {@link TenantContext} 模拟登录态租户）。每个用例用独立雪花随机 (wholesaler, sku, user) 隔离数据。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 WK 登记 → inbound 单 REGISTERED + 库存增加 + 写 INBOUND 流水（doc 与库存同增）。</li>
 *   <li>S1 单事务一致性：单据数 = INBOUND 流水数，库存 = 累计入库。</li>
 *   <li>S2 qty<=0 / 缺 sku（sku 不存在或不属该 wholesaler）→ 拒绝。</li>
 *   <li>S4 非 WK（无 WK 角色）/ 跨租户（TenantContext 为他租户）→ 拒绝。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class InboundScenarioTest {

    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private StockMovementMapper stockMovementMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** seed 一个本租户商户，返回其 id。 */
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

    /** seed 一个 sku 挂在 wholesaler 下，返回其 id。 */
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

    /** seed 一个 user 在 tenant 下的 WK 角色，返回 userId。 */
    private long seedWkUser(long tenantId) {
        long userId = snowflakeIdUtil.nextId();
        UserRole r = new UserRole();
        r.setId(snowflakeIdUtil.nextId());
        r.setUserId(userId);
        r.setRole("WK");
        r.setTenantId(tenantId);
        r.setStatus("ACTIVE");
        r.setPriority(3);
        userRoleMapper.insert(r);
        return userId;
    }

    private long countMovements(long wholesalerId, long skuId) {
        return stockMovementMapper.selectCount(new LambdaQueryWrapper<StockMovement>()
                .eq(StockMovement::getWholesalerId, wholesalerId)
                .eq(StockMovement::getSkuId, skuId)
                .eq(StockMovement::getType, StockMovement.TYPE_INBOUND));
    }

    private InboundRegisterDto dto(long wholesalerId, long skuId, Integer qty, Integer palletQty) {
        InboundRegisterDto d = new InboundRegisterDto();
        d.setWholesalerId(wholesalerId);
        d.setSkuId(skuId);
        d.setQty(qty);
        d.setPalletQty(palletQty);
        return d;
    }

    // ======================================================================
    // S1 正常流程 + 单事务一致性
    // ======================================================================

    @Test
    @DisplayName("INB-S1-01 WK 登记 → 单据 REGISTERED + 库存增加 + INBOUND 流水")
    void s1_registerByWk() {
        long tenantId = 700_000_100_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        long wk = seedWkUser(tenantId);
        // 模拟登录态：可信租户 = 商户租户（TenantLine 会注入该租户条件）
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wk, "WK"));

        InboundRequestVo vo = inboundRequestService.registerByWk(dto(wid, sku, 30, 2), wk);

        assertThat(vo.getStatus()).isEqualTo(InboundRequest.STATUS_REGISTERED);
        assertThat(vo.getDocNo()).startsWith("WK-");
        assertThat(vo.getQty()).isEqualTo(30);
        assertThat(vo.getCurrentStock()).isEqualTo(30);

        // 库存增加 + INBOUND 流水各 1
        assertThat(inventoryService.queryInventory(wid, sku).get(0).getQty()).isEqualTo(30);
        assertThat(countMovements(wid, sku)).isEqualTo(1);

        // 二次登记累加（单据与库存同增）
        InboundRequestVo vo2 = inboundRequestService.registerByWk(dto(wid, sku, 20, null), wk);
        assertThat(vo2.getCurrentStock()).isEqualTo(50);
        assertThat(inventoryService.queryInventory(wid, sku).get(0).getQty()).isEqualTo(50);
        assertThat(countMovements(wid, sku)).isEqualTo(2);

        // 列表能查到本租户入库单（2 条）
        assertThat(inboundRequestService.listByTenant(tenantId, wid)).hasSize(2);
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("INB-S2-01 qty<=0 → 拒绝（INBOUND_QTY_INVALID）且不增库存/不出单")
    void s2_qtyInvalid() {
        long tenantId = 700_000_200_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        long wk = seedWkUser(tenantId);
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wk, "WK"));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.registerByWk(dto(wid, sku, 0, 0), wk));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INBOUND_QTY_INVALID);

        // 未建库存行、无流水、无单据
        assertThat(inventoryService.queryInventory(wid, sku)).isEmpty();
        assertThat(countMovements(wid, sku)).isZero();
        assertThat(inboundRequestService.listByTenant(tenantId, wid)).isEmpty();
    }

    @Test
    @DisplayName("INB-S2-02 sku 不属该 wholesaler（缺 sku）→ 拒绝（SKU_NOT_FOUND）")
    void s2_skuNotBelong() {
        long tenantId = 700_000_300_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long widA = seedWholesaler(tenantId);
        long widB = seedWholesaler(tenantId);
        long skuOfB = seedSku(tenantId, widB);   // sku 属 B
        long wk = seedWkUser(tenantId);
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wk, "WK"));

        // 为 A 登记 B 的 sku → SKU_NOT_FOUND
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.registerByWk(dto(widA, skuOfB, 5, 0), wk));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SKU_NOT_FOUND);
    }

    // ======================================================================
    // S4 越权 / 跨租户
    // ======================================================================

    @Test
    @DisplayName("INB-S4-01 操作人非 WK（无 WK 角色）→ 拒绝（INBOUND_OPERATOR_NOT_WK）")
    void s4_notWk() {
        long tenantId = 700_000_400_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        long notWk = snowflakeIdUtil.nextId();   // 无任何 WK 角色
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, notWk, "TA"));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.registerByWk(dto(wid, sku, 5, 0), notWk));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INBOUND_OPERATOR_NOT_WK);
        assertThat(countMovements(wid, sku)).isZero();
    }

    @Test
    @DisplayName("INB-S4-02 跨租户：WK 用他租户上下文为他租户商户登记 → 拒绝（不可见 WHOLESALER_NOT_FOUND）")
    void s4_crossTenant() {
        long tenantA = 700_000_500_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long tenantB = 700_000_600_001L + (snowflakeIdUtil.nextId() & 0xFFFF);
        long widA = seedWholesaler(tenantA);
        long skuA = seedSku(tenantA, widA);
        long wkB = seedWkUser(tenantB);          // B 租户的 WK
        // 登录态为 B 租户 ⇒ TenantLine 注入 tenant=B 条件，A 的商户对 B 不可见
        TenantContext.set(TenantContext.TenantInfo.of(tenantB, wkB, "WK"));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inboundRequestService.registerByWk(dto(widA, skuA, 5, 0), wkB));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WHOLESALER_NOT_FOUND);
        assertThat(countMovements(widA, skuA)).isZero();
    }
}
