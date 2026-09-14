package com.cangchu.storefront;

import com.cangchu.CangchuApplication;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.RtSkuSearchItemVo;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RT 首页「搜商品找仓库」公开跨仓检索场景测试（先搜货再进店）。
 *
 * <p>公开端点无登录态，调用前清空 TenantContext；直接 mapper seed 租户/店铺/商户/SKU/库存，
 * 经 {@link StoreFrontService#searchSkus} 验证匹配范围、在售口径、排序与 LIKE 转义。
 */
@SpringBootTest(classes = CangchuApplication.class)
class RtSkuSearchScenarioTest {

    /** 杭州东站附近（测试定位中心） */
    private static final BigDecimal CENTER_LAT = new BigDecimal("30.2741");
    private static final BigDecimal CENTER_LNG = new BigDecimal("120.1552");

    @Autowired
    private StoreFrontService storeFrontService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed ====================

    private Tenant seedTenant(String status, String simpleCodePrefix) {
        Tenant t = new Tenant();
        Long id = snowflakeIdUtil.nextId();
        t.setId(id);
        t.setName("搜商品测试-" + simpleCodePrefix + "-" + id);
        t.setTenantSimpleCode(simpleCodePrefix + Math.abs(id % 1000L));
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        t.setStatus(status);
        tenantMapper.insert(t);
        return t;
    }

    private Store seedStore(Tenant tenant, String name, BigDecimal lat, BigDecimal lng) {
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenant.getId());
        s.setName(name);
        s.setStatus("ACTIVE");
        s.setLat(lat);
        s.setLng(lng);
        storeMapper.insert(s);
        return s;
    }

    private Wholesaler seedWholesaler(Tenant tenant, String name, String status) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenant.getId());
        w.setName(name);
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus(status);
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return w;
    }

    private Sku seedSku(Tenant tenant, Wholesaler wholesaler, String name, String spec, boolean listed) {
        Sku k = new Sku();
        k.setId(snowflakeIdUtil.nextId());
        k.setTenantId(tenant.getId());
        k.setWholesalerId(wholesaler.getId());
        k.setName(name);
        k.setSpec(spec);
        k.setUnitPrice(new BigDecimal("10.00"));
        k.setMoqPrice(new BigDecimal("9.00"));
        k.setMoqQty(10);
        k.setListed(listed);
        skuMapper.insert(k);
        return k;
    }

    private void seedInventory(Tenant tenant, Wholesaler wholesaler, Sku sku, int qty) {
        Inventory i = new Inventory();
        i.setId(snowflakeIdUtil.nextId());
        i.setTenantId(tenant.getId());
        i.setWholesalerId(wholesaler.getId());
        i.setSkuId(sku.getId());
        i.setQty(qty);
        i.setPalletQty(0);
        inventoryMapper.insert(i);
    }

    // ==================== cases ====================

    @Test
    void search_matchesKeyword_acrossStores_andSortsByDistance() {
        // A：定位中心仓，有匹配 SKU
        Tenant tenantA = seedTenant("ACTIVE", "SA");
        seedStore(tenantA, "中心仓", CENTER_LAT, CENTER_LNG);
        Wholesaler waA = seedWholesaler(tenantA, "中心商户A", "ACTIVE");
        seedInventory(tenantA, waA, seedSku(tenantA, waA, "红富士苹果", "5kg/箱", true), 10);

        // B：远处仓，也有匹配 SKU
        Tenant tenantB = seedTenant("ACTIVE", "SB");
        seedStore(tenantB, "东北仓", new BigDecimal("30.3500"), new BigDecimal("120.2200"));
        Wholesaler waB = seedWholesaler(tenantB, "东北商户B", "ACTIVE");
        seedInventory(tenantB, waB, seedSku(tenantB, waB, "阿克苏苹果", "10kg/箱", true), 5);

        // C：PENDING 租户，即使有匹配 SKU 也不可检索（未审核仓不对外）
        Tenant tenantC = seedTenant("PENDING", "SC");
        seedStore(tenantC, "未审核仓", CENTER_LAT, CENTER_LNG);
        Wholesaler waC = seedWholesaler(tenantC, "未审核商户C", "ACTIVE");
        seedInventory(tenantC, waC, seedSku(tenantC, waC, "苹果梨", "1kg/袋", true), 8);

        // A 店内不匹配关键词的 SKU，不应返回
        seedInventory(tenantA, waA, seedSku(tenantA, waA, "海南香蕉", "5kg/箱", true), 10);

        TenantContext.clear();
        List<RtSkuSearchItemVo> list = storeFrontService.searchSkus("苹果", CENTER_LAT, CENTER_LNG, 50);

        // H2 测试库跨用例共享：先收敛到本用例 seed 的两个租户再断言
        Set<String> ours = Set.of(tenantA.getTenantSimpleCode(), tenantB.getTenantSimpleCode());
        List<RtSkuSearchItemVo> mine = list.stream()
                .filter(i -> ours.contains(i.getTenantSimpleCode()))
                .toList();
        assertThat(mine).hasSize(2);
        assertThat(list).noneMatch(i -> tenantC.getTenantSimpleCode().equals(i.getTenantSimpleCode()));
        assertThat(list).noneMatch(i -> "海南香蕉".equals(i.getName()));

        // 距离排序：中心仓（距离 0）在前
        RtSkuSearchItemVo first = mine.get(0);
        assertThat(first.getTenantSimpleCode()).isEqualTo(tenantA.getTenantSimpleCode());
        assertThat(first.getDistanceMeters()).isEqualTo(0);
        assertThat(first.getWholesalerName()).isEqualTo("中心商户A");
        assertThat(first.getStoreName()).isEqualTo("中心仓");
        assertThat(first.getName()).isEqualTo("红富士苹果");
        assertThat(first.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(first.getStockQty()).isEqualTo(10);

        RtSkuSearchItemVo second = mine.get(1);
        assertThat(second.getDistanceMeters()).isNotNull().isGreaterThan(0);
    }

    @Test
    void search_filtersUnlisted_andZeroStock() {
        Tenant tenant = seedTenant("ACTIVE", "SF");
        seedStore(tenant, "过滤口径仓", null, null);
        Wholesaler wa = seedWholesaler(tenant, "过滤商户", "ACTIVE");

        // 在售有货：应命中
        seedInventory(tenant, wa, seedSku(tenant, wa, "苹果汁", "1L/瓶", true), 5);
        // 已下架：不命中
        seedInventory(tenant, wa, seedSku(tenant, wa, "苹果醋", "500ml/瓶", false), 5);
        // 无货：不命中
        seedInventory(tenant, wa, seedSku(tenant, wa, "苹果派", "6个/盒", true), 0);

        TenantContext.clear();
        List<RtSkuSearchItemVo> list = storeFrontService.searchSkus("苹果", null, null, 50);

        // 收敛到本用例租户（H2 测试库跨用例共享，其他用例可能 seed 了同名前缀 SKU）
        List<RtSkuSearchItemVo> mine = list.stream()
                .filter(i -> tenant.getTenantSimpleCode().equals(i.getTenantSimpleCode()))
                .toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getName()).isEqualTo("苹果汁");
        // 下架/无货行全局不可见
        assertThat(list).noneMatch(i -> "苹果醋".equals(i.getName()) || "苹果派".equals(i.getName()));
    }

    @Test
    void search_escapesLikeWildcards() {
        Tenant tenant = seedTenant("ACTIVE", "SE");
        seedStore(tenant, "转义口径仓", null, null);
        Wholesaler wa = seedWholesaler(tenant, "转义商户", "ACTIVE");

        // 名称本身含 %：关键词 "100%" 若未转义会当通配符，把 "1001纯果汁" 一并命中
        seedInventory(tenant, wa, seedSku(tenant, wa, "100%纯果汁", "1L/瓶", true), 5);
        seedInventory(tenant, wa, seedSku(tenant, wa, "1001纯果汁", "1L/瓶", true), 5);

        TenantContext.clear();
        List<RtSkuSearchItemVo> list = storeFrontService.searchSkus("100%", null, null, 20);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).isEqualTo("100%纯果汁");
    }

    @Test
    void search_blankKeyword_returnsEmpty_andLimitCapped() {
        Tenant tenant = seedTenant("ACTIVE", "SG");
        seedStore(tenant, "空关键词仓", CENTER_LAT, CENTER_LNG);
        Wholesaler wa = seedWholesaler(tenant, "空关键词商户", "ACTIVE");
        seedInventory(tenant, wa, seedSku(tenant, wa, "苹果干", "100g/袋", true), 5);

        TenantContext.clear();
        assertThat(storeFrontService.searchSkus("  ", null, null, 20)).isEmpty();
        assertThat(storeFrontService.searchSkus(null, null, null, 20)).isEmpty();
        // limit 上限 50：传 100 也最多 50 条
        assertThat(storeFrontService.searchSkus("苹果干", CENTER_LAT, CENTER_LNG, 100)).hasSizeLessThanOrEqualTo(50);
    }
}
