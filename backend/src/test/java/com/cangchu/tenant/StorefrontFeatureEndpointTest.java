package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.mapper.TenantMapper;
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
 * P5-A W4：店铺撮合配置端点（GET/PUT /api/v1/tenant/storefront/featured）HTTP 层验证。
 *
 * <p>复用 {@code StoreFrontScenarioTest} 基建（RANDOM_PORT + TestRestTemplate + H2 + mock 短信 888888）：
 * 注册 TA → apply 建仓（tenant+store）→ 建商户 → 上架 SKU → PUT/GET 撮合配置；
 * 并验证无店铺上下文（未建仓 TA / WK 无租户绑定）在 HTTP 层被拒。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorefrontFeatureEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TenantMapper tenantMapper;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseAccount;
    private String baseFeatured;

    @BeforeEach
    void setUp() {
        String root = "http://localhost:" + port + "/api/v1";
        baseTenant = root + "/tenant";
        baseWholesaler = root + "/tenant/wholesalers";
        baseSku = root + "/tenant/skus";
        baseAccount = root + "/account";
        baseFeatured = root + "/tenant/storefront/featured";
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

    private String registerAndLogin(String phone, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("TaPass123");
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    /** 一个完整的店：tenantId + storeId + TA token（仓已 ACTIVE）。 */
    private record StoreCtx(String phone, String token, Long tenantId, Long storeId) {}

    private StoreCtx registerStore() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("撮合仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> apply = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(apply).isNotNull();
        assertThat(apply.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(apply.getData().get("tenantId").toString());

        R<Map<String, Object>> mine = restTemplate.exchange(baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
        assertThat(mine).isNotNull();
        assertThat(mine.getCode()).as("tenant/me %s", phone).isEqualTo(0);
        Long storeId = Long.valueOf(mine.getData().get("storeId").toString());

        activateTenant(tenantId);
        return new StoreCtx(phone, token, tenantId, storeId);
    }

    private void activateTenant(Long tenantId) {
        com.cangchu.tenant.entity.Tenant t = tenantMapper.selectById(tenantId);
        t.setStatus("ACTIVE");
        tenantMapper.updateById(t);
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

    private R<Map<String, Object>> putFeatured(String token, List<Long> mainSkuIds, List<Long> pinWaIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("mainSkuIds", mainSkuIds);
        body.put("pinWaIds", pinWaIds);
        return restTemplate.exchange(baseFeatured, HttpMethod.PUT,
                new HttpEntity<>(body, bearer(token)), MAP).getBody();
    }

    private R<Map<String, Object>> getFeatured(String token) {
        return restTemplate.exchange(baseFeatured, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    @Test
    @DisplayName("PUT 保存 + GET 回显（TA 店铺上下文），响应 id 为字符串")
    void putThenGet_happyPath() {
        StoreCtx store = registerStore();
        String wid = createWholesaler(store, "商户-" + store.phone());
        String sku = createSku(store, wid, "在售-" + store.phone());
        addStock(store, wid, sku, 100);

        R<Map<String, Object>> put = putFeatured(store.token(),
                List.of(Long.valueOf(sku)), List.of(Long.valueOf(wid)));
        assertThat(put).isNotNull();
        assertThat(put.getCode()).as("PUT featured").isEqualTo(0);

        R<Map<String, Object>> get = getFeatured(store.token());
        assertThat(get).isNotNull();
        assertThat(get.getCode()).as("GET featured").isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> mainSkuIds = (List<String>) get.getData().get("mainSkuIds");
        @SuppressWarnings("unchecked")
        List<String> pinWaIds = (List<String>) get.getData().get("pinWaIds");
        assertThat(mainSkuIds).containsExactly(sku);
        assertThat(pinWaIds).containsExactly(wid);

        // 覆盖保存（清空置顶）→ 幂等回显
        R<Map<String, Object>> put2 = putFeatured(store.token(),
                List.of(Long.valueOf(sku)), List.of());
        assertThat(put2.getCode()).isZero();
        R<Map<String, Object>> get2 = getFeatured(store.token());
        @SuppressWarnings("unchecked")
        List<String> main2 = (List<String>) get2.getData().get("mainSkuIds");
        @SuppressWarnings("unchecked")
        List<String> pin2 = (List<String>) get2.getData().get("pinWaIds");
        assertThat(main2).containsExactly(sku);
        assertThat(pin2).isEmpty();
    }

    @Test
    @DisplayName("未建仓 TA（无店铺上下文）→ GET/PUT 50210")
    void taWithoutStore_rejected() {
        String token = registerAndLogin(uniquePhone(PHONE_PREFIX_TA), "TA");
        R<Map<String, Object>> get = getFeatured(token);
        assertThat(get).isNotNull();
        assertThat(get.getCode()).isEqualTo(50210);
        R<Map<String, Object>> put = putFeatured(token, List.of(), List.of());
        assertThat(put).isNotNull();
        assertThat(put.getCode()).isEqualTo(50210);
    }

    @Test
    @DisplayName("WK 无租户绑定（无店铺上下文）→ GET 50210")
    void wkWithoutTenant_rejected() {
        String token = registerAndLogin(uniquePhone(PHONE_PREFIX_TA), "WK");
        R<Map<String, Object>> get = getFeatured(token);
        assertThat(get).isNotNull();
        assertThat(get.getCode()).isEqualTo(50210);
    }
}
