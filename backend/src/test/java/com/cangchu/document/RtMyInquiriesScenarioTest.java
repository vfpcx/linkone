package com.cangchu.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.document.vo.RtInquiryListVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2 · RT「我的意向单」（US-RT-04，architecture/api-contract-storefront §3.4）场景测试。
 *
 * <p>沿用 {@code RtPriceListScenarioTest} 风格：mapper 直接 seed（tenant/store/wholesaler/sku）+
 * 经 {@link InventoryService} 入库种库存 + 操作 {@link TenantContext}；公开端点（无登录态），调用前清空
 * TenantContext。查单走 hmac 盲索引，响应仅尾号归属提示。
 *
 * <p>覆盖：
 * <ul>
 *   <li>MI-01 同 phone 多单倒序返回：docNo/status/wholesalerName/storeName/items(sku 名+价格快照) 字段正确。</li>
 *   <li>MI-02 无记录 phone → 空 inquiries（HTTP 200 语义）。</li>
 *   <li>MI-03 跨店隔离：同 phone 的 A 店单在 B 店查不到。</li>
 *   <li>MI-04 空手机号 / 店铺不存在 → 拒绝。</li>
 *   <li>MI-05 响应不含手机号明文（仅尾号 4 位归属提示）。</li>
 *   <li>MI-06 纯只读：查询不产生任何询价单。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class)
class RtMyInquiriesScenarioTest {

    private static final String RT_PHONE = "13800006666";

    @Autowired
    private InquiryService inquiryService;
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
    private InquiryRequestMapper inquiryRequestMapper;
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

    // ==================== seed helpers（同 RtPriceListScenarioTest） ====================

    private long seedTenant(long bucket) {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setName("仓库-" + bucket + "-" + t.getId());
        t.setTenantSimpleCode("mi" + bucket + Math.abs(t.getId() % 1000L));
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

    private StoreSeed seedStore(long bucket) {
        long tenantId = seedTenant(bucket);
        long storeId = insertStore(tenantId);
        long wholesalerId = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wholesalerId);
        return new StoreSeed(tenantId, storeId, wholesalerId, skuId);
    }

    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("品-" + s.getId());
        s.setSpec("5kg/箱");
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
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

    /** RT 提交一单（1 个 SKU，数量 qty）。 */
    private InquiryVo submitOne(StoreSeed s, long skuId, int qty) {
        SubmitInquiryDto dto = new SubmitInquiryDto();
        dto.setStoreId(s.storeId());
        dto.setWholesalerId(s.wholesalerId());
        dto.setRtPhone(RT_PHONE);
        SubmitInquiryDto.InquiryItemDto it = new SubmitInquiryDto.InquiryItemDto();
        it.setSkuId(skuId);
        it.setQty(qty);
        dto.setItems(List.of(it));
        return inquiryService.submitByRt(dto);
    }

    // ==================== MI-01 ====================

    @Test
    void myInquiries_listsSubmittedOrdersNewestFirst() {
        StoreSeed s = seedStore(1);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        InquiryVo first = submitOne(s, s.skuId(), 2);
        InquiryVo second = submitOne(s, s.skuId(), 5);

        RtInquiryListVo vo = inquiryService.listForRt(s.storeId(), null, RT_PHONE);

        assertThat(vo.getRtPhoneLast4()).isEqualTo("6666");
        assertThat(vo.getStoreName()).isNotBlank();
        assertThat(vo.getInquiries()).hasSize(2);
        // createdAt 倒序：second 是最新一条
        assertThat(vo.getInquiries().get(0).getInquiryId()).isEqualTo(Long.valueOf(second.getId()));
        assertThat(vo.getInquiries().get(1).getInquiryId()).isEqualTo(Long.valueOf(first.getId()));
        RtInquiryListVo.Summary top = vo.getInquiries().get(0);
        assertThat(top.getDocNo()).isNotBlank().isEqualTo(second.getDocNo());
        assertThat(top.getStatus()).isEqualTo(InquiryRequest.STATUS_PENDING);
        assertThat(top.getWholesalerId()).isEqualTo(Long.valueOf(s.wholesalerId()));
        assertThat(top.getWholesalerName()).isNotBlank();
        assertThat(top.getCreatedAt()).isNotNull();
        // items：SKU 名 + 公开价快照 + 数量
        assertThat(top.getItems()).hasSize(1);
        RtInquiryListVo.Item item = top.getItems().get(0);
        assertThat(item.getSkuId()).isEqualTo(Long.valueOf(s.skuId()));
        assertThat(item.getName()).isNotBlank();
        assertThat(item.getSpec()).isEqualTo("5kg/箱");
        assertThat(item.getQty()).isEqualTo(5);
        assertThat(item.getUnitPriceSnapshot()).isEqualByComparingTo("9.90");
        assertThat(item.getMoqPriceSnapshot()).isEqualByComparingTo("8.50");
        assertThat(item.getMoqQtySnapshot()).isEqualTo(10);
        // PENDING：成交价默认 = 提交时公开单价快照（WA 确认时可改写）
        assertThat(item.getDealPrice()).isEqualByComparingTo("9.90");
    }

    // ==================== MI-02 ====================

    @Test
    void myInquiries_returnsEmptyForUnknownPhone() {
        StoreSeed s = seedStore(2);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        submitOne(s, s.skuId(), 3);

        RtInquiryListVo vo = inquiryService.listForRt(s.storeId(), null, "13900000000");

        assertThat(vo.getInquiries()).isEmpty();
        assertThat(vo.getRtPhoneLast4()).isEqualTo("0000");
    }

    // ==================== MI-03 ====================

    @Test
    void myInquiries_isolatedAcrossStores() {
        StoreSeed s1 = seedStore(3);
        StoreSeed s2 = seedStore(4);
        seedStock(s1.tenantId(), s1.wholesalerId(), s1.skuId(), 100);
        seedStock(s2.tenantId(), s2.wholesalerId(), s2.skuId(), 100);
        // 同 phone 只在店 1 提交
        submitOne(s1, s1.skuId(), 4);

        RtInquiryListVo voAt1 = inquiryService.listForRt(s1.storeId(), null, RT_PHONE);
        RtInquiryListVo voAt2 = inquiryService.listForRt(s2.storeId(), null, RT_PHONE);

        assertThat(voAt1.getInquiries()).hasSize(1);
        assertThat(voAt2.getInquiries()).isEmpty();   // 换店查不到别店单（隔离）
        assertThat(voAt2.getStoreName()).isNotBlank();
    }

    // ==================== MI-04 ====================

    @Test
    void myInquiries_rejectsBlankPhone() {
        StoreSeed s = seedStore(5);
        assertThatThrownBy(() -> inquiryService.listForRt(s.storeId(), null, "  "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void myInquiries_rejectsUnknownStore() {
        assertThatThrownBy(() -> inquiryService.listForRt(null, "NO_SUCH_CODE_" + System.currentTimeMillis(),
                        RT_PHONE))
                .isInstanceOf(BizException.class);
    }

    // ==================== MI-05 ====================

    @Test
    void myInquiries_masksPhone_neverReturnsPlaintext() throws Exception {
        StoreSeed s = seedStore(6);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        submitOne(s, s.skuId(), 2);

        RtInquiryListVo vo = inquiryService.listForRt(s.storeId(), null, RT_PHONE);

        String json = objectMapper.writeValueAsString(vo);
        assertThat(json).doesNotContain(RT_PHONE).doesNotContain("1380000");
        assertThat(json).contains("6666");   // 尾号归属提示可见
    }

    // ==================== MI-06 ====================

    @Test
    void myInquiries_isReadOnly_noInquiryCreated() {
        StoreSeed s = seedStore(7);
        seedStock(s.tenantId(), s.wholesalerId(), s.skuId(), 100);
        submitOne(s, s.skuId(), 2);
        long before = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, s.tenantId()));

        inquiryService.listForRt(s.storeId(), null, RT_PHONE);

        long after = inquiryRequestMapper.selectCount(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, s.tenantId()));
        assertThat(after).isEqualTo(before);
    }
}
