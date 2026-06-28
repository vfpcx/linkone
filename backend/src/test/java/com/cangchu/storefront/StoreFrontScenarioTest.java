package com.cangchu.storefront;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RT 店铺前台浏览场景测试（phase-1 B2）。
 *
 * <p>沿用 {@code SkuScenarioTest} 基建（@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信 888888）。
 * 数据准备链路：注册 TA → apply 建仓（生成 tenant+store+settings，store.tenantId 即解析依据）→ 建商户 →
 * 上架 SKU → 直接调 {@link InventoryService} 入库种库存。RT 端点公开免登录，故不带 token 调用。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 RT 用 storeId 进店 → 返回店内 ACTIVE 批发商 + 在售 SKU（含公开价+库存量）。</li>
 *   <li>S1 下架 SKU / 库存为 0 的 SKU 不出现在 RT 进店页。</li>
 *   <li>S2 不存在 storeId / 无参 → STORE_NOT_FOUND(50260) / 校验错误。</li>
 *   <li>跨店隔离：A 店 RT 看不到 B 店的商户与 SKU。</li>
 *   <li>/rt/wholesalers 与 /rt/skus 端点正确返回本店数据。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoreFrontScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InventoryService inventoryService;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseAccount;
    private String baseRt;

    @BeforeEach
    void setUp() {
        String root = "http://localhost:" + port + "/api/v1";
        baseTenant = root + "/tenant";
        baseWholesaler = root + "/tenant/wholesalers";
        baseSku = root + "/tenant/skus";
        baseAccount = root + "/account";
        baseRt = root + "/rt";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + String.format("%04d", n % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private String registerAndLogin(String phone, String password, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    /** 一个完整的店：tenantId + storeId + TA token。 */
    private record StoreCtx(String phone, String token, Long tenantId, Long storeId) {}

    private StoreCtx registerStore() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("批发仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> apply = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(apply).isNotNull();
        assertThat(apply.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(apply.getData().get("tenantId").toString());

        // 取 TA 自己的店铺 id（/tenant/me 返回 storeId）
        R<Map<String, Object>> mine = restTemplate.exchange(baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
        assertThat(mine).isNotNull();
        assertThat(mine.getCode()).as("tenant/me %s", phone).isEqualTo(0);
        Long storeId = Long.valueOf(mine.getData().get("storeId").toString());

        return new StoreCtx(phone, token, tenantId, storeId);
    }

    private String createWholesaler(StoreCtx ctx, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ctx.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler %s", name).isEqualTo(0);
        return body.getData().get("id").toString();
    }

    private Map<String, Object> validSku(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("spec", "500ml*24");
        m.put("unitPrice", 9.90);
        m.put("moqPrice", 8.50);
        m.put("moqQty", 10);
        return m;
    }

    private String createSku(StoreCtx ctx, String wholesalerId, String name) {
        R<Map<String, Object>> body = restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId,
                HttpMethod.POST, new HttpEntity<>(validSku(name), bearer(ctx.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create sku %s", name).isEqualTo(0);
        return body.getData().get("id").toString();
    }

    private void toggleListing(StoreCtx ctx, String skuId, boolean on) {
        R<Map<String, Object>> body = restTemplate.exchange(baseSku + "/" + skuId + "/listing?on=" + on,
                HttpMethod.PUT, new HttpEntity<>(bearer(ctx.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("toggle %s -> %s", skuId, on).isEqualTo(0);
    }

    /** 直接经 service 入库种库存（B1 不暴露公开加库存 HTTP）。 */
    private void addStock(StoreCtx ctx, String wholesalerId, String skuId, int qty) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(Long.valueOf(wholesalerId))
                .tenantId(ctx.tenantId())
                .skuId(Long.valueOf(skuId))
                .qty(qty)
                .refDocNo("IN-T")
                .operatorUserId(1L)
                .build());
    }

    private R<Map<String, Object>> rtStore(Long storeId) {
        return restTemplate.exchange(baseRt + "/store?storeId=" + storeId, HttpMethod.GET,
                HttpEntity.EMPTY, MAP).getBody();
    }

    private R<List<Map<String, Object>>> rtWholesalers(Long storeId) {
        return restTemplate.exchange(baseRt + "/wholesalers?storeId=" + storeId, HttpMethod.GET,
                HttpEntity.EMPTY, LIST).getBody();
    }

    private R<List<Map<String, Object>>> rtSkus(Long storeId, String wholesalerId) {
        return restTemplate.exchange(baseRt + "/skus?storeId=" + storeId + "&wholesalerId=" + wholesalerId,
                HttpMethod.GET, HttpEntity.EMPTY, LIST).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> wholesalersOf(R<Map<String, Object>> page) {
        return (List<Map<String, Object>>) page.getData().get("wholesalers");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> skusOf(Map<String, Object> wholesaler) {
        return (List<Map<String, Object>>) wholesaler.get("skus");
    }

    // ======================================================================
    // S1 正常进店：店内 WA + 在售 SKU（含价+库存）
    // ======================================================================

    @Test
    @DisplayName("SF-S1-01 RT 进店返回店内 WA + 在售 SKU（含公开价+库存量）")
    void s1_storePageHasOnSaleSkus() {
        StoreCtx store = registerStore();
        String wid = createWholesaler(store, "可乐批发商-" + store.phone());
        String onSku = createSku(store, wid, "在售可乐-" + store.phone());
        addStock(store, wid, onSku, 100);

        R<Map<String, Object>> page = rtStore(store.storeId());
        assertThat(page).isNotNull();
        assertThat(page.getCode()).isEqualTo(0);
        assertThat(page.getData().get("storeId").toString()).isEqualTo(store.storeId().toString());
        assertThat(page.getData().get("tenantId").toString()).isEqualTo(store.tenantId().toString());

        List<Map<String, Object>> was = wholesalersOf(page);
        assertThat(was).extracting(m -> m.get("wholesalerId").toString()).contains(wid);

        Map<String, Object> wa = was.stream()
                .filter(m -> wid.equals(m.get("wholesalerId").toString())).findFirst().orElseThrow();
        List<Map<String, Object>> skus = skusOf(wa);
        Map<String, Object> sku = skus.stream()
                .filter(s -> onSku.equals(s.get("skuId").toString())).findFirst().orElseThrow();
        assertThat(sku.get("unitPrice")).isNotNull();
        assertThat(sku.get("moqQty")).isNotNull();
        assertThat(((Number) sku.get("stockQty")).intValue()).isEqualTo(100);
    }

    // ======================================================================
    // S1 下架 / 库存0 的 SKU 不出现
    // ======================================================================

    @Test
    @DisplayName("SF-S1-02 下架 SKU 与 库存为0 的 SKU 不出现在 RT 进店页")
    void s1_hiddenSkusExcluded() {
        StoreCtx store = registerStore();
        String wid = createWholesaler(store, "混合商户-" + store.phone());

        String onSku = createSku(store, wid, "在售-" + store.phone());
        addStock(store, wid, onSku, 50);

        // 下架的 SKU：有库存但 listed=false
        String offSku = createSku(store, wid, "下架-" + store.phone());
        addStock(store, wid, offSku, 50);
        toggleListing(store, offSku, false);

        // 零库存的 SKU：listed=true 但从未入库（qty 不存在/0）
        String zeroSku = createSku(store, wid, "零库存-" + store.phone());

        R<List<Map<String, Object>>> skus = rtSkus(store.storeId(), wid);
        assertThat(skus).isNotNull();
        assertThat(skus.getCode()).isEqualTo(0);
        List<String> ids = skus.getData().stream().map(s -> s.get("skuId").toString()).toList();

        assertThat(ids).as("在售 SKU 应出现").contains(onSku);
        assertThat(ids).as("下架 SKU 不应出现").doesNotContain(offSku);
        assertThat(ids).as("零库存 SKU 不应出现").doesNotContain(zeroSku);
    }

    // ======================================================================
    // S2 不存在 / 缺参
    // ======================================================================

    @Test
    @DisplayName("SF-S2-01 不存在 storeId → STORE_NOT_FOUND(50260)")
    void s2_storeNotFound() {
        R<Map<String, Object>> page = rtStore(99999999999L);
        assertThat(page).isNotNull();
        assertThat(page.getCode()).isEqualTo(50260);
    }

    @Test
    @DisplayName("SF-S2-02 storeId 与 code 都不传 → 校验失败(40003)")
    void s2_missingParam() {
        R<Map<String, Object>> page = restTemplate.exchange(baseRt + "/store", HttpMethod.GET,
                HttpEntity.EMPTY, MAP).getBody();
        assertThat(page).isNotNull();
        assertThat(page.getCode()).isEqualTo(40003);
    }

    // ======================================================================
    // 跨店隔离：A 店 RT 看不到 B 店数据
    // ======================================================================

    @Test
    @DisplayName("SF-ISO-01 跨店隔离：A 店进店页不含 B 店商户/SKU")
    void iso_crossStoreIsolation() {
        StoreCtx a = registerStore();
        String widA = createWholesaler(a, "A家商户-" + a.phone());
        String skuA = createSku(a, widA, "A家在售-" + a.phone());
        addStock(a, widA, skuA, 30);

        StoreCtx b = registerStore();
        String widB = createWholesaler(b, "B家商户-" + b.phone());
        String skuB = createSku(b, widB, "B家在售-" + b.phone());
        addStock(b, widB, skuB, 30);

        // A 店进店页只含 A 的商户/SKU
        R<Map<String, Object>> pageA = rtStore(a.storeId());
        assertThat(pageA.getCode()).isEqualTo(0);
        List<Map<String, Object>> wasA = wholesalersOf(pageA);
        List<String> waIdsA = wasA.stream().map(m -> m.get("wholesalerId").toString()).toList();
        assertThat(waIdsA).contains(widA).doesNotContain(widB);
        List<String> skuIdsA = wasA.stream().flatMap(w -> skusOf(w).stream())
                .map(s -> s.get("skuId").toString()).toList();
        assertThat(skuIdsA).contains(skuA).doesNotContain(skuB);

        // A 店尝试用 B 的 wholesalerId 拉 SKU → 不属本店，返回空
        R<List<Map<String, Object>>> crossSkus = rtSkus(a.storeId(), widB);
        assertThat(crossSkus.getCode()).isEqualTo(0);
        assertThat(crossSkus.getData()).as("A 店不能拉到 B 店商户 SKU").isEmpty();
    }

    // ======================================================================
    // /rt/wholesalers 端点
    // ======================================================================

    @Test
    @DisplayName("SF-S1-03 /rt/wholesalers 返回店内 ACTIVE 批发商")
    void s1_wholesalersEndpoint() {
        StoreCtx store = registerStore();
        String wid = createWholesaler(store, "唯一商户-" + store.phone());

        R<List<Map<String, Object>>> was = rtWholesalers(store.storeId());
        assertThat(was).isNotNull();
        assertThat(was.getCode()).isEqualTo(0);
        assertThat(was.getData()).extracting(m -> m.get("wholesalerId").toString()).contains(wid);
        assertThat(was.getData()).allMatch(m -> "ACTIVE".equals(m.get("status")));
    }
}
