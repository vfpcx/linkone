package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

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
 * P2 入驻生态 Wave1 场景测试：申请 → TA 审批 → 黑名单 → OPS 代建。
 *
 * <p>测试基建沿用 {@link WholesalerScenarioTest} 风格
 * （@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信码 888888）。
 *
 * <p>覆盖（task_plan Wave1 验收面）：
 * <ul>
 *   <li>申请成功 → TA 通过 → WA 角色生效（再次申请命中 50204 重复入驻）。</li>
 *   <li>驳回带理由；驳回缺 remark 拒绝；驳回后可重新申请。</li>
 *   <li>重复 PENDING 申请 → 50201。</li>
 *   <li>黑名单拦截自助申请 + 拦截 OPS 代建（决策 O-2）→ 50205；解除后放行。</li>
 *   <li>OPS 代建缺 authBasis 拒绝；成功 → ACTIVE/OPS_CREATED；非 OPS → 42002。</li>
 *   <li>跨租户不可见（TenantLine）：列表隔离 + 跨租户审批 50203。</li>
 *   <li>注册接入：WA 注册带 targetTenantId/wholesalerName 自动建 PENDING 申请单。</li>
 *   <li>TA 自营 createSelfOperated 补 APPROVED 申请单留痕（TA_SELF_OPERATED）。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private com.cangchu.tenant.mapper.TenantMapper tenantMapper;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_PROXY =
            "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseWaApply;
    private String baseTaApps;
    private String baseAdminWholesalers;
    private String baseOpsBlacklist;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseTenant = base + "/api/v1/tenant";
        baseWaApply = base + "/api/v1/wholesaler/applications";
        baseTaApps = base + "/api/v1/tenant/wholesaler-applications";
        baseAdminWholesalers = base + "/api/v1/admin/wholesalers";
        baseOpsBlacklist = base + "/api/v1/ops/blacklist";
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

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private R<LoginVo> register(String phone, String password, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        return restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private String registerAndLogin(String phone, String password, String role) {
        R<LoginVo> body = register(phone, password, role);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + apply 建仓（user_roles 绑定 tenantId，供 TenantContext 推导）。 */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("入驻仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        // F5 审查修复后入驻目标租户必须 ACTIVE（OPS 审核不在本测试范围，直接置位）
        com.cangchu.tenant.entity.Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        return new TaContext(phone, token, tenantId);
    }

    private String registerOps() {
        return registerAndLogin(uniquePhone(P_OPS), "OpsPass123", "OPS");
    }

    private record WaContext(String phone, String token) {}

    private WaContext registerWa() {
        String phone = uniquePhone(P_WA);
        return new WaContext(phone, registerAndLogin(phone, "WaPass123", "WA"));
    }

    /** WA 自助提交入驻申请。 */
    private R<Map<String, Object>> selfApply(WaContext wa, Long tenantId, String name,
                                             String contactPhone, String license) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetTenantId", tenantId.toString());
        dto.put("name", name);
        dto.put("contactName", "联系人-" + wa.phone());
        if (contactPhone != null) dto.put("contactPhone", contactPhone);
        if (license != null) dto.put("license", license);
        return restTemplate.exchange(baseWaApply, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(wa.token())), MAP).getBody();
    }

    /** TA 审批。 */
    private R<Map<String, Object>> audit(String taToken, String applicationId, String action, String remark) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("action", action);
        if (remark != null) dto.put("remark", remark);
        return restTemplate.exchange(baseTaApps + "/" + applicationId + "/audit", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(taToken)), MAP).getBody();
    }

    /** TA 分页列表（默认第 1 页 50 条）。 */
    private R<Map<String, Object>> listApps(String taToken, String status) {
        String url = baseTaApps + "?page=1&size=50" + (status != null ? "&status=" + status : "");
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(bearer(taToken)), MAP).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(R<Map<String, Object>> listBody) {
        assertThat(listBody).isNotNull();
        assertThat(listBody.getCode()).isEqualTo(0);
        return (List<Map<String, Object>>) listBody.getData().get("records");
    }

    /** OPS 加黑。 */
    private R<Map<String, Object>> addBlacklist(String opsToken, String type, String value, String reason) {
        Map<String, Object> dto = Map.of("targetType", type, "targetValue", value, "reason", reason);
        return restTemplate.exchange(baseOpsBlacklist, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
    }

    /** OPS 代建。 */
    private R<Map<String, Object>> opsCreate(String opsToken, Long tenantId, String name,
                                             String waPhone, String license, String authBasis) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("tenantId", tenantId.toString());
        dto.put("name", name);
        dto.put("waPhone", waPhone);
        if (license != null) dto.put("license", license);
        if (authBasis != null) dto.put("authBasis", authBasis);
        return restTemplate.exchange(baseAdminWholesalers, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
    }

    // ======================================================================
    // S1 主链：申请 → TA 通过 → WA 角色生效
    // ======================================================================

    @Test
    @DisplayName("ONB-01 WA 申请 → TA 通过 → wholesaler ACTIVE/SELF_APPLY + 回填 + 角色生效(再申请 50204)")
    void s1_applyApproveChain() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWa();

        // WA 提交申请 → PENDING
        R<Map<String, Object>> applied = selfApply(wa, ta.tenantId(), "批发商甲-" + wa.phone(), null, null);
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        assertThat(applied.getData().get("status")).isEqualTo("PENDING");
        String appId = applied.getData().get("applicationId").toString();

        // TA 列表可见该 PENDING 申请
        List<Map<String, Object>> recs = records(listApps(ta.token(), "PENDING"));
        assertThat(recs).extracting(m -> m.get("id").toString()).contains(appId);

        // TA 审批通过 → APPROVED + wholesalerId 回填
        R<Map<String, Object>> approved = audit(ta.token(), appId, "APPROVED", "资质齐全");
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        assertThat(approved.getData().get("status")).isEqualTo("APPROVED");
        assertThat(approved.getData().get("wholesalerId")).isNotNull();

        // 主体表：TA 商户列表含 ACTIVE / SELF_APPLY
        R<List<Map<String, Object>>> wsList = restTemplate.exchange(
                baseTenant + "/wholesalers", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), LIST).getBody();
        assertThat(wsList).isNotNull();
        assertThat(wsList.getCode()).isEqualTo(0);
        Map<String, Object> created = wsList.getData().stream()
                .filter(m -> ("批发商甲-" + wa.phone()).equals(m.get("name")))
                .findFirst().orElseThrow();
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(created.get("source")).isEqualTo("SELF_APPLY");

        // WA 角色已生效 → 再次申请命中重复入驻 50204
        R<Map<String, Object>> again = selfApply(wa, ta.tenantId(), "批发商甲二店-" + wa.phone(), null, null);
        assertThat(again).isNotNull();
        assertThat(again.getCode()).as("已入驻账号重复申请应拒 50204").isEqualTo(50204);
    }

    // ======================================================================
    // S2 驳回
    // ======================================================================

    @Test
    @DisplayName("ONB-02 TA 驳回必须带理由；驳回后状态 REJECTED 且可重新申请")
    void s2_rejectFlow() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWa();
        String appId = selfApply(wa, ta.tenantId(), "批发商乙-" + wa.phone(), null, null)
                .getData().get("applicationId").toString();

        // 缺 remark 驳回 → 拒绝（参数校验）
        R<Map<String, Object>> noRemark = audit(ta.token(), appId, "REJECTED", null);
        assertThat(noRemark).isNotNull();
        assertThat(noRemark.getCode()).as("驳回必填理由").isIn(40001, 40003);

        // 带理由驳回 → REJECTED + remark 留痕
        R<Map<String, Object>> rejected = audit(ta.token(), appId, "REJECTED", "资质不全");
        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(0);
        assertThat(rejected.getData().get("status")).isEqualTo("REJECTED");

        List<Map<String, Object>> recs = records(listApps(ta.token(), "REJECTED"));
        Map<String, Object> rec = recs.stream()
                .filter(m -> appId.equals(m.get("id").toString())).findFirst().orElseThrow();
        assertThat(rec.get("auditRemark")).isEqualTo("资质不全");

        // 已驳回申请不可再审 → 50203
        R<Map<String, Object>> reAudit = audit(ta.token(), appId, "APPROVED", null);
        assertThat(reAudit).isNotNull();
        assertThat(reAudit.getCode()).as("仅 PENDING 可审").isEqualTo(50203);

        // 驳回后 WA 可重新申请
        R<Map<String, Object>> reApply = selfApply(wa, ta.tenantId(), "批发商乙重申-" + wa.phone(), null, null);
        assertThat(reApply).isNotNull();
        assertThat(reApply.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("ONB-03 已有 PENDING 申请再次提交 → 50201")
    void s2_duplicatePending() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWa();
        assertThat(selfApply(wa, ta.tenantId(), "批发商丙-" + wa.phone(), null, null).getCode()).isEqualTo(0);

        R<Map<String, Object>> dup = selfApply(wa, ta.tenantId(), "批发商丙-二次-" + wa.phone(), null, null);
        assertThat(dup).isNotNull();
        assertThat(dup.getCode()).as("PENDING 未决时重复申请应拒 50201").isEqualTo(50201);
    }

    // ======================================================================
    // S3 黑名单
    // ======================================================================

    @Test
    @DisplayName("BLK-01 黑名单手机号拦截自助申请(50205)；解除后放行")
    void s3_blacklistBlocksSelfApply() {
        TaContext ta = registerTaWithTenant();
        String ops = registerOps();
        WaContext wa = registerWa();

        // OPS 把该 WA 注册手机号拉黑
        R<Map<String, Object>> added = addBlacklist(ops, "PHONE", wa.phone(), "恶意刷单");
        assertThat(added).isNotNull();
        assertThat(added.getCode()).isEqualTo(0);
        String entryId = added.getData().get("id").toString();

        R<Map<String, Object>> blocked = selfApply(wa, ta.tenantId(), "黑名单商户-" + wa.phone(), null, null);
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("黑名单命中应拒 50205").isEqualTo(50205);
        // DEF-2：对外文案中性化，不得透出「黑名单」字样
        assertThat(blocked.getMessage()).as("50205 文案须中性，不透出黑名单字样")
                .isEqualTo("暂不满足入驻条件，请联系平台客服")
                .doesNotContain("黑名单");

        // 解除黑名单 → 再申请放行
        R<Void> removed = restTemplate.exchange(baseOpsBlacklist + "/" + entryId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(ops)), VOID).getBody();
        assertThat(removed).isNotNull();
        assertThat(removed.getCode()).isEqualTo(0);

        R<Map<String, Object>> pass = selfApply(wa, ta.tenantId(), "黑名单解除-" + wa.phone(), null, null);
        assertThat(pass).isNotNull();
        assertThat(pass.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("BLK-02 黑名单执照号拦截 OPS 代建（决策 O-2 防绕过）→ 50205")
    void s3_blacklistBlocksOpsCreate() {
        TaContext ta = registerTaWithTenant();
        String ops = registerOps();
        String license = "91330100MA" + uniquePhone("00");

        assertThat(addBlacklist(ops, "LICENSE_NO", license, "执照涉假").getCode()).isEqualTo(0);

        R<Map<String, Object>> blocked = opsCreate(ops, ta.tenantId(), "代建拦截-" + license,
                uniquePhone(P_PROXY), license, "TA 授权书 SQ-001");
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("黑名单同样拦截 OPS 代建").isEqualTo(50205);
    }

    @Test
    @DisplayName("BLK-03 重复加黑 → 50310；非 OPS 操作黑名单 → 42002")
    void s3_blacklistManage() {
        String ops = registerOps();
        String phone = uniquePhone(P_PROXY);
        assertThat(addBlacklist(ops, "PHONE", phone, "重复测试").getCode()).isEqualTo(0);
        assertThat(addBlacklist(ops, "PHONE", phone, "重复测试2").getCode()).isEqualTo(50310);

        // 列表可见 ACTIVE 条目（DEF-6：分页 PageRecords 契约 records/total/page/size）
        R<Map<String, Object>> list = restTemplate.exchange(
                baseOpsBlacklist + "?page=1&size=50&keyword=" + phone, HttpMethod.GET,
                new HttpEntity<>(bearer(ops)), MAP).getBody();
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blRecords = (List<Map<String, Object>>) list.getData().get("records");
        assertThat(blRecords).extracting(m -> m.get("targetValue")).contains(phone);

        // 非 OPS（TA）操作黑名单 → 42002
        TaContext ta = registerTaWithTenant();
        R<Map<String, Object>> denied = addBlacklist(ta.token(), "PHONE", uniquePhone(P_PROXY), "越权");
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).isEqualTo(42002);
    }

    // ======================================================================
    // S4 OPS 代建
    // ======================================================================

    @Test
    @DisplayName("ONB-04 OPS 代建成功 → ACTIVE/OPS_CREATED + WA 开通；缺 authBasis → 拒绝")
    void s4_opsCreate() {
        TaContext ta = registerTaWithTenant();
        String ops = registerOps();
        String waPhone = uniquePhone(P_PROXY);

        // 缺 authBasis → 参数校验拒绝
        R<Map<String, Object>> noBasis = opsCreate(ops, ta.tenantId(), "代建无凭据", waPhone, null, null);
        assertThat(noBasis).isNotNull();
        assertThat(noBasis.getCode()).as("authBasis 必填").isIn(40001, 40003);

        // 带凭据 → 成功
        R<Map<String, Object>> okBody = opsCreate(ops, ta.tenantId(), "代建商户-" + waPhone,
                waPhone, null, "客诉单 KS-20260716-01");
        assertThat(okBody).isNotNull();
        assertThat(okBody.getCode()).isEqualTo(0);
        assertThat(okBody.getData().get("status")).isEqualTo("ACTIVE");
        assertThat(okBody.getData().get("source")).isEqualTo("OPS_CREATED");
        assertThat(okBody.getData().get("waUserId")).isNotNull();

        // 留痕：TA 侧申请单列表含 APPROVED / OPS_CREATED（authBasis 落库）
        List<Map<String, Object>> recs = records(listApps(ta.token(), "APPROVED"));
        Map<String, Object> trace = recs.stream()
                .filter(m -> ("代建商户-" + waPhone).equals(m.get("name")))
                .findFirst().orElseThrow();
        assertThat(trace.get("source")).isEqualTo("OPS_CREATED");
        assertThat(trace.get("authBasis")).isEqualTo("客诉单 KS-20260716-01");
    }

    @Test
    @DisplayName("SEC-01 非 OPS 调代建 → 42002")
    void s4_nonOpsCreate() {
        TaContext ta = registerTaWithTenant();
        R<Map<String, Object>> denied = opsCreate(ta.token(), ta.tenantId(), "越权代建",
                uniquePhone(P_PROXY), null, "伪造凭据");
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).isEqualTo(42002);
    }

    // ======================================================================
    // S5 跨租户隔离（TenantLine）
    // ======================================================================

    @Test
    @DisplayName("SEC-S4-06 B 租户 TA 看不到 A 租户申请（TenantLine）；跨租户审批 → 50203")
    void s5_crossTenantIsolation() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();
        WaContext wa = registerWa();
        String appId = selfApply(wa, a.tenantId(), "A仓商户-" + wa.phone(), null, null)
                .getData().get("applicationId").toString();

        // B 列表不可见 A 的申请
        List<Map<String, Object>> bRecs = records(listApps(b.token(), null));
        assertThat(bRecs).extracting(m -> m.get("id").toString()).doesNotContain(appId);

        // B 审批 A 的申请 → 不可见即不存在 50203
        R<Map<String, Object>> denied = audit(b.token(), appId, "APPROVED", null);
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).as("跨租户审批应按不存在拒绝").isEqualTo(50203);

        // 非 TA（WA 自己）调审批 → 越权拒绝
        R<Map<String, Object>> waDenied = audit(wa.token(), appId, "APPROVED", null);
        assertThat(waDenied).isNotNull();
        assertThat(waDenied.getCode()).isNotEqualTo(0);
    }

    // ======================================================================
    // S6 注册接入（AccountServiceImpl WA 注册自动建申请单）
    // ======================================================================

    @Test
    @DisplayName("ONB-05 WA 注册带 targetTenantId/wholesalerName → 自动建 PENDING 申请单")
    void s6_registerAutoApply() {
        TaContext ta = registerTaWithTenant();
        String phone = uniquePhone(P_WA);

        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("WaPass123");
        dto.setSmsCode("888888");
        dto.setRole("WA");
        dto.setAgreedTerms(true);
        dto.setWholesalerName("注册直申-" + phone);
        dto.setTargetTenantId(ta.tenantId().toString());
        R<LoginVo> reg = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);

        List<Map<String, Object>> recs = records(listApps(ta.token(), "PENDING"));
        Map<String, Object> rec = recs.stream()
                .filter(m -> ("注册直申-" + phone).equals(m.get("name")))
                .findFirst().orElseThrow();
        assertThat(rec.get("source")).isEqualTo("SELF_APPLY");
        assertThat(rec.get("contactPhone")).isEqualTo(phone);
    }

    @Test
    @DisplayName("BLK-04 黑名单手机号 WA 注册直申 → 50205（注册即拦截）")
    void s6_registerBlacklisted() {
        TaContext ta = registerTaWithTenant();
        String ops = registerOps();
        String phone = uniquePhone(P_WA);
        assertThat(addBlacklist(ops, "PHONE", phone, "黑名单注册拦截").getCode()).isEqualTo(0);

        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("WaPass123");
        dto.setSmsCode("888888");
        dto.setRole("WA");
        dto.setAgreedTerms(true);
        dto.setWholesalerName("黑名单直申-" + phone);
        dto.setTargetTenantId(ta.tenantId().toString());
        R<LoginVo> reg = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).as("黑名单命中注册直申应拒 50205").isEqualTo(50205);
    }

    // ======================================================================
    // S7 TA 自营统一留痕
    // ======================================================================

    @Test
    @DisplayName("ONB-06 TA 建自营商户 → 补 APPROVED 申请单留痕(TA_SELF_OPERATED)，行为兼容")
    void s7_selfOperatedTrace() {
        TaContext ta = registerTaWithTenant();
        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("自营留痕-" + ta.phone());
        R<Map<String, Object>> created = restTemplate.exchange(baseTenant + "/wholesalers",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(created).isNotNull();
        assertThat(created.getCode()).isEqualTo(0);
        // 行为兼容：主体仍 ACTIVE / SELF_OPERATED
        assertThat(created.getData().get("status")).isEqualTo("ACTIVE");
        assertThat(created.getData().get("source")).isEqualTo("SELF_OPERATED");

        // 申请单留痕：APPROVED / TA_SELF_OPERATED + wholesaler_id 回填
        List<Map<String, Object>> recs = records(listApps(ta.token(), "APPROVED"));
        Map<String, Object> trace = recs.stream()
                .filter(m -> ("自营留痕-" + ta.phone()).equals(m.get("name")))
                .findFirst().orElseThrow();
        assertThat(trace.get("source")).isEqualTo("TA_SELF_OPERATED");
        assertThat(trace.get("wholesalerId").toString())
                .isEqualTo(created.getData().get("id").toString());
    }

    // ======================================================================
    // S8 测试计划高危自查点（测试&审查 Agent 04-onboarding-test-plan）
    // ======================================================================

    @Test
    @DisplayName("BLK-S1-05 黑名单同样拦截 TA 建自营（第三条入驻路径防绕过）→ 50205")
    void blk_s1_05_blacklistBlocksSelfOperated() {
        TaContext ta = registerTaWithTenant();
        String ops = registerOps();
        String waPhone = uniquePhone(P_PROXY);
        assertThat(addBlacklist(ops, "PHONE", waPhone, "自营绕过防护测试").getCode()).isEqualTo(0);

        WholesalerCreateDto dto = new WholesalerCreateDto();
        dto.setName("自营黑名单-" + waPhone);
        dto.setWaPhone(waPhone);
        R<Map<String, Object>> blocked = restTemplate.exchange(baseTenant + "/wholesalers",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("TA 自营路径同样受黑名单拦截").isEqualTo(50205);
    }

    @Test
    @DisplayName("SEC-02 伪造 X-Tenant-Id 头不可覆盖登录态租户（G-2.1）→ 42101")
    void sec_forgedTenantHeader() {
        TaContext a = registerTaWithTenant();
        TaContext b = registerTaWithTenant();

        // B 的 TA 伪造 X-Tenant-Id=A 的租户访问审批列表 → 拦截器校验归属失败
        HttpHeaders headers = bearer(b.token());
        headers.set("X-Tenant-Id", a.tenantId().toString());
        R<Map<String, Object>> denied = restTemplate.exchange(baseTaApps + "?page=1&size=10",
                HttpMethod.GET, new HttpEntity<>(headers), MAP).getBody();
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).as("伪造 X-Tenant-Id 应被 42101 拒绝").isEqualTo(42101);
    }

    @Test
    @DisplayName("CON-01 并发 approve/reject 同一申请 → 仅一方成功（DB 条件更新 CAS，非内存判断）")
    void con_concurrentAudit() throws Exception {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWa();
        String appId = selfApply(wa, ta.tenantId(), "并发商户-" + wa.phone(), null, null)
                .getData().get("applicationId").toString();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approve = pool.submit(() -> {
                start.await();
                R<Map<String, Object>> r = audit(ta.token(), appId, "APPROVED", null);
                return r != null ? r.getCode() : -1;
            });
            Future<Integer> reject = pool.submit(() -> {
                start.await();
                R<Map<String, Object>> r = audit(ta.token(), appId, "REJECTED", "并发驳回");
                return r != null ? r.getCode() : -1;
            });
            start.countDown();
            int approveCode = approve.get();
            int rejectCode = reject.get();

            // 恰有一方成功，另一方被 CAS 抢占拒绝（50203）
            assertThat((approveCode == 0) ^ (rejectCode == 0))
                    .as("并发审批必须恰一方成功: approve=%s reject=%s", approveCode, rejectCode)
                    .isTrue();
            assertThat(approveCode == 0 ? rejectCode : approveCode).isEqualTo(50203);

            // 终态与赢家一致，且不可再审
            String finalStatus = approveCode == 0 ? "APPROVED" : "REJECTED";
            List<Map<String, Object>> recs = records(listApps(ta.token(), finalStatus));
            assertThat(recs).extracting(m -> m.get("id").toString()).contains(appId);
            R<Map<String, Object>> reAudit = audit(ta.token(), appId, "APPROVED", null);
            assertThat(reAudit).isNotNull();
            assertThat(reAudit.getCode()).isEqualTo(50203);
        } finally {
            pool.shutdownNow();
        }
    }
}
