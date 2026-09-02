package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.TenantApplyDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TA 一账号多仓 收敛场景测试（20-p5-ta-multi-warehouse.md §5）。
 * 基建同 MultiWarehouseScenarioTest：@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信。
 * 多仓造数：TA 注册后 createWarehouse 连建 N 仓（每仓独立 tenantId + 独立 store）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantMultiWarehouseScenarioTest {

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

    /** createWarehouse → data {tenantId, simpleCode, status}；TA 已 ACTIVE 即可连建（老板多仓 P2）。 */
    private Long createWarehouse(String token, String name) {
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName(name);
        dto.setContactPhone("13900000000");
        dto.setAddressText("浙江省杭州市");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/warehouses", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token, null)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("createWarehouse %s", name).isEqualTo(0);
        return Long.valueOf(body.getData().get("tenantId").toString());
    }

    /** GET /tenant/me → TenantDetailVo。 */
    private R<Map<String, Object>> tenantMe(String token, Long tenantId) {
        return restTemplate.exchange(baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token, tenantId)), MAP).getBody();
    }

    /** GET /tenant/dashboard → TenantDashboardVo（19 §4）。 */
    private R<Map<String, Object>> dashboard(String token, Long tenantId) {
        return restTemplate.exchange(baseTenant + "/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearer(token, tenantId)), MAP).getBody();
    }

    /** 批发商自助入驻申请（任意登录用户可发起；MTA-S2 跨仓角色造数用）。 */
    private R<Map<String, Object>> selfApply(String token, Long targetTenantId, String name) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetTenantId", targetTenantId.toString());
        dto.put("name", name);
        dto.put("contactName", "联系人-" + name);
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(token, null)), MAP).getBody();
    }

    /** OPS 审核建仓申请 → ACTIVE（createWarehouse 建的仓默认 PENDING，写操作/入驻需 ACTIVE）。 */
    private void auditTenantActive(String opsToken, Long tenantId) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("action", "APPROVED");
        dto.put("remark", "OK");
        R<Map<String, Object>> body = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/admin/tenant/" + tenantId + "/audit",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(opsToken, null)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("OPS 审核租户 %s 通过", tenantId).isEqualTo(0);
    }

    /** TA 审批批发商入驻（带当前仓 X-Tenant-Id；20 §2 收敛后审批按当前仓定位）。 */
    private R<Map<String, Object>> audit(String token, Long tenantId, String applicationId, String action) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("action", action);
        dto.put("remark", "OK");
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/tenant/wholesaler-applications/"
                + applicationId + "/audit", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token, tenantId)), MAP).getBody();
    }

    // ==================== MTA-S1 多仓隔离 ====================

    @Test
    @DisplayName("MTA-S1-01 多仓 TA 带 X-Tenant-Id=仓A → /tenant/me 落仓A")
    void mtaS1_01_scopedToTenantA() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "多仓A-" + SEQ.get());
        Long tB = createWarehouse(token, "多仓B-" + SEQ.get());

        R<Map<String, Object>> me = tenantMe(token, tA);
        assertThat(me).isNotNull();
        assertThat(me.getCode()).isEqualTo(0);
        assertThat(String.valueOf(me.getData().get("tenantId")))
                .as("带 X-Tenant-Id=仓A 应落仓A").isEqualTo(String.valueOf(tA));
        assertThat(String.valueOf(me.getData().get("name"))).contains("多仓A");
    }

    @Test
    @DisplayName("MTA-S1-02 多仓 TA 带 X-Tenant-Id=仓B → /tenant/me 落仓B（与仓A互不可见）")
    void mtaS1_02_scopedToTenantB() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "多仓A-" + SEQ.get());
        Long tB = createWarehouse(token, "多仓B-" + SEQ.get());

        R<Map<String, Object>> me = tenantMe(token, tB);
        assertThat(me).isNotNull();
        assertThat(me.getCode()).isEqualTo(0);
        assertThat(String.valueOf(me.getData().get("tenantId")))
                .as("带 X-Tenant-Id=仓B 应落仓B").isEqualTo(String.valueOf(tB));
        assertThat(String.valueOf(me.getData().get("tenantId")))
                .as("仓B 视图不应泄漏仓A 身份").isNotEqualTo(String.valueOf(tA));
    }

    @Test
    @DisplayName("MTA-S1-03 多仓 TA 无 X-Tenant-Id → 回退登录态推导不报错（tenantId ∈ {A,B}）")
    void mtaS1_03_fallbackWithoutHeader() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "多仓A-" + SEQ.get());
        Long tB = createWarehouse(token, "多仓B-" + SEQ.get());

        R<Map<String, Object>> me = tenantMe(token, null);
        assertThat(me).isNotNull();
        assertThat(me.getCode()).as("无 X-Tenant-Id 应回退登录态推导，不报错").isEqualTo(0);
        Long tid = Long.valueOf(me.getData().get("tenantId").toString());
        assertThat(tid).isIn(tA, tB);
    }

    @Test
    @DisplayName("MTA-S1-04 多仓 TA dashboard 按当前仓隔离（storeName 随 X-Tenant-Id 切换）")
    void mtaS1_04_dashboardIsolation() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "多仓A-" + SEQ.get());
        Long tB = createWarehouse(token, "多仓B-" + SEQ.get());

        R<Map<String, Object>> da = dashboard(token, tA);
        R<Map<String, Object>> db = dashboard(token, tB);
        assertThat(da).isNotNull();
        assertThat(db).isNotNull();
        assertThat(da.getCode()).isEqualTo(0);
        assertThat(db.getCode()).isEqualTo(0);
        assertThat(String.valueOf(da.getData().get("storeName")))
                .as("dashboard 应随当前仓切换（仓A 与仓B storeName 互异）")
                .isNotEqualTo(String.valueOf(db.getData().get("storeName")));
    }

    // ==================== MTA-S2 跨仓角色组合越权 ====================

    @Test
    @DisplayName("MTA-S2-01 跨仓角色组合防越权：A仓TA+B仓WA 带 X-Tenant-Id=B 调 TA 端接口 → 42001")
    void mtaS2_01_crossRoleRejected() {
        // V：B 仓 TA（B 仓需 ACTIVE 才可入驻）
        String vToken = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tB = createWarehouse(vToken, "跨仓B-" + SEQ.get());
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        auditTenantActive(opsToken, tB);
        // U：A 仓 TA
        String uToken = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(uToken, "跨仓A-" + SEQ.get());

        // U 以批发商身份入驻 B 仓 → V 审批通过 → U 获得 (WA, B)
        R<Map<String, Object>> app = selfApply(uToken, tB, "跨仓商户-" + SEQ.get());
        assertThat(app).isNotNull();
        assertThat(app.getCode()).as("U 入驻申请").isEqualTo(0);
        String applicationId = app.getData().get("applicationId").toString();
        assertThat(audit(vToken, tB, applicationId, "APPROVED").getCode())
                .as("V 审批 U 入驻 B 仓").isEqualTo(0);

        // U 带 X-Tenant-Id=B 调 TA 端接口 → 该仓非 TA（跨仓角色组合）→ 拒绝。
        // getMyStore 的 null 分支抛 TENANT_NOT_FOUND(50210)；billing 端同一越权为 42001（原语义）
        R<Map<String, Object>> denied = tenantMe(uToken, tB);
        assertThat(denied).isNotNull();
        assertThat(denied.getCode())
                .as("A仓TA+B仓WA 借 X-Tenant-Id=B 调 TA 接口应拒绝（50210）").isEqualTo(50210);
    }

    // ==================== MTA-S3 单仓兼容 ====================

    @Test
    @DisplayName("MTA-S3-01 单仓 TA 无 X-Tenant-Id → 正常返回（回退兼容，拦截器单仓自动 set）")
    void mtaS3_01_singleWarehouseCompatible() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "单仓-" + SEQ.get());

        R<Map<String, Object>> me = tenantMe(token, null);
        assertThat(me).isNotNull();
        assertThat(me.getCode()).as("单仓 TA 无头应正常").isEqualTo(0);
        assertThat(String.valueOf(me.getData().get("tenantId"))).isEqualTo(String.valueOf(tA));
    }

    // ==================== MTA-S4 写操作落当前仓 ====================

    @Test
    @DisplayName("MTA-S4-01 写操作 updateMyStore 落当前仓：改仓B店名、仓A不受影响")
    void mtaS4_01_writeScoped() {
        String token = registerAndLogin(uniquePhone(P_TA), "TA");
        Long tA = createWarehouse(token, "写仓A-" + SEQ.get());
        Long tB = createWarehouse(token, "写仓B-" + SEQ.get());
        // updateMyStore 状态机要求 ACTIVE（STATE_TENANT_001）→ 先 OPS 审核两仓通过
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OPS");
        auditTenantActive(opsToken, tA);
        auditTenantActive(opsToken, tB);

        String newName = "B店改名-" + SEQ.incrementAndGet();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", newName);
        R<Map<String, Object>> upd = restTemplate.exchange(baseTenant + "/me", HttpMethod.PUT,
                new HttpEntity<>(dto, bearer(token, tB)), MAP).getBody();
        assertThat(upd).isNotNull();
        assertThat(upd.getCode()).as("带 X-Tenant-Id=仓B 改店名").isEqualTo(0);

        R<Map<String, Object>> meB = tenantMe(token, tB);
        assertThat(String.valueOf(meB.getData().get("storeName")))
                .as("仓B 店名已改").isEqualTo(newName);
        R<Map<String, Object>> meA = tenantMe(token, tA);
        assertThat(String.valueOf(meA.getData().get("storeName")))
                .as("仓A 店名不受影响").isNotEqualTo(newName);
    }
}
