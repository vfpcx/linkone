package com.cangchu.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 入驻 审查修复批次场景测试（F1/F4/F5 + SEC-S4-01 无 token 全扫）。
 *
 * <ul>
 *   <li>CON-S7-01（F1）：同一账号双线程并发提交入驻申请——uk_applicant_pending 部分唯一索引
 *       兜底（V13），恰一方成功、另一方 50201，库中 PENDING 恰一条。</li>
 *   <li>F4：TA 审批通过前账号已有 ACTIVE 入驻绑定 → 50204 复查拒绝（事务回滚 CAS）；
 *       OPS 代建成功自动关闭该账号存量 PENDING 申请（REJECTED + remark 留痕）。</li>
 *   <li>F5：目标租户非 ACTIVE（PENDING/FROZEN/REJECTED）→ 50101/50102/50103，
 *       自助申请与 OPS 代建同检。</li>
 *   <li>SEC-S4-01：入驻/退驻/员工端点无 token 一律 41001（SaInterceptor 覆盖面全扫）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingReviewFixScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerApplicationMapper applicationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
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
        R<LoginVo> body = restTemplate.exchange(base + "/api/v1/account/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + apply 建仓；tenantStatus 控制 F5 场景（ACTIVE/PENDING/FROZEN/REJECTED）。 */
    private TaContext registerTaWithTenant(String tenantStatus) {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA").getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("审查修复仓-" + phone);
        dto.setContactPhone(phone);
        R<Map<String, Object>> body = restTemplate.exchange(base + "/api/v1/tenant/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus(tenantStatus);
        tenantMapper.updateById(tenant);
        return new TaContext(phone, token, tenantId);
    }

    private R<Map<String, Object>> selfApply(String waToken, Long tenantId, String name) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetTenantId", tenantId.toString());
        dto.put("name", name);
        return restTemplate.exchange(base + "/api/v1/wholesaler/applications", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(waToken)), MAP).getBody();
    }

    private R<Map<String, Object>> opsCreate(String opsToken, Long tenantId, String name, String waPhone) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("tenantId", tenantId.toString());
        dto.put("name", name);
        dto.put("waPhone", waPhone);
        dto.put("authBasis", "客诉单 KS-REVIEWFIX-001");
        return restTemplate.exchange(base + "/api/v1/admin/wholesalers", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
    }

    // ======================================================================
    // F1：并发唯一
    // ======================================================================

    @Test
    @DisplayName("CON-S7-01(F1) 同账号双线程并发提交入驻申请：恰一方成功另一方50201，库中PENDING恰一条")
    void con_s7_01_concurrentDuplicateApply() throws Exception {
        TaContext ta = registerTaWithTenant("ACTIVE");
        LoginVo wa = registerAndLogin(uniquePhone(P_WA), "WaPass123", "WA");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final String name = "并发唯一商户-" + i + "-" + wa.getUserId();
            futures.add(pool.submit(() -> {
                gate.await();
                R<Map<String, Object>> res = selfApply(wa.getToken(), ta.tenantId(), name);
                return res == null ? -1 : res.getCode();
            }));
        }
        gate.countDown();
        List<Integer> codes = new java.util.ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();

        assertThat(codes).as("恰一方成功、另一方 50201，实际=%s", codes)
                .containsExactlyInAnyOrder(0, 50201);
        TenantContext.clear();
        long pendingCount = applicationMapper.selectCount(new LambdaQueryWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getApplicantUserId, wa.getUserId())
                .eq(WholesalerApplication::getStatus, "PENDING"));
        assertThat(pendingCount).as("uk_applicant_pending 兜底后 PENDING 恰一条").isEqualTo(1);
    }

    // ======================================================================
    // F4：审批复查 + 代建自动关闭
    // ======================================================================

    @Test
    @DisplayName("F4a TA审批通过时账号已有ACTIVE入驻绑定 → 50204复查拒绝且申请保持PENDING(事务回滚)")
    void f4a_auditRechecksActiveBinding() {
        TaContext ta = registerTaWithTenant("ACTIVE");
        String waPhone = uniquePhone(P_WA);
        LoginVo wa = registerAndLogin(waPhone, "WaPass123", "WA");
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OpsPass123", "OPS").getToken();

        // 先 OPS 代建使账号获得 ACTIVE 绑定
        R<Map<String, Object>> created = opsCreate(opsToken, ta.tenantId(), "F4a代建商户-" + waPhone, waPhone);
        assertThat(created).isNotNull();
        assertThat(created.getCode()).isEqualTo(0);

        // 模拟并发窗口遗留：DB 直插一条该账号的 PENDING 申请（绕过应用层预检）
        long appId = snowflakeIdUtil.nextId();
        jdbcTemplate.update(
                "INSERT INTO wholesaler_applications (id, tenant_id, applicant_user_id, name, status, "
                        + "pending_flag, source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 1, 'SELF_APPLY', NOW(), NOW())",
                appId, ta.tenantId(), wa.getUserId(), "F4a遗留申请-" + waPhone);

        Map<String, Object> audit = Map.of("action", "APPROVED", "remark", "F4a 通过尝试");
        R<Map<String, Object>> res = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit", HttpMethod.POST,
                new HttpEntity<>(audit, bearer(ta.token())), MAP).getBody();
        assertThat(res).isNotNull();
        assertThat(res.getCode()).as("已有 ACTIVE 绑定的账号不可重复通过").isEqualTo(50204);

        TenantContext.clear();
        WholesalerApplication after = applicationMapper.selectById(appId);
        assertThat(after.getStatus()).as("50204 抛出随事务回滚，申请保持 PENDING").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("F4b OPS代建成功自动关闭该账号存量PENDING申请(REJECTED+留痕remark+释放pending_flag)")
    void f4b_opsCreateClosesStalePending() {
        TaContext ta = registerTaWithTenant("ACTIVE");
        String waPhone = uniquePhone(P_WA);
        LoginVo wa = registerAndLogin(waPhone, "WaPass123", "WA");
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OpsPass123", "OPS").getToken();

        // 存量 PENDING 申请
        R<Map<String, Object>> applied = selfApply(wa.getToken(), ta.tenantId(), "F4b存量申请-" + waPhone);
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        long appId = Long.parseLong(applied.getData().get("applicationId").toString());

        // OPS 代建同一手机号 → 成功并自动关闭存量申请
        R<Map<String, Object>> created = opsCreate(opsToken, ta.tenantId(), "F4b代建商户-" + waPhone, waPhone);
        assertThat(created).isNotNull();
        assertThat(created.getCode()).isEqualTo(0);

        TenantContext.clear();
        WholesalerApplication stale = applicationMapper.selectById(appId);
        assertThat(stale.getStatus()).isEqualTo("REJECTED");
        assertThat(stale.getAuditRemark()).contains("自动关闭");
        assertThat(stale.getPendingFlag()).isNull();

        // pending_flag 释放后可再入驻？——账号已 ACTIVE 绑定，再申请命中 50204（规则闭环）
        R<Map<String, Object>> reApply = selfApply(wa.getToken(), ta.tenantId(), "F4b重申-" + waPhone);
        assertThat(reApply).isNotNull();
        assertThat(reApply.getCode()).isEqualTo(50204);
    }

    // ======================================================================
    // F5：目标租户状态
    // ======================================================================

    @Test
    @DisplayName("F5 目标租户非ACTIVE：PENDING→50101 / FROZEN→50102 / REJECTED→50103（自助+代建同检）")
    void f5_targetTenantMustBeActive() {
        String opsToken = registerAndLogin(uniquePhone(P_OPS), "OpsPass123", "OPS").getToken();

        record Case(String status, int expect) {}
        for (Case c : List.of(new Case("PENDING", 50101), new Case("FROZEN", 50102),
                new Case("REJECTED", 50103))) {
            TaContext ta = registerTaWithTenant(c.status());
            LoginVo wa = registerAndLogin(uniquePhone(P_WA), "WaPass123", "WA");

            R<Map<String, Object>> self = selfApply(wa.getToken(), ta.tenantId(), "F5自助-" + c.status());
            assertThat(self).isNotNull();
            assertThat(self.getCode()).as("自助申请 目标租户 %s", c.status()).isEqualTo(c.expect());

            R<Map<String, Object>> ops = opsCreate(opsToken, ta.tenantId(),
                    "F5代建-" + c.status(), uniquePhone(P_WA));
            assertThat(ops).isNotNull();
            assertThat(ops.getCode()).as("OPS 代建 目标租户 %s", c.status()).isEqualTo(c.expect());
        }
    }

    // ======================================================================
    // SEC-S4-01：无 token 全扫
    // ======================================================================

    @Test
    @DisplayName("SEC-S4-01 入驻/退驻/员工端点无token全扫 → 一律41001(HTTP 401)")
    void sec_s4_01_noTokenSweep() {
        record Probe(HttpMethod method, String path) {}
        List<Probe> probes = List.of(
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/applications"),
                new Probe(HttpMethod.GET, "/api/v1/wholesaler/applications"),
                new Probe(HttpMethod.GET, "/api/v1/tenant/wholesaler-applications"),
                new Probe(HttpMethod.POST, "/api/v1/tenant/wholesaler-applications/1/audit"),
                new Probe(HttpMethod.POST, "/api/v1/admin/wholesalers"),
                new Probe(HttpMethod.GET, "/api/v1/admin/tenants"),
                new Probe(HttpMethod.GET, "/api/v1/ops/blacklist"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/withdraw"),
                new Probe(HttpMethod.GET, "/api/v1/wholesaler/withdraw/precheck"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/withdraw/cancel"),
                new Probe(HttpMethod.GET, "/api/v1/wholesaler/withdraw/mine"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/withdraw/restore"),
                new Probe(HttpMethod.GET, "/api/v1/tenant/wholesaler-withdraw-applications"),
                new Probe(HttpMethod.POST, "/api/v1/tenant/wholesalers/1/force-offline"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/employee-invites"),
                new Probe(HttpMethod.GET, "/api/v1/wholesaler/employee-invites"),
                new Probe(HttpMethod.DELETE, "/api/v1/wholesaler/employee-invites/1"),
                new Probe(HttpMethod.GET, "/api/v1/wholesaler/employees"),
                new Probe(HttpMethod.PUT, "/api/v1/wholesaler/employees/1/permissions"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/employees/1/disable"),
                new Probe(HttpMethod.POST, "/api/v1/wholesaler/employees/1/restore"));

        for (Probe p : probes) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            R<Map<String, Object>> body = restTemplate.exchange(base + p.path(), p.method(),
                    new HttpEntity<>("{}", headers), MAP).getBody();
            assertThat(body).as("%s %s 应返回统一未登录结构", p.method(), p.path()).isNotNull();
            assertThat(body.getCode()).as("%s %s 无 token 必须 41001", p.method(), p.path())
                    .isEqualTo(41001);
        }
    }
}
