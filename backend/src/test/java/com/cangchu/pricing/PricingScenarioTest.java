package com.cangchu.pricing;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.response.R;
import com.cangchu.pricing.dto.SetCustomerPriceDto;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * 定价模块场景测试（P2 定价 Wave 1，HTTP 黑盒，Style A）。
 *
 * <p>沿用 {@code SkuScenarioTest} 基建（@SpringBootTest RANDOM_PORT + TestRestTemplate
 * + H2 + mock 短信码 888888 + bare-token Authorization）。鉴权口径：TA（同租户）可代商户设专属价。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 TA 设专属价 → 列表可见 → PATCH 改价 → DELETE 作废。</li>
 *   <li>S2 非法价（<=0）与缺 phone → 校验拒绝；服务层 validatePrice → 50301。</li>
 *   <li>S4 跨租户 TA 不能对他人商户设/列专属价 → 42101/50230。</li>
 *   <li>resolvePrice 优先级：有 phone→专属价；空 phone→公开价；过期专属→公开价。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PricingService pricingService;

    private static final String PHONE_PREFIX_TA =
            "14" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseCustomerPrice;
    private String baseAccount;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseWholesaler = "http://localhost:" + port + "/api/v1/tenant/wholesalers";
        baseSku = "http://localhost:" + port + "/api/v1/tenant/skus";
        baseCustomerPrice = "http://localhost:" + port + "/api/v1/tenant/customer-prices";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID = new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + String.format("%04d", n % 10000);
    }

    private String rtPhone() {
        return "17" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private LoginVo registerAndLogin(String phone, String password, String role) {
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
        return body.getData();
    }

    private record TaContext(String phone, String token, Long tenantId, Long userId) {}

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        LoginVo login = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("定价仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(login.getToken())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, login.getToken(), tenantId, login.getUserId());
    }

    private String createWholesaler(TaContext ta, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    /** 建一个 SKU（unitPrice=10.00, moqPrice=8.00, moqQty=5），返回 skuId。 */
    private String createSku(TaContext ta, String wholesalerId, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("spec", "500ml*24");
        m.put("unitPrice", 10.00);
        m.put("moqPrice", 8.00);
        m.put("moqQty", 5);
        R<Map<String, Object>> body = restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId,
                HttpMethod.POST, new HttpEntity<>(m, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create sku").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    private R<Map<String, Object>> setPrice(String token, Map<String, Object> dto) {
        return restTemplate.exchange(baseCustomerPrice, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    private Map<String, Object> priceDto(String wholesalerId, String skuId, String phone, Object unitPrice) {
        Map<String, Object> m = new HashMap<>();
        m.put("wholesalerId", wholesalerId);
        m.put("skuId", skuId);
        m.put("rtPhone", phone);
        m.put("unitPrice", unitPrice);
        return m;
    }

    private R<List<Map<String, Object>>> listPrices(String token, String wholesalerId) {
        return restTemplate.exchange(baseCustomerPrice + "?wholesalerId=" + wholesalerId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    // ======================================================================
    // S1 正常流程：set → list → update(PATCH) → revoke(DELETE)
    // ======================================================================

    @Test
    @DisplayName("PRICE-S1-01 TA 设专属价 → 列表可见 → 改价 → 作废")
    void s1_crud() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "自营商户-" + ta.phone());
        String skuId = createSku(ta, wid, "可乐-" + ta.phone());
        String phone = rtPhone();

        // set
        R<Map<String, Object>> set = setPrice(ta.token(), priceDto(wid, skuId, phone, 6.60));
        assertThat(set).isNotNull();
        assertThat(set.getCode()).isEqualTo(0);
        assertThat(set.getData().get("status")).isEqualTo("ACTIVE");
        assertThat(set.getData().get("rtPhone")).isEqualTo(phone);
        assertThat(new BigDecimal(set.getData().get("unitPrice").toString())).isEqualByComparingTo("6.60");
        String priceId = set.getData().get("id").toString();

        // list
        R<List<Map<String, Object>>> list = listPrices(ta.token(), wid);
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).extracting(m -> m.get("id").toString()).contains(priceId);

        // update (PATCH) — 改价
        Map<String, Object> upd = new HashMap<>();
        upd.put("unitPrice", 5.50);
        R<Map<String, Object>> patched = restTemplate.exchange(baseCustomerPrice + "/" + priceId,
                HttpMethod.PATCH, new HttpEntity<>(upd, bearer(ta.token())), MAP).getBody();
        assertThat(patched).isNotNull();
        assertThat(patched.getCode()).as("PATCH 改价").isEqualTo(0);
        assertThat(new BigDecimal(patched.getData().get("unitPrice").toString())).isEqualByComparingTo("5.50");

        // revoke (DELETE) — 作废置 DISABLED
        R<Void> del = restTemplate.exchange(baseCustomerPrice + "/" + priceId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(ta.token())), VOID).getBody();
        assertThat(del).isNotNull();
        assertThat(del.getCode()).as("DELETE 作废").isEqualTo(0);

        R<List<Map<String, Object>>> after = listPrices(ta.token(), wid);
        Map<String, Object> row = after.getData().stream()
                .filter(m -> priceId.equals(m.get("id").toString()))
                .findFirst().orElseThrow();
        assertThat(row.get("status")).as("作废后状态 DISABLED").isEqualTo("DISABLED");
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("PRICE-S2-01 专属价<=0 → 校验拒绝（40001/50301）")
    void s2_invalidPrice() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "价校商户-" + ta.phone());
        String skuId = createSku(ta, wid, "校验品-" + ta.phone());

        R<Map<String, Object>> body = setPrice(ta.token(), priceDto(wid, skuId, rtPhone(), 0));
        assertThat(body).isNotNull();
        // DTO @DecimalMin 先兜底(40001)，服务层 validatePrice 为后备(50301)
        assertThat(body.getCode()).as("专属价<=0 应被拒").isIn(40001, 50301);
    }

    @Test
    @DisplayName("PRICE-S2-02 缺 rtPhone → 40001 校验失败")
    void s2_missingPhone() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "缺号商户-" + ta.phone());
        String skuId = createSku(ta, wid, "缺号品-" + ta.phone());

        Map<String, Object> dto = priceDto(wid, skuId, null, 6.60);
        dto.remove("rtPhone");
        R<Map<String, Object>> body = setPrice(ta.token(), dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("PRICE-S2-03 服务层 validatePrice：unitPrice=0 → 50301")
    void s2_serviceValidatePrice() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "服务校验商户-" + ta.phone());
        String skuId = createSku(ta, wid, "服务校验品-" + ta.phone());

        SetCustomerPriceDto dto = new SetCustomerPriceDto();
        dto.setWholesalerId(Long.valueOf(wid));
        dto.setSkuId(Long.valueOf(skuId));
        dto.setRtPhone(rtPhone());
        dto.setUnitPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> pricingService.setCustomerPrice(dto, ta.userId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(50301));
    }

    // ======================================================================
    // S4 越权 / 跨租户
    // ======================================================================

    @Test
    @DisplayName("PRICE-S4-01 跨租户 TA 对他人商户设/列专属价 → 拒绝（42101/50230）")
    void s4_crossTenant() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();
        String widA = createWholesaler(a, "A家商户-" + a.phone());
        String skuA = createSku(a, widA, "A家品-" + a.phone());

        // B 的 TA 尝试为 A 的商户设专属价
        R<Map<String, Object>> setBody = setPrice(b.token(), priceDto(widA, skuA, rtPhone(), 6.60));
        assertThat(setBody).isNotNull();
        assertThat(setBody.getCode())
                .as("跨租户为他人商户设专属价应被拒（42101/50230）")
                .isIn(42101, 50230);

        // B 的 TA 尝试列 A 商户的专属价
        R<List<Map<String, Object>>> listBody = listPrices(b.token(), widA);
        assertThat(listBody).isNotNull();
        assertThat(listBody.getCode())
                .as("跨租户列他人商户专属价应被拒（42101/50230）")
                .isIn(42101, 50230);
    }

    // ======================================================================
    // resolvePrice 优先级（Style B：@Autowired service 直调内部方法）
    // ======================================================================

    @Test
    @DisplayName("PRICE-S5-01 resolvePrice 优先级：专属价 / 公开价（起批） / 过期回退公开价")
    void s5_resolvePriority() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "解析商户-" + ta.phone());
        String skuId = createSku(ta, wid, "解析品-" + ta.phone());
        Long widL = Long.valueOf(wid);
        Long skuL = Long.valueOf(skuId);

        // 空 phone → 公开价：qty<5 用单价 10.00，qty>=5 用起批价 8.00
        assertThat(pricingService.resolvePrice(widL, skuL, null, 1)).isEqualByComparingTo("10.00");
        assertThat(pricingService.resolvePrice(widL, skuL, "  ", 5)).isEqualByComparingTo("8.00");

        // 有 phone + ACTIVE 专属价 → 专属价（忽略 qty/起批）
        String custPhone = rtPhone();
        assertThat(setPrice(ta.token(), priceDto(wid, skuId, custPhone, 6.00)).getCode()).isEqualTo(0);
        assertThat(pricingService.resolvePrice(widL, skuL, custPhone, 1)).isEqualByComparingTo("6.00");
        assertThat(pricingService.resolvePrice(widL, skuL, custPhone, 100)).isEqualByComparingTo("6.00");

        // 有 phone 但专属价已过期 → 回退公开价
        String expPhone = rtPhone();
        Map<String, Object> expired = priceDto(wid, skuId, expPhone, 3.00);
        expired.put("expireAt", "2020-01-01T00:00:00");
        assertThat(setPrice(ta.token(), expired).getCode()).isEqualTo(0);
        assertThat(pricingService.resolvePrice(widL, skuL, expPhone, 1))
                .as("过期专属价回退公开价").isEqualByComparingTo("10.00");
    }
}
