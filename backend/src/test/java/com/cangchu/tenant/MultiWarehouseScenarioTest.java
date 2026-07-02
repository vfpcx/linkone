package com.cangchu.tenant;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老板多仓 场景测试（P2）：TA 登录后 createWarehouse + 多仓列表 + 多仓隔离 + 简码唯一。
 * 基建同 TenantScenarioTest：@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信 888888。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiWarehouseScenarioTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseAccount;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST = new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token, Long tenantId) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        if (tenantId != null) h.set("X-Tenant-Id", tenantId.toString());
        return h;
    }

    private String registerAndLogin(String phone, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("Pass1234");
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    /** createWarehouse → 返回 {tenantId, simpleCode, status} */
    private Map<String, Object> createWarehouse(String token, String name) {
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName(name);
        dto.setContactPhone("13900000000");
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/warehouses", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token, null)), MAP).getBody();
        assertThat(body).isNotNull();
        return body.getData() != null ? Map.of("code", body.getCode(), "data", body.getData())
                : Map.of("code", body.getCode());
    }

    // ==================== S1 正常：建仓 + 多仓列表 ====================

    @Test
    @DisplayName("MW-S1-01 TA 登录后连建2仓 → 名下仓库列表返回2个、tenantId 互异")
    void mwS1_01_createAndList() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Map<String, Object> w1 = createWarehouse(token, "仓A-" + SEQ.get());
        Map<String, Object> w2 = createWarehouse(token, "仓B-" + SEQ.get());
        assertThat(((Map<?, ?>) w1.get("data")).get("tenantId")).isNotNull();
        assertThat(((Map<?, ?>) w2.get("data")).get("tenantId"))
                .isNotEqualTo(((Map<?, ?>) w1.get("data")).get("tenantId"));

        R<List<Map<String, Object>>> list = restTemplate.exchange(baseTenant + "/warehouses",
                HttpMethod.GET, new HttpEntity<>(bearer(token, null)), LIST).getBody();
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).as("名下仓库数 ≥ 2").hasSizeGreaterThanOrEqualTo(2);
    }

    // ==================== S1 隔离：多仓数据互不串 ====================

    @Test
    @DisplayName("MW-S1-02 多仓隔离：仓A商户不出现在仓B的商户列表(X-Tenant-Id 切换)")
    void mwS1_02_isolation() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = Long.valueOf(((Map<?, ?>) createWarehouse(token, "隔离仓A-" + SEQ.get()).get("data")).get("tenantId").toString());
        Long tB = Long.valueOf(((Map<?, ?>) createWarehouse(token, "隔离仓B-" + SEQ.get()).get("data")).get("tenantId").toString());

        String whA = "商户A-" + SEQ.incrementAndGet();
        String whB = "商户B-" + SEQ.incrementAndGet();
        createWholesaler(token, tA, whA);
        createWholesaler(token, tB, whB);

        // 以 X-Tenant-Id=仓A 查商户列表 → 只含 whA，不含 whB
        R<List<Map<String, Object>>> listA = restTemplate.exchange(baseTenant + "/wholesalers",
                HttpMethod.GET, new HttpEntity<>(bearer(token, tA)), LIST).getBody();
        assertThat(listA).isNotNull();
        assertThat(listA.getCode()).isEqualTo(0);
        List<String> namesA = listA.getData().stream().map(m -> String.valueOf(m.get("name"))).collect(Collectors.toList());
        assertThat(namesA).contains(whA).doesNotContain(whB);
    }

    private void createWholesaler(String token, Long tenantId, String name) {
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName(name);
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/wholesalers", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token, tenantId)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("建商户 %s @tenant %s", name, tenantId).isEqualTo(0);
    }

    // ==================== S4 越权：非 TA 不可建仓 ====================

    @Test
    @DisplayName("MW-S4-01 非 TA(OPS) 调 createWarehouse → 拒绝(42001)")
    void mwS4_01_nonTaRejected() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        Map<String, Object> r = createWarehouse(opsToken, "越权建仓");
        assertThat(r.get("code")).isEqualTo(42001);
    }

    // ==================== S6 简码唯一 ====================

    @Test
    @DisplayName("MW-S6-01 连建5仓 → 简码全互异(F6 随机+唯一)")
    void mwS6_01_simpleCodeUnique() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> w = createWarehouse(token, "码仓" + i + "-" + SEQ.incrementAndGet());
            String code = String.valueOf(((Map<?, ?>) w.get("data")).get("simpleCode"));
            codes.add(code);
        }
        assertThat(codes).as("5 个仓简码应全互异").hasSize(5);
    }
}
