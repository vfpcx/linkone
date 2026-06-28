package com.cangchu.product;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
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
 * SKU 模块场景测试（phase-1 A2）。
 *
 * <p>沿用 {@code WholesalerScenarioTest} 基建（@SpringBootTest RANDOM_PORT + TestRestTemplate
 * + H2 + mock 短信码 888888）。鉴权口径：TA（同租户）可代商户创建/管理 SKU。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 TA 创建 SKU 成功 + 商户列表可见（含下架）。</li>
 *   <li>S1 listByTenantForRt（/listed）只返回上架 SKU。</li>
 *   <li>S2 价格非法（unit_price<=0）/缺 name → 40x 拒绝。</li>
 *   <li>S4 跨租户 TA 为他人商户创建 SKU → 拒绝。</li>
 *   <li>toggleListing 下架后 /listed 不再返回、商户列表仍可见。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseSku;
    private String baseAccount;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseWholesaler = "http://localhost:" + port + "/api/v1/tenant/wholesalers";
        baseSku = "http://localhost:" + port + "/api/v1/tenant/skus";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
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

    private record TaContext(String phone, String token, Long tenantId) {}

    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("批发仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, token, tenantId);
    }

    /** TA 建一个商户，返回 wholesalerId。 */
    private String createWholesaler(TaContext ta, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("create wholesaler").isEqualTo(0);
        return body.getData().get("id").toString();
    }

    private R<Map<String, Object>> createSku(String token, String wholesalerId, Map<String, Object> dto) {
        return restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
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

    private R<List<Map<String, Object>>> listByWholesaler(String token, String wholesalerId) {
        return restTemplate.exchange(baseSku + "?wholesalerId=" + wholesalerId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    private R<List<Map<String, Object>>> listed(String token, String wholesalerId) {
        return restTemplate.exchange(baseSku + "/listed?wholesalerId=" + wholesalerId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), LIST).getBody();
    }

    // ======================================================================
    // S1 正常流程
    // ======================================================================

    @Test
    @DisplayName("SKU-S1-01 TA 创建 SKU 成功 + 商户列表可见")
    void s1_createSuccess() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "自营商户-" + ta.phone());

        R<Map<String, Object>> body = createSku(ta.token(), wid, validSku("可乐-" + ta.phone()));
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("listed")).isEqualTo(true);
        assertThat(body.getData().get("wholesalerId").toString()).isEqualTo(wid);

        R<List<Map<String, Object>>> list = listByWholesaler(ta.token(), wid);
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).extracting(m -> m.get("name")).contains("可乐-" + ta.phone());
    }

    @Test
    @DisplayName("SKU-S1-02 listByTenantForRt(/listed) 只返回上架")
    void s1_listedOnlyReturnsListed() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "在售商户-" + ta.phone());

        // 一个上架、一个随后下架
        R<Map<String, Object>> onSku = createSku(ta.token(), wid, validSku("在售品-" + ta.phone()));
        R<Map<String, Object>> offSku = createSku(ta.token(), wid, validSku("下架品-" + ta.phone()));
        assertThat(onSku.getCode()).isEqualTo(0);
        assertThat(offSku.getCode()).isEqualTo(0);
        String offId = offSku.getData().get("id").toString();

        // 下架第二个
        R<Map<String, Object>> toggled = restTemplate.exchange(baseSku + "/" + offId + "/listing?on=false",
                HttpMethod.PUT, new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(toggled).isNotNull();
        assertThat(toggled.getCode()).isEqualTo(0);
        assertThat(toggled.getData().get("listed")).isEqualTo(false);

        // /listed 只见上架；商户列表仍见两者
        R<List<Map<String, Object>>> rt = listed(ta.token(), wid);
        assertThat(rt.getCode()).isEqualTo(0);
        assertThat(rt.getData()).extracting(m -> m.get("name")).contains("在售品-" + ta.phone());
        assertThat(rt.getData()).extracting(m -> m.get("name")).doesNotContain("下架品-" + ta.phone());

        R<List<Map<String, Object>>> all = listByWholesaler(ta.token(), wid);
        assertThat(all.getData()).extracting(m -> m.get("name"))
                .contains("在售品-" + ta.phone(), "下架品-" + ta.phone());
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("SKU-S2-01 单价<=0 → 拒绝（40x）")
    void s2_invalidPrice() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "价格校验商户-" + ta.phone());
        Map<String, Object> dto = validSku("非法价-" + ta.phone());
        dto.put("unitPrice", 0);

        R<Map<String, Object>> body = createSku(ta.token(), wid, dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("单价<=0 应被拒").isNotEqualTo(0);
    }

    @Test
    @DisplayName("SKU-S2-02 缺 name → 40001 校验失败")
    void s2_missingName() {
        TaContext ta = registerTaWithTenant();
        String wid = createWholesaler(ta, "缺名商户-" + ta.phone());
        Map<String, Object> dto = validSku("x");
        dto.remove("name");

        R<Map<String, Object>> body = createSku(ta.token(), wid, dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    // ======================================================================
    // S4 越权 / 跨商户
    // ======================================================================

    @Test
    @DisplayName("SKU-S4-01 跨租户 TA 为他人商户创建 SKU → 拒绝")
    void s4_crossTenantCreate() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();
        String widA = createWholesaler(a, "A家商户-" + a.phone());

        // B 的 TA 尝试为 A 的商户创建 SKU
        R<Map<String, Object>> body = createSku(b.token(), widA, validSku("越权品-" + b.phone()));
        assertThat(body).isNotNull();
        // TenantLine 注入 B 的 tenant 条件 ⇒ A 商户对 B 不可见(50230)，或归属鉴权失败(42101)
        assertThat(body.getCode())
                .as("跨租户为他人商户建 SKU 应被拒（50230 / 42101）")
                .isIn(50230, 42101);
    }
}
