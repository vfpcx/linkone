package com.cangchu.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ConfirmInquiryDto;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 议价沉淀 场景测试（P2 定价 Wave 3a · settle-at-confirm）。
 *
 * <p>沿用 {@code InquiryScenarioTest} 的 mapper-seed 风格：直接 seed
 * store/wholesaler/sku/userRole + 经 {@link InventoryService} 种库存 + 操控
 * {@link TenantContext} 模拟登录态。确认时传 {@link ConfirmInquiryDto} 携带议价与沉淀开关。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 议价≠公开价 + settle=true → 生成 source=from_inquiry 的专属价，resolvePrice 命中沉淀价；
 *       议价==公开价 → 不沉淀（无行）。</li>
 *   <li>S5 库存不足 → 确认整体回滚 → 无沉淀行（同事务原子性）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class PricingSettleScenarioTest {

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String RT_PHONE = "13900002222";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed helpers（沿用 InquiryScenarioTest） ====================

    private static final AtomicInteger TENANT_CODE_SEQ = new AtomicInteger(0);

    private long baseTenant(long bucket) {
        long tenantId = bucket + (snowflakeIdUtil.nextId() & 0xFFFF);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode("S" + String.format("%07d", TENANT_CODE_SEQ.incrementAndGet()));
        t.setName("沉淀仓-" + tenantId);
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhone("13800000000");
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        return tenantId;
    }

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

    /** SKU：unitPrice=9.90（公开价快照基准），moqPrice=8.50，moqQty=10。 */
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

    private SubmitInquiryDto submitDto(long storeId, long wholesalerId, long skuId, Integer qty) {
        SubmitInquiryDto d = new SubmitInquiryDto();
        d.setStoreId(storeId);
        d.setWholesalerId(wholesalerId);
        d.setRtPhone(RT_PHONE);
        SubmitInquiryDto.InquiryItemDto it = new SubmitInquiryDto.InquiryItemDto();
        it.setSkuId(skuId);
        it.setQty(qty);
        d.setItems(List.of(it));
        return d;
    }

    private ConfirmInquiryDto confirmDto(long inquiryItemId, BigDecimal dealPrice, boolean settle) {
        ConfirmInquiryDto d = new ConfirmInquiryDto();
        ConfirmInquiryDto.Item it = new ConfirmInquiryDto.Item();
        it.setInquiryItemId(inquiryItemId);
        it.setDealPrice(dealPrice);
        d.setItems(List.of(it));
        d.setSettleAsCustomerPrice(settle);
        return d;
    }

    private List<CustomerPrice> settledRows(long wholesalerId, String rtPhone, long skuId) {
        return customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhone, rtPhone)
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(CustomerPrice::getSource, CustomerPrice.SOURCE_FROM_INQUIRY));
    }

    // ======================================================================
    // S1 议价沉淀
    // ======================================================================

    @Test
    @DisplayName("SETTLE-S1-01 议价≠公开价 + settle=true → 生成 from_inquiry 专属价，resolvePrice 命中沉淀价")
    void s1_settleWhenDealDiffersFromPublic() {
        long tenantId = baseTenant(730_000_100_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 100);
        long wa = seedWaUser(tenantId, wid);

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(submitDto(store, wid, sku, 20));
        long inquiryId = submitted.getId();
        long itemId = submitted.getItems().get(0).getId();
        // 提交时公开价快照 = sku.unitPrice = 9.90
        assertThat(submitted.getItems().get(0).getUnitPriceSnapshot()).isEqualByComparingTo("9.90");

        // 议价 6.00（≠公开价 9.90）+ 沉淀
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wa, "WA"));
        InquiryVo confirmed = inquiryService.confirmByWa(inquiryId,
                confirmDto(itemId, new BigDecimal("6.00"), true), wa);
        assertThat(confirmed.getStatus()).isEqualTo("COMPLETED");

        // 生成一条 source=from_inquiry 的 ACTIVE 专属价，价=6.00
        List<CustomerPrice> rows = settledRows(wid, RT_PHONE, sku);
        assertThat(rows).hasSize(1);
        CustomerPrice cp = rows.get(0);
        assertThat(cp.getStatus()).isEqualTo(CustomerPrice.STATUS_ACTIVE);
        assertThat(cp.getUnitPrice()).isEqualByComparingTo("6.00");
        assertThat(cp.getSourceDocNo()).isEqualTo(submitted.getDocNo());
        assertThat(cp.getTenantId()).isEqualTo(tenantId);

        // resolvePrice 命中沉淀价（有 phone → 专属价 6.00，忽略 qty/起批）
        assertThat(pricingService.resolvePrice(wid, sku, RT_PHONE, 1)).isEqualByComparingTo("6.00");
        assertThat(pricingService.resolvePrice(wid, sku, RT_PHONE, 100)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("SETTLE-S1-02 议价==公开价快照 + settle=true → 不沉淀（无 from_inquiry 行）")
    void s1_noSettleWhenDealEqualsPublic() {
        long tenantId = baseTenant(730_000_110_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 100);
        long wa = seedWaUser(tenantId, wid);

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(submitDto(store, wid, sku, 20));
        long inquiryId = submitted.getId();
        long itemId = submitted.getItems().get(0).getId();

        // 议价 9.90（==公开价快照）+ settle=true → 不应沉淀
        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wa, "WA"));
        InquiryVo confirmed = inquiryService.confirmByWa(inquiryId,
                confirmDto(itemId, new BigDecimal("9.90"), true), wa);
        assertThat(confirmed.getStatus()).isEqualTo("COMPLETED");

        assertThat(settledRows(wid, RT_PHONE, sku)).isEmpty();
        // 无专属价 → resolvePrice 回退公开价（qty=20>=起批量10 → 8.50）
        assertThat(pricingService.resolvePrice(wid, sku, RT_PHONE, 20)).isEqualByComparingTo("8.50");
    }

    // ======================================================================
    // S5 库存不足 → 整体回滚 → 无沉淀行（同事务原子性）
    // ======================================================================

    @Test
    @DisplayName("SETTLE-S5-01 库存不足回滚 → 沉淀随之撤销（无 from_inquiry 行）")
    void s5_rollbackUndoesSettlement() {
        long tenantId = baseTenant(730_000_500_001L);
        long store = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long sku = seedSku(tenantId, wid);
        seedStock(tenantId, wid, sku, 5); // 仅 5 件
        long wa = seedWaUser(tenantId, wid);

        TenantContext.clear();
        InquiryVo submitted = inquiryService.submitByRt(submitDto(store, wid, sku, 20)); // 20 > 库存 5
        long inquiryId = submitted.getId();
        long itemId = submitted.getItems().get(0).getId();

        TenantContext.set(TenantContext.TenantInfo.of(tenantId, wa, "WA"));
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> inquiryService.confirmByWa(inquiryId,
                        confirmDto(itemId, new BigDecimal("6.00"), true), wa));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        // 同事务回滚 → 无沉淀行
        assertThat(settledRows(wid, RT_PHONE, sku)).isEmpty();
        // 且专属价未生效 → resolvePrice 走公开价（qty=1<起批 → 单价 9.90）
        assertThat(pricingService.resolvePrice(wid, sku, RT_PHONE, 1)).isEqualByComparingTo("9.90");
    }
}
