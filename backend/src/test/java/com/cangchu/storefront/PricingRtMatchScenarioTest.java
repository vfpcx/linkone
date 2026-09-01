package com.cangchu.storefront;

import com.cangchu.CangchuApplication;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
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
 * RT 浏览价格匹配 + 可选鉴权 场景测试（P2 定价 Wave 3b）。
 *
 * <p>沿用 {@code PricingSettleScenarioTest} 的 mapper-seed 风格：直接 seed
 * tenant(ACTIVE)/store/wholesaler/sku + 经 {@link InventoryService} 种库存 +
 * mapper 直插一条 ACTIVE 客户专属价，随后经 {@link StoreFrontService} 的可选鉴权入口
 * （{@code listSkus(storeId, code, wholesalerId, rtPhone)} / {@code getStorePage(.., rtPhone)}，
 * 内部即 {@code buildOnSaleSkus(tenantId, wholesalerId, rtPhone)}）验证 matchedPrice 口径。
 *
 * <p>RT 浏览无登录态/无 TenantContext，故断言前 {@link TenantContext#clear()}，
 * 真实还原匿名/登录 RT 扫码进店路径。
 *
 * <p>覆盖（matchedPrice 语义）：
 * <ul>
 *   <li>S1-01 登录 RT（有专属价，且≠公开价）→ 该 SKU {@code matchedPrice}=专属价、{@code unitPrice}=公开价。</li>
 *   <li>S1-02 匿名（rtPhone=null）→ {@code matchedPrice}=null，{@code unitPrice}=公开价。</li>
 *   <li>S1-03 登录 RT 但无专属价 → {@code matchedPrice}=null（回退公开价，不叠加）。</li>
 *   <li>S1-04 专属价==公开价 → {@code matchedPrice}=null（叠加字段仅在"不同于公开价"时出现）。</li>
 *   <li>S2-01 /store 聚合路径同样按 rtPhone 下发 matchedPrice。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class PricingRtMatchScenarioTest {

    @Autowired
    private StoreFrontService storeFrontService;
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
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private PiiCrypto piiCrypto;

    /** 公开价基准：unitPrice=9.90（浏览 qty=1 < moqQty → 走单价），moqPrice=8.50，moqQty=10。 */
    private static final BigDecimal PUBLIC_UNIT_PRICE = new BigDecimal("9.90");

    private static final AtomicInteger TENANT_CODE_SEQ = new AtomicInteger(0);
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed helpers（沿用 PricingSettleScenarioTest 口径） ====================

    private String rtPhone() {
        return "18" + String.format("%09d", PHONE_SEQ.incrementAndGet() % 1_000_000_000L);
    }

    /** 一个完整的 ACTIVE 店：tenantId + storeId + wholesalerId + skuId（已种库存）。 */
    private record StoreCtx(long tenantId, long storeId, long wholesalerId, long skuId) {}

    private StoreCtx seedActiveStoreWithOnSaleSku(long bucket) {
        long tenantId = bucket + (snowflakeIdUtil.nextId() & 0xFFFF);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode("R" + String.format("%07d", TENANT_CODE_SEQ.incrementAndGet()));
        t.setName("匹配仓-" + tenantId);
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);

        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);

        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);

        Sku sku = new Sku();
        sku.setId(snowflakeIdUtil.nextId());
        sku.setTenantId(tenantId);
        sku.setWholesalerId(w.getId());
        sku.setName("品-" + sku.getId());
        sku.setUnitPrice(PUBLIC_UNIT_PRICE);
        sku.setMoqPrice(new BigDecimal("8.50"));
        sku.setMoqQty(10);
        sku.setListed(true);
        skuMapper.insert(sku);

        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(w.getId())
                .tenantId(tenantId)
                .skuId(sku.getId())
                .qty(100)
                .refDocNo("IN-RTMATCH")
                .operatorUserId(1L)
                .build());

        return new StoreCtx(tenantId, s.getId(), w.getId(), sku.getId());
    }

    /** 直插一条 ACTIVE 客户专属价（无登录态 → 显式塞 tenantId，MetaObjectHandler 不自动填充）。 */
    private void seedCustomerPrice(StoreCtx ctx, String phone, BigDecimal unitPrice) {
        CustomerPrice cp = new CustomerPrice();
        cp.setId(snowflakeIdUtil.nextId());
        cp.setTenantId(ctx.tenantId());
        cp.setWholesalerId(ctx.wholesalerId());
        cp.setSkuId(ctx.skuId());
        cp.setRtPhoneHmac(piiCrypto.phoneHmac(phone));
        cp.setRtPhoneLast4(piiCrypto.last4(phone));
        cp.setUnitPrice(unitPrice);
        cp.setStatus(CustomerPrice.STATUS_ACTIVE);
        cp.setSource(CustomerPrice.SOURCE_MANUAL);
        cp.setCreatedBy(1L);
        customerPriceMapper.insert(cp);
    }

    /** 经可选鉴权入口取该店该商户在售 SKU（内部即 buildOnSaleSkus(tenantId, wholesalerId, rtPhone)）。 */
    private StoreSkuVo onlySku(StoreCtx ctx, String rtPhone) {
        TenantContext.clear(); // RT 浏览无登录态
        List<StoreSkuVo> skus = storeFrontService.listSkus(ctx.storeId(), null, ctx.wholesalerId(), rtPhone);
        return skus.stream()
                .filter(v -> v.getSkuId().equals(ctx.skuId()))
                .findFirst().orElseThrow();
    }

    // ======================================================================
    // S1 matchedPrice 语义（服务层，可选鉴权入口）
    // ======================================================================

    @Test
    @DisplayName("RTMATCH-S1-01 登录 RT（有专属价≠公开价）→ matchedPrice=专属价, unitPrice=公开价")
    void s1_loggedInRtSeesMatchedPrice() {
        StoreCtx ctx = seedActiveStoreWithOnSaleSku(731_000_100_001L);
        String phone = rtPhone();
        seedCustomerPrice(ctx, phone, new BigDecimal("6.00"));

        StoreSkuVo sku = onlySku(ctx, phone);

        assertThat(sku.getMatchedPrice())
                .as("命中专属价应下发 matchedPrice=6.00").isEqualByComparingTo("6.00");
        assertThat(sku.getUnitPrice())
                .as("unitPrice 恒为公开价，不随登录态变化").isEqualByComparingTo(PUBLIC_UNIT_PRICE);
    }

    @Test
    @DisplayName("RTMATCH-S1-02 匿名（rtPhone=null）→ matchedPrice=null, unitPrice=公开价")
    void s1_anonymousSeesPublicOnly() {
        StoreCtx ctx = seedActiveStoreWithOnSaleSku(731_000_110_001L);
        // 即便库里存在别的手机号的专属价，匿名也只看公开价
        seedCustomerPrice(ctx, rtPhone(), new BigDecimal("6.00"));

        StoreSkuVo sku = onlySku(ctx, null);

        assertThat(sku.getMatchedPrice()).as("匿名无专属价字段").isNull();
        assertThat(sku.getUnitPrice()).isEqualByComparingTo(PUBLIC_UNIT_PRICE);
    }

    @Test
    @DisplayName("RTMATCH-S1-03 登录 RT 但无专属价 → matchedPrice=null（回退公开价）")
    void s1_loggedInRtWithoutCustomerPrice() {
        StoreCtx ctx = seedActiveStoreWithOnSaleSku(731_000_120_001L);
        String phoneWithoutPrice = rtPhone(); // 不为其 seed 专属价

        StoreSkuVo sku = onlySku(ctx, phoneWithoutPrice);

        assertThat(sku.getMatchedPrice()).as("无专属价则不叠加 matchedPrice").isNull();
        assertThat(sku.getUnitPrice()).isEqualByComparingTo(PUBLIC_UNIT_PRICE);
    }

    @Test
    @DisplayName("RTMATCH-S1-04 专属价==公开价 → matchedPrice=null（仅'不同于公开价'才展示）")
    void s1_matchedEqualsPublicHidden() {
        StoreCtx ctx = seedActiveStoreWithOnSaleSku(731_000_130_001L);
        String phone = rtPhone();
        seedCustomerPrice(ctx, phone, PUBLIC_UNIT_PRICE); // 专属价恰等于公开单价

        StoreSkuVo sku = onlySku(ctx, phone);

        assertThat(sku.getMatchedPrice())
                .as("专属价==公开价时不冗余下发 matchedPrice").isNull();
        assertThat(sku.getUnitPrice()).isEqualByComparingTo(PUBLIC_UNIT_PRICE);
    }

    // ======================================================================
    // S2 /store 聚合路径同样按 rtPhone 下发 matchedPrice
    // ======================================================================

    @Test
    @DisplayName("RTMATCH-S2-01 getStorePage(.., rtPhone) 聚合路径下发 matchedPrice")
    void s2_storePageCarriesMatchedPrice() {
        StoreCtx ctx = seedActiveStoreWithOnSaleSku(731_000_200_001L);
        String phone = rtPhone();
        seedCustomerPrice(ctx, phone, new BigDecimal("7.00"));

        TenantContext.clear();
        StoreFrontVo page = storeFrontService.getStorePage(ctx.storeId(), null, phone);

        StoreWholesalerVo wa = page.getWholesalers().stream()
                .filter(w -> w.getWholesalerId().equals(ctx.wholesalerId()))
                .findFirst().orElseThrow();
        StoreSkuVo sku = wa.getSkus().stream()
                .filter(v -> v.getSkuId().equals(ctx.skuId()))
                .findFirst().orElseThrow();

        assertThat(sku.getMatchedPrice()).isEqualByComparingTo("7.00");
        assertThat(sku.getUnitPrice()).isEqualByComparingTo(PUBLIC_UNIT_PRICE);

        // 同页匿名口径不下发（回归：additive/nullable）
        TenantContext.clear();
        StoreFrontVo anon = storeFrontService.getStorePage(ctx.storeId(), null, null);
        StoreSkuVo anonSku = anon.getWholesalers().stream()
                .filter(w -> w.getWholesalerId().equals(ctx.wholesalerId()))
                .findFirst().orElseThrow()
                .getSkus().stream().filter(v -> v.getSkuId().equals(ctx.skuId()))
                .findFirst().orElseThrow();
        assertThat(anonSku.getMatchedPrice()).isNull();
    }
}
