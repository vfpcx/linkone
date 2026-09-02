package com.cangchu.storefront;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.pricing.vo.CustomerPriceRef;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.RtPriceItemVo;
import com.cangchu.storefront.vo.RtPriceListVo;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 · RT「我的价目」（US-RT-05 专属价复购，architecture/23-p5-c-c1 §6）场景测试。
 *
 * <p>沿用 {@code InquiryScenarioTest}/{@code PricingRtMatchScenarioTest} 风格：mapper 直接 seed
 * （tenant/store/wholesaler/sku/customer_price）+ 经 {@link InventoryService} 入库种库存 +
 * 操作 {@link TenantContext}。价目查询为公开只读端点（无登录态），调用前清空 TenantContext。
 *
 * <p>覆盖：
 * <ul>
 *   <li>RP-01 manual + from_inquiry 双来源价目行均入清单、字段正确。</li>
 *   <li>RP-02 过期（ACTIVE+expireAt 过去）与 DISABLED 行不出现。</li>
 *   <li>RP-03 按 wholesaler 分组；无有效价目行的商户不出组。</li>
 *   <li>RP-04 跨店（跨 tenant）隔离：同 phone 换店查不到别店价目。</li>
 *   <li>RP-05 响应不含手机号明文（仅尾号 4 位归属提示）。</li>
 *   <li>RP-06 SKU 已下架 → 行仍返回且 listed=false（前端置灰）。</li>
 *   <li>RP-07 空手机号 / 店铺不存在 → 拒绝。</li>
 *   <li>RP-08 纯只读：调用不产生任何询价单。</li>
 *   <li>RP-09 价目勾选提交复用 submitByRt 链路成功建单（PENDING）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class RtPriceListScenarioTest {

    private static final String RT_PHONE = "13800006666";

    @Autowired
    private StoreFrontService storeFrontService;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private SkuService skuService;
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
    private InquiryRequestMapper inquiryRequestMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private ObjectMapper objectMapper;

    /** 一店一商户一 SKU 的最小种子。 */
    private record StoreSeed(long tenantId, long storeId, long wholesalerId, long skuId) {
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed helpers ====================

    private long seedTenant(long bucket) {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setName("仓库-" + bucket + "-" + t.getId());
        // tenant_simple_code 列 VARCHAR(8)：rp + bucket(≤2) + id 尾 3 位 = ≤7
        t.setTenantSimpleCode("rp" + bucket + Math.abs(t.getId() % 1000L));
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        return t.getId();
    }

    private long insertStore(long tenantId) {
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

    /** 一店一商户一 SKU（上架）基础种子 + 100 库存。 */
    private StoreSeed seedStore(long bucket) {
        long tenantId = seedTenant(bucket);
        long storeId = insertStore(tenantId);
        long wholesalerId = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wholesalerId, true);
        return new StoreSeed(tenantId, storeId, wholesalerId, skuId);
    }

    private long seedSku(long tenantId, long wholesalerId, boolean listed) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(listed);
        skuMapper.insert(s);
        return s.getId();
    }

    /** 经 service 入库种库存（不暴露公开加库存 HTTP）。 */
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

    private void seedCustomerPrice(long tenantId, long wholesalerId, long skuId, String phone,
                                   BigDecimal unitPrice, String source, String status, LocalDateTime expireAt) {
        CustomerPrice cp = new CustomerPrice();
        cp.setId(snowflakeIdUtil.nextId());
        cp.setTenantId(tenantId);
        cp.setWholesalerId(wholesalerId);
        cp.setSkuId(skuId);
        cp.setRtPhoneHmac(piiCrypto.phoneHmac(phone));
        cp.setRtPhoneLast4(piiCrypto.last4(phone));
        cp.setUnitPrice(unitPrice);
        cp.setStatus(status);
        cp.setSource(source);
        cp.setExpireAt(expireAt);
        customerPriceMapper.insert(cp);
    }

    private RtPriceItemVo findItem(RtPriceListVo vo, long skuId) {
        return vo.getWholesalers().stream()
                .flatMap(g -> g.getItems().stream())
                .filter(i -> i.getSkuId().equals(skuId))
                .findFirst().orElse(null);
    }

    // ==================== RP-01 ====================

    @Test
    void rtPriceList_combinesManualAndFromInquiry_sources() {
        StoreSeed s = seedStore(1);
        long skuB = seedSku(s.tenantId(), s.wholesalerId(), true);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        seedStock(s.tenantId(), s.wholesalerId(), skuB, 80);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), skuB, RT_PHONE,
                new BigDecimal("5.50"), CustomerPrice.SOURCE_FROM_INQUIRY, CustomerPrice.STATUS_ACTIVE, null);

        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);

        assertThat(vo.getRtPhoneLast4()).isEqualTo("6666");
        assertThat(vo.getWholesalers()).hasSize(1);
        assertThat(vo.getWholesalers().get(0).getWholesalerId()).isEqualTo(s.wholesalerId());
        RtPriceItemVo a = findItem(vo, s.skuId());
        assertThat(a).isNotNull();
        assertThat(a.getCustomerPrice()).isEqualByComparingTo("6.00");
        assertThat(a.getSource()).isEqualTo(CustomerPrice.SOURCE_MANUAL);
        assertThat(a.getUnitPrice()).isEqualByComparingTo("9.90");   // 公开价对照
        assertThat(a.getStockQty()).isEqualTo(100);
        assertThat(a.getListed()).isTrue();
        RtPriceItemVo b = findItem(vo, skuB);
        assertThat(b).isNotNull();
        assertThat(b.getCustomerPrice()).isEqualByComparingTo("5.50");
        assertThat(b.getSource()).isEqualTo(CustomerPrice.SOURCE_FROM_INQUIRY);
        assertThat(b.getStockQty()).isEqualTo(80);
    }

    // ==================== RP-02 ====================

    @Test
    void rtPriceList_filtersExpiredOrDisabled() {
        StoreSeed s = seedStore(2);
        long skuExpired = seedSku(s.tenantId(), s.wholesalerId(), true);
        long skuDisabled = seedSku(s.tenantId(), s.wholesalerId(), true);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 10);
        seedStock(s.tenantId(), s.wholesalerId(), skuExpired, 10);
        seedStock(s.tenantId(), s.wholesalerId(), skuDisabled, 10);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), skuExpired, RT_PHONE,
                new BigDecimal("7.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE,
                LocalDateTime.now().minusDays(1));
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), skuDisabled, RT_PHONE,
                new BigDecimal("8.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_DISABLED,
                LocalDateTime.now().plusDays(30));

        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);

        assertThat(vo.getWholesalers()).hasSize(1);
        assertThat(findItem(vo, s.skuId())).isNotNull();
        assertThat(findItem(vo, skuExpired)).isNull();
        assertThat(findItem(vo, skuDisabled)).isNull();
    }

    // ==================== RP-03 ====================

    @Test
    void rtPriceList_groupsOnlyWholesalersWithPrices() {
        long tenantId = seedTenant(3);
        long storeId = insertStore(tenantId);
        long w1 = seedWholesaler(tenantId);
        long w2 = seedWholesaler(tenantId);   // 无有效价目 → 不出组
        long skuA = seedSku(tenantId, w1, true);
        seedSku(tenantId, w2, true);
        seedStock(tenantId, w1, skuA, 50);
        seedCustomerPrice(tenantId, w1, skuA, RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);

        RtPriceListVo vo = storeFrontService.getMyPriceList(storeId, null, RT_PHONE);

        assertThat(vo.getWholesalers()).hasSize(1);
        assertThat(vo.getWholesalers().get(0).getWholesalerId()).isEqualTo(w1);
        assertThat(vo.getWholesalers().get(0).getItems()).hasSize(1);
    }

    // ==================== RP-04 ====================

    @Test
    void rtPriceList_isolatedAcrossStores() {
        StoreSeed s1 = seedStore(4);
        StoreSeed s2 = seedStore(5);
        seedStock(s1.tenantId(), s1.wholesalerId(), s1.skuId(), 10);
        seedStock(s2.tenantId(), s2.wholesalerId(), s2.skuId(), 10);
        // 同一 RT 手机号在店 1、店 2 各有专属价
        seedCustomerPrice(s1.tenantId(), s1.wholesalerId(), s1.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        seedCustomerPrice(s2.tenantId(), s2.wholesalerId(), s2.skuId(), RT_PHONE,
                new BigDecimal("9.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);

        RtPriceListVo vo = storeFrontService.getMyPriceList(s1.storeId(), null, RT_PHONE);

        assertThat(vo.getWholesalers()).hasSize(1);
        assertThat(vo.getWholesalers().get(0).getWholesalerId()).isEqualTo(s1.wholesalerId());
        assertThat(findItem(vo, s1.skuId())).isNotNull();
        assertThat(findItem(vo, s2.skuId())).isNull();
        assertThat(vo.getWholesalers().get(0).getItems())
                .extracting(RtPriceItemVo::getSkuId).containsOnly(s1.skuId());
    }

    // ==================== RP-05 ====================

    @Test
    void rtPriceList_masksPhone_neverReturnsPlaintext() throws Exception {
        StoreSeed s = seedStore(6);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 10);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);

        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);

        assertThat(vo.getRtPhoneLast4()).isEqualTo("6666");
        String json = objectMapper.writeValueAsString(vo);
        assertThat(json).doesNotContain(RT_PHONE).doesNotContain("1380000");
        assertThat(json).contains("6666");   // 尾号归属提示可见
    }

    // ==================== RP-06 ====================

    @Test
    void rtPriceList_marksUnlistedSku() {
        StoreSeed s = seedStore(7);
        long skuOff = seedSku(s.tenantId(), s.wholesalerId(), true);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 10);
        seedStock(s.tenantId(), s.wholesalerId(), skuOff, 10);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), skuOff, RT_PHONE,
                new BigDecimal("4.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        // 模拟 WA 将 skuOff 下架（走 toggleListing，权限=本商户 WA）
        long waUserId = seedWaUser(s.tenantId(), s.wholesalerId());
        skuService.toggleListing(skuOff, false, waUserId);

        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);

        assertThat(findItem(vo, s.skuId()).getListed()).isTrue();
        RtPriceItemVo off = findItem(vo, skuOff);
        assertThat(off).isNotNull();                 // 价目行仍返回
        assertThat(off.getListed()).isFalse();       // 前端置灰禁提交
        assertThat(off.getCustomerPrice()).isEqualByComparingTo("4.00");
    }

    // ==================== RP-07 ====================

    @Test
    void rtPriceList_rejectsBlankPhone() {
        StoreSeed s = seedStore(8);
        assertThatThrownBy(() -> storeFrontService.getMyPriceList(s.storeId(), null, "  "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rtPriceList_rejectsUnknownStore() {
        assertThatThrownBy(() -> storeFrontService.getMyPriceList(null, "NO_SUCH_CODE_" + System.currentTimeMillis(),
                        RT_PHONE))
                .isInstanceOf(BizException.class);
    }

    // ==================== RP-08 ====================

    @Test
    void rtPriceList_isReadOnly_noInquiryCreated() {
        StoreSeed s = seedStore(9);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 10);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        long before = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, s.tenantId()));

        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);

        assertThat(vo.getWholesalers()).hasSize(1);
        long after = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, s.tenantId()));
        assertThat(after).isEqualTo(before);
    }

    // ==================== RP-09 ====================

    @Test
    void rtPriceList_submitFollowsExistingInquiryChain() {
        StoreSeed s = seedStore(10);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        seedCustomerPrice(s.tenantId(), s.wholesalerId(), s.skuId(), RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        // 价目所见行（前端勾选后走现提交链路）
        RtPriceListVo vo = storeFrontService.getMyPriceList(s.storeId(), null, RT_PHONE);
        RtPriceItemVo item = findItem(vo, s.skuId());
        assertThat(item).isNotNull();
        assertThat(item.getCustomerPrice()).isEqualByComparingTo("6.00");

        SubmitInquiryDto dto = new SubmitInquiryDto();
        dto.setStoreId(s.storeId());
        dto.setWholesalerId(s.wholesalerId());
        dto.setRtPhone(RT_PHONE);
        SubmitInquiryDto.InquiryItemDto it = new SubmitInquiryDto.InquiryItemDto();
        it.setSkuId(s.skuId());
        it.setQty(2);
        dto.setItems(List.of(it));

        InquiryVo inq = inquiryService.submitByRt(dto);

        assertThat(inq.getDocNo()).isNotBlank();
        assertThat(inq.getStatus()).isEqualTo("PENDING");
        assertThat(inq.getWholesalerId()).isEqualTo(s.wholesalerId());
    }

    // ==================== 跨域出口冒烟（轻量 VO 不直连 mapper） ====================

    @Test
    void pricingExport_listActiveRefsByPhone_mapsOnlyActiveRows() {
        long tenantId = seedTenant(11);
        long wholesalerId = seedWholesaler(tenantId);
        long skuA = seedSku(tenantId, wholesalerId, true);
        long skuB = seedSku(tenantId, wholesalerId, true);
        seedCustomerPrice(tenantId, wholesalerId, skuA, RT_PHONE,
                new BigDecimal("6.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE, null);
        seedCustomerPrice(tenantId, wholesalerId, skuB, RT_PHONE,
                new BigDecimal("7.00"), CustomerPrice.SOURCE_MANUAL, CustomerPrice.STATUS_ACTIVE,
                LocalDateTime.now().minusDays(1));

        List<CustomerPriceRef> refs =
                pricingService.listActiveRefsByPhone(wholesalerId, piiCrypto.phoneHmac(RT_PHONE));

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getSkuId()).isEqualTo(skuA);
        assertThat(refs.get(0).getUnitPrice()).isEqualByComparingTo("6.00");
        assertThat(refs.get(0).getSource()).isEqualTo(CustomerPrice.SOURCE_MANUAL);
    }

    @Test
    void skuExport_listForRtBySkuIds_includesUnlisted_onlyWithinWholesaler() {
        long tenantId = seedTenant(12);
        long w1 = seedWholesaler(tenantId);
        long w2 = seedWholesaler(tenantId);
        long skuOn = seedSku(tenantId, w1, true);
        long skuOff = seedSku(tenantId, w1, false);
        long skuOther = seedSku(tenantId, w2, true);

        List<SkuVo> vos = skuService.listForRtBySkuIds(tenantId, w1, List.of(skuOn, skuOff, skuOther));

        assertThat(vos).extracting(SkuVo::getId).containsExactlyInAnyOrder(skuOn, skuOff);
        assertThat(vos).extracting(SkuVo::getListed)
                .containsExactlyInAnyOrder(true, false);
        // 越商户的 sku 查不到（不泄漏）
        assertThat(vos).extracting(SkuVo::getId).doesNotContain(skuOther);
    }
}
