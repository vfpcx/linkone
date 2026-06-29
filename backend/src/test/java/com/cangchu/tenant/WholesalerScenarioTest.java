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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wholesaler 模块场景测试（phase-1 A1）。
 *
 * <p>测试基建：沿用 {@link TenantScenarioTest} 风格
 * （@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信码 888888）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 TA 自营创建成功 → code 0、status=ACTIVE、source=SELF_OPERATED。</li>
 *   <li>S2 缺 name → 40001 校验失败。</li>
 *   <li>S4 非 TA / 跨租户创建 → 拒绝（42101 PERMISSION_TENANT_001）。</li>
 *   <li>S6 同租户同名重复 → 拒绝（50231 WHOLESALER_NAME_DUPLICATED）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WholesalerScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseWholesaler;
    private String baseAccount;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseWholesaler = "http://localhost:" + port + "/api/v1/tenant/wholesalers";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> WS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> WS_LIST =
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

    /** 注册 TA + apply 建仓（user_roles 绑定 tenantId，供 TenantContext 推导）。 */
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

    private R<Map<String, Object>> createWholesaler(String token, WholesalerCreateDto dto) {
        return restTemplate.exchange(baseWholesaler, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), WS).getBody();
    }

    // ======================================================================
    // S1 正常流程
    // ======================================================================

    @Test
    @DisplayName("WS-S1-01 TA 自营创建商户成功 → ACTIVE / SELF_OPERATED")
    void s1_createSuccess() {
        TaContext ta = registerTaWithTenant();
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("自营批发商-" + ta.phone());
        dto.setIntro("主营日用百货");

        R<Map<String, Object>> body = createWholesaler(ta.token(), dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("status")).isEqualTo("ACTIVE");
        assertThat(body.getData().get("source")).isEqualTo("SELF_OPERATED");
        assertThat(body.getData().get("tenantId").toString()).isEqualTo(ta.tenantId().toString());

        // 列表应能查到刚建的商户
        R<List<Map<String, Object>>> list = restTemplate.exchange(baseWholesaler, HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), WS_LIST).getBody();
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        assertThat(list.getData()).extracting(m -> m.get("name"))
                .contains("自营批发商-" + ta.phone());
    }

    @Test
    @DisplayName("WS-S1-02 创建商户并开通 WA 账号 → 返回 waUserId")
    void s1_createWithWaAccount() {
        TaContext ta = registerTaWithTenant();
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("带WA批发商-" + ta.phone());
        dto.setWaPhone(uniquePhone("17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000)));

        R<Map<String, Object>> body = createWholesaler(ta.token(), dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("waUserId")).as("应返回 WA 角色绑定 id").isNotNull();
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("WS-S2-01 缺 name → 40001 校验失败")
    void s2_missingName() {
        TaContext ta = registerTaWithTenant();
        WholesalerCreateDto dto = new WholesalerCreateDto();   // name 为 null → @NotBlank
        dto.setIntro("没有名字");

        R<Map<String, Object>> body = createWholesaler(ta.token(), dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    // ======================================================================
    // S4 越权与跨租户
    // ======================================================================

    @Test
    @DisplayName("WS-S4-01 非 TA（无租户绑定）创建 → 拒绝")
    void s4_nonTaCreate() {
        // WK 角色用户：注册即有角色但无 TA 租户绑定 → TenantContext 无可信租户 / requireTaRole 失败
        String wkToken = registerAndLogin(uniquePhone(PHONE_PREFIX_TA), "WkPass123", "WK");
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("越权创建-" + System.nanoTime());

        R<Map<String, Object>> body = createWholesaler(wkToken, dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode())
                .as("非 TA / 无租户绑定创建应被拒绝")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("WS-S4-02 跨租户改资料（A 的 TA 改 B 的商户）→ 拒绝（隔离：不可见即 50230，越权 42101）")
    void s4_crossTenantUpdate() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();

        // B 创建一个商户
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("B家商户-" + b.phone());
        R<Map<String, Object>> created = createWholesaler(b.token(), dto);
        assertThat(created.getCode()).isEqualTo(0);
        String wholesalerId = created.getData().get("id").toString();

        // A 的 TA 尝试改 B 的商户资料
        Map<String, String> upd = Map.of("intro", "恶意修改");
        R<Map<String, Object>> body = restTemplate.exchange(baseWholesaler + "/" + wholesalerId,
                HttpMethod.PUT, new HttpEntity<>(upd, bearer(a.token())), WS).getBody();
        assertThat(body).isNotNull();
        // TenantLine 兜底注入 tenant_id=A 条件 ⇒ B 的商户对 A 不可见，selectById 返回 null → 50230；
        // 若改为先查后比对租户则为 42101。两者都是「拒绝且未改动」的安全结果。
        assertThat(body.getCode())
                .as("跨租户改他人商户应被拒（不可见 50230 / 越权 42101）")
                .isIn(50230, 42101);
    }

    // ======================================================================
    // S6 唯一性
    // ======================================================================

    @Test
    @DisplayName("WS-S6-01 同租户同名重复创建 → 50231 拒绝")
    void s6_duplicateName() {
        TaContext ta = registerTaWithTenant();
        String dupName = "唯一商户名-" + ta.phone();

        WholesalerCreateDto dto1 = new WholesalerCreateDto();
        dto1.setName(dupName);
        assertThat(createWholesaler(ta.token(), dto1).getCode()).isEqualTo(0);

        WholesalerCreateDto dto2 = new WholesalerCreateDto();
        dto2.setName(dupName);
        R<Map<String, Object>> second = createWholesaler(ta.token(), dto2);
        assertThat(second).isNotNull();
        assertThat(second.getCode())
                .as("同租户同名重复应被唯一约束拒绝")
                .isEqualTo(50231);
    }
}
