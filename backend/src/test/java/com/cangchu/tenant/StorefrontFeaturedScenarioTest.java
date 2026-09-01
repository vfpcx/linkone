package com.cangchu.tenant;

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
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.StorefrontFeature;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.StorefrontFeatureMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.StorefrontFeatureService;
import com.cangchu.tenant.vo.StorefrontFeatureVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5-A W4（18-p5-design §4.3/§4.4/§6）：店铺撮合配置（主推商品 / 置顶批发商）。
 *
 * <p>关键验证：① 正常保存 + 回显（有序，按数组顺序落 sort_order）；② 上限 20/5（50711/50712）；
 * ③ 重复项（50713）；④ 引用无效——非本店在售 SKU / 非本店入驻批发商 / 未上架 SKU（50714）；
 * ⑤ 覆盖写幂等（重复 PUT 结果一致，DELETE+INSERT 同事务）；⑥ storefront 出参 featured/pinned
 * 标记与主推/置顶前置排序；⑦ 非 TA 无权限（42101）/ 无店铺上下文（50210）拒绝。
 *
 * <p>沿用 {@code AnnouncementScenarioTest} 集成测试写法：直连 Service + mapper 种数据（H2 MODE=MySQL），
 * 不做 HTTP 层（端点鉴权由 {@code StorefrontFeatureEndpointTest} 单独覆盖）。
 */
@SpringBootTest(classes = CangchuApplication.class)
class StorefrontFeaturedScenarioTest {

    @Autowired
    private StorefrontFeatureService storefrontFeatureService;
    @Autowired
    private StoreFrontService storeFrontService;
    @Autowired
    private StorefrontFeatureMapper storefrontFeatureMapper;
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
    private UserRoleMapper userRoleMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== seed ====================

    /** 本店上下文：TA/WK + ACTIVE 仓/店 + 两个 ACTIVE 批发商 + 4 个 SKU（3 上架 1 下架）。 */
    private record Ctx(long tenantId, long storeId, long taUserId, long wkUserId,
                       long wholesalerA, long wholesalerB,
                       long skuA1, long skuA2, long skuB1, long skuB2, long skuUnlistedA) {
    }

    private Ctx seed() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = seedRole(null, "TA", tenantId, null);
        long wkUserId = seedRole(null, "WK", tenantId, null);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);

        long storeId = snowflakeIdUtil.nextId();
        Store s = new Store();
        s.setId(storeId);
        s.setTenantId(tenantId);
        s.setName("店铺-" + storeId);
        s.setStatus("ACTIVE");
        storeMapper.insert(s);

        long wholesalerA = seedWholesaler(tenantId, taUserId);
        long wholesalerB = seedWholesaler(tenantId, taUserId);

        long skuA1 = seedSku(tenantId, wholesalerA, true);
        long skuA2 = seedSku(tenantId, wholesalerA, true);
        long skuB1 = seedSku(tenantId, wholesalerB, true);
        long skuB2 = seedSku(tenantId, wholesalerB, true);
        long skuUnlistedA = seedSku(tenantId, wholesalerA, false);

        return new Ctx(tenantId, storeId, taUserId, wkUserId,
                wholesalerA, wholesalerB, skuA1, skuA2, skuB1, skuB2, skuUnlistedA);
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

    private long seedWholesaler(long tenantId, long ownerUserId) {
        long id = snowflakeIdUtil.nextId();
        Wholesaler w = new Wholesaler();
        w.setId(id);
        w.setTenantId(tenantId);
        w.setName("商户-" + id);
        w.setOwnerUserId(ownerUserId);
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return id;
    }

    private long seedSku(long tenantId, long wholesalerId, boolean listed) {
        long id = snowflakeIdUtil.nextId();
        Sku sku = new Sku();
        sku.setId(id);
        sku.setTenantId(tenantId);
        sku.setWholesalerId(wholesalerId);
        sku.setName("商品-" + id);
        sku.setSpec("500ml*24");
        sku.setUnitPrice(new BigDecimal("9.90"));
        sku.setMoqPrice(new BigDecimal("8.50"));
        sku.setMoqQty(10);
        sku.setListed(listed);
        skuMapper.insert(sku);
        return id;
    }

    private void seedStock(long tenantId, long wholesalerId, long skuId, int qty) {
        Inventory inv = new Inventory();
        inv.setId(snowflakeIdUtil.nextId());
        inv.setTenantId(tenantId);
        inv.setWholesalerId(wholesalerId);
        inv.setSkuId(skuId);
        inv.setQty(qty);
        inv.setPalletQty(0);
        inventoryMapper.insert(inv);
    }

    /** 另一租户的店 + 在售 SKU（用于「非本店」引用校验）。 */
    private long seedOtherTenantSku() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = seedRole(null, "TA", tenantId, null);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("他仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);

        long wholesalerId = seedWholesaler(tenantId, taUserId);
        return seedSku(tenantId, wholesalerId, true);
    }

    /** 另一租户的 ACTIVE 批发商（用于「非本店入驻批发商」引用校验）。 */
    private long seedOtherTenantWholesaler() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = seedRole(null, "TA", tenantId, null);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("他仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        return seedWholesaler(tenantId, taUserId);
    }

    private List<StorefrontFeature> rows(long storeId, String kind) {
        return storefrontFeatureMapper.selectList(new LambdaQueryWrapper<StorefrontFeature>()
                .eq(StorefrontFeature::getStoreId, storeId)
                .eq(StorefrontFeature::getKind, kind)
                .orderByAsc(StorefrontFeature::getSortOrder));
    }

    private static int codeOf(Throwable e) {
        return ((BizException) e).getCode();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("保存+回显：主推/置顶按数组顺序落 sort_order，GET 按序返回")
    void saveAndGet_returnsOrderedLists() {
        Ctx c = seed();
        storefrontFeatureService.saveMyFeatured(c.tenantId(), c.taUserId(),
                List.of(c.skuA2(), c.skuA1()), List.of(c.wholesalerB(), c.wholesalerA()));

        StorefrontFeatureVo vo = storefrontFeatureService.getMyFeatured(c.tenantId(), c.taUserId());
        assertThat(vo.getMainSkuIds()).as("主推有序").containsExactly(c.skuA2(), c.skuA1());
        assertThat(vo.getPinWaIds()).as("置顶有序").containsExactly(c.wholesalerB(), c.wholesalerA());

        List<StorefrontFeature> mainRows = rows(c.storeId(), StorefrontFeature.KIND_MAIN_SKU);
        assertThat(mainRows).extracting(StorefrontFeature::getRefId)
                .containsExactly(c.skuA2(), c.skuA1());
        assertThat(mainRows).extracting(StorefrontFeature::getSortOrder).containsExactly(0, 1);
        List<StorefrontFeature> pinRows = rows(c.storeId(), StorefrontFeature.KIND_PIN_WA);
        assertThat(pinRows).extracting(StorefrontFeature::getRefId)
                .containsExactly(c.wholesalerB(), c.wholesalerA());
        assertThat(pinRows).extracting(StorefrontFeature::getSortOrder).containsExactly(0, 1);
    }

    @Test
    @DisplayName("主推 21 个 → 50711；置顶 6 个 → 50712")
    void overLimit_rejects50711_50712() {
        Ctx c = seed();
        List<Long> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(seedSku(c.tenantId(), c.wholesalerA(), true));
        }
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), many, List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_MAIN_SKU_LIMIT.getCode()));

        List<Long> sixWas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sixWas.add(seedWholesaler(c.tenantId(), c.taUserId()));
        }
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(), sixWas))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_PIN_WA_LIMIT.getCode()));
    }

    @Test
    @DisplayName("重复主推/重复置顶 → 50713")
    void duplicateIds_rejects50713() {
        Ctx c = seed();
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(c.skuA1(), c.skuA1()), List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_FEATURED_DUPLICATED.getCode()));

        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(), List.of(c.wholesalerA(), c.wholesalerA())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_FEATURED_DUPLICATED.getCode()));
    }

    @Test
    @DisplayName("引用无效：他店 SKU / 未上架 SKU / 他店批发商 → 50714")
    void invalidRef_rejects50714() {
        Ctx c = seed();
        // 他店在售 SKU 不属于本店
        long otherSku = seedOtherTenantSku();
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(otherSku), List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_REF_INVALID.getCode()));

        // 本店但未上架（listed=false）的 SKU
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(c.skuUnlistedA()), List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_REF_INVALID.getCode()));

        // 他店 ACTIVE 批发商不属于本店
        long otherWa = seedOtherTenantWholesaler();
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.taUserId(), List.of(), List.of(otherWa)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.STOREFRONT_REF_INVALID.getCode()));
    }

    @Test
    @DisplayName("覆盖写幂等：重复保存结果一致，行数不累积")
    void overwriteSave_idempotent() {
        Ctx c = seed();
        // 第一次保存
        storefrontFeatureService.saveMyFeatured(c.tenantId(), c.taUserId(),
                List.of(c.skuA1(), c.skuA2()), List.of(c.wholesalerB()));
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_MAIN_SKU)).hasSize(2);
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_PIN_WA)).hasSize(1);

        // 第二次覆盖：换主推（去掉一个）、置顶反序
        storefrontFeatureService.saveMyFeatured(c.tenantId(), c.taUserId(),
                List.of(c.skuA2()), List.of(c.wholesalerA(), c.wholesalerB()));
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_MAIN_SKU)).hasSize(1);
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_PIN_WA)).hasSize(2);

        StorefrontFeatureVo after = storefrontFeatureService.getMyFeatured(c.tenantId(), c.taUserId());
        assertThat(after.getMainSkuIds()).containsExactly(c.skuA2());
        assertThat(after.getPinWaIds()).containsExactly(c.wholesalerA(), c.wholesalerB());

        // 第三次保存与第二次完全相同 → 结果一致、行数不累积
        storefrontFeatureService.saveMyFeatured(c.tenantId(), c.taUserId(),
                List.of(c.skuA2()), List.of(c.wholesalerA(), c.wholesalerB()));
        StorefrontFeatureVo again = storefrontFeatureService.getMyFeatured(c.tenantId(), c.taUserId());
        assertThat(again.getMainSkuIds()).containsExactly(c.skuA2());
        assertThat(again.getPinWaIds()).containsExactly(c.wholesalerA(), c.wholesalerB());
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_MAIN_SKU)).hasSize(1);
        assertThat(rows(c.storeId(), StorefrontFeature.KIND_PIN_WA)).hasSize(2);
    }

    @Test
    @DisplayName("storefront 出参：featuredSkuIds/pinnedWholesalerIds + 主推/置顶前置 + featured/pinned 标记")
    void storefront_output_orderingAndFlags() {
        Ctx c = seed();
        // 给全部在售 SKU 种库存（下架 SKU 不种）
        seedStock(c.tenantId(), c.wholesalerA(), c.skuA1(), 100);
        seedStock(c.tenantId(), c.wholesalerA(), c.skuA2(), 100);
        seedStock(c.tenantId(), c.wholesalerB(), c.skuB1(), 100);
        seedStock(c.tenantId(), c.wholesalerB(), c.skuB2(), 100);

        // 主推 [skuB1, skuA1]，置顶 [wholesalerB]
        storefrontFeatureService.saveMyFeatured(c.tenantId(), c.taUserId(),
                List.of(c.skuB1(), c.skuA1()), List.of(c.wholesalerB()));

        StoreFrontVo page = storeFrontService.getStorePage(c.storeId(), null);
        assertThat(page.getFeaturedSkuIds()).as("出参主推序").containsExactly(c.skuB1(), c.skuA1());
        assertThat(page.getPinnedWholesalerIds()).as("出参置顶序").containsExactly(c.wholesalerB());

        // 批发商列表：置顶批发商 B 前置，未置顶 A 保持原序
        List<StoreWholesalerVo> was = page.getWholesalers();
        assertThat(was).extracting(StoreWholesalerVo::getWholesalerId)
                .containsExactly(c.wholesalerB(), c.wholesalerA());
        assertThat(was).satisfiesExactly(
                w -> assertThat(w.getPinned()).isTrue(),
                w -> assertThat(w.getPinned()).isFalse());

        // B 店 SKU：主推 skuB1 前置且 featured=true；skuB2 保持原序 featured=false
        StoreWholesalerVo waB = was.get(0);
        assertThat(waB.getSkus()).extracting(StoreSkuVo::getSkuId).containsExactly(c.skuB1(), c.skuB2());
        assertThat(waB.getSkus().get(0).getFeatured()).isTrue();
        assertThat(waB.getSkus().get(1).getFeatured()).isFalse();

        // A 店 SKU：主推 skuA1 前置且 featured=true；skuA2 保持原序 featured=false
        StoreWholesalerVo waA = was.get(1);
        assertThat(waA.getSkus()).extracting(StoreSkuVo::getSkuId).containsExactly(c.skuA1(), c.skuA2());
        assertThat(waA.getSkus().get(0).getFeatured()).isTrue();
        assertThat(waA.getSkus().get(1).getFeatured()).isFalse();
    }

    @Test
    @DisplayName("无权限：非 TA（WK）保存/回显 → 42101")
    void nonTaUser_rejected() {
        Ctx c = seed();
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(
                c.tenantId(), c.wkUserId(), List.of(c.skuA1()), List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PERMISSION_TENANT_001.getCode()));
        assertThatThrownBy(() -> storefrontFeatureService.getMyFeatured(c.tenantId(), c.wkUserId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PERMISSION_TENANT_001.getCode()));
    }

    @Test
    @DisplayName("非店铺上下文：TA 有租户但未建仓（无 store）→ 50210")
    void taWithoutStore_rejected() {
        long tenantId = snowflakeIdUtil.nextId();
        long taUserId = seedRole(null, "TA", tenantId, null);
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("无仓-" + tenantId);
        t.setContactUserId(taUserId);
        t.setContactPhoneCipher(piiCrypto.encrypt("1" + String.format("%010d", tenantId % 10_000_000_000L)));
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);

        assertThatThrownBy(() -> storefrontFeatureService.getMyFeatured(tenantId, taUserId))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.TENANT_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> storefrontFeatureService.saveMyFeatured(tenantId, taUserId, List.of(), List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.TENANT_NOT_FOUND.getCode()));
    }
}
