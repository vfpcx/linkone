package com.cangchu.common.pii;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.mapper.TenantMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII-W7 查全号 + VO 脱敏 + 检索口径场景测试（15-pii-hardening-v2 §4 阶段2 / §5.2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>查全号端点 /api/v1/pii/phone-reveal：BLACKLIST / TENANT / WA_APPLICATION / INQUIRY
 *       四类 biz 的权限矩阵（OPS / 归属 TA / 归属 WA 可取，越权 50402，非手机号行 50400，不存在 50401）。</li>
 *   <li>黑名单列表打码（PHONE 行 138****1234；LICENSE_NO 非手机号 PII 原样）。</li>
 *   <li>黑名单检索口径切换（15 §4 阶段2-2）：完整 11 位 → hmac/明文精确；last4 → RIGHT 尾号；
 *       执照号子串 → LIKE（非 PII 保留）。</li>
 * </ul>
 * 基建沿用 {@code OnboardingScenarioTest}（RANDOM_PORT + TestRestTemplate + H2 + mock 888888）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PiiRevealScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private com.cangchu.tenant.mapper.TenantMapper tenantMapper;
    @Autowired
    private BlacklistMapper blacklistMapper;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseWaApply;
    private String baseTaApps;
    private String baseOpsBlacklist;
    private String baseReveal;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseTenant = base + "/api/v1/tenant";
        baseWaApply = base + "/api/v1/wholesaler/applications";
        baseTaApps = base + "/api/v1/tenant/wholesaler-applications";
        baseOpsBlacklist = base + "/api/v1/ops/blacklist";
        baseReveal = base + "/api/v1/pii/phone-reveal";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

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

    private String registerOps() {
        return registerAndLogin(uniquePhone(P_OPS), "OpsPass123", "OPS");
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + apply 建仓 + 直接置 ACTIVE（同 OnboardingScenarioTest 惯例）。 */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("查全号仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        com.cangchu.tenant.entity.Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        return new TaContext(phone, token, tenantId);
    }

    private record WaContext(String phone, String token) {}

    private WaContext registerWa() {
        String phone = uniquePhone(P_WA);
        return new WaContext(phone, registerAndLogin(phone, "WaPass123", "WA"));
    }

    private R<Map<String, Object>> selfApply(WaContext wa, Long tenantId, String name) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetTenantId", tenantId.toString());
        dto.put("name", name);
        dto.put("contactName", "联系人-" + wa.phone());
        return restTemplate.exchange(baseWaApply, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(wa.token())), MAP).getBody();
    }

    private R<Map<String, Object>> audit(String taToken, String applicationId, String action, String remark) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("action", action);
        if (remark != null) dto.put("remark", remark);
        return restTemplate.exchange(baseTaApps + "/" + applicationId + "/audit", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(taToken)), MAP).getBody();
    }

    /** OPS 加黑，返回黑名单 id。 */
    private Long addBlacklist(String opsToken, String type, String value) {
        Map<String, Object> dto = Map.of("targetType", type, "targetValue", value, "reason", "PII-W7 测试");
        R<Map<String, Object>> body = restTemplate.exchange(baseOpsBlacklist, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(opsToken)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return Long.parseLong(body.getData().get("id").toString());
    }

    /** 查全号：返回 R（成功 → data.phone 明文；失败 → 错误码）。 */
    private R<Map<String, Object>> reveal(String token, String biz, Long id) {
        String url = baseReveal + "?biz=" + biz + "&id=" + id;
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    private static final int C_FORBIDDEN = ErrorCode.PII_REVEAL_FORBIDDEN.getCode();
    private static final int C_TYPE = ErrorCode.PII_REVEAL_TYPE_INVALID.getCode();
    private static final int C_NOT_FOUND = ErrorCode.PII_REVEAL_TARGET_NOT_FOUND.getCode();

    // ======================================================================
    // A. 查全号 BLACKLIST：OPS 可取，越权 50402，非手机号行 50400，不存在 50401
    // ======================================================================

    @Test
    @DisplayName("REV-01 BLACKLIST：OPS 取回全号；TA 越权 50402；LICENSE_NO 行 50400；不存在 50401")
    void reveal_blacklist_permissionMatrix() {
        String ops = registerOps();
        String phone = uniquePhone(P_OPS);
        Long phoneId = addBlacklist(ops, "PHONE", phone);
        Long licId = addBlacklist(ops, "LICENSE_NO", "91LIC" + phone + "IC");
        TaContext ta = registerTaWithTenant();

        // OPS 可取回全号（列表打码后的反向：查看完整号）
        R<Map<String, Object>> ok = reveal(ops, "BLACKLIST", phoneId);
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("phone")).isEqualTo(phone);

        // TA 越权 → 50402
        assertThat(reveal(ta.token(), "BLACKLIST", phoneId).getCode()).isEqualTo(C_FORBIDDEN);
        // 非手机号行 → 50400
        assertThat(reveal(ops, "BLACKLIST", licId).getCode()).isEqualTo(C_TYPE);
        // 不存在 → 50401
        assertThat(reveal(ops, "BLACKLIST", 99999999999L).getCode()).isEqualTo(C_NOT_FOUND);
    }

    // ======================================================================
    // B. 查全号 TENANT：OPS 可取，TA 越权 50402
    // ======================================================================

    @Test
    @DisplayName("REV-02 TENANT：OPS 取回租户 contactPhone；TA 越权 50402")
    void reveal_tenant_permission() {
        String ops = registerOps();
        TaContext ta = registerTaWithTenant();

        R<Map<String, Object>> ok = reveal(ops, "TENANT", ta.tenantId());
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("phone")).isEqualTo(ta.phone());

        assertThat(reveal(ta.token(), "TENANT", ta.tenantId()).getCode()).isEqualTo(C_FORBIDDEN);
    }

    // ======================================================================
    // C. 查全号 WA_APPLICATION：OPS / 归属 TA 可取，跨租户 TA 50402
    // ======================================================================

    @Test
    @DisplayName("REV-03 WA_APPLICATION：归属 TA 与 OPS 可取；跨租户 TA 越权 50402")
    void reveal_waApplication_owningTaOnly() {
        String ops = registerOps();
        TaContext ta1 = registerTaWithTenant();
        TaContext ta2 = registerTaWithTenant();
        WaContext wa = registerWa();

        R<Map<String, Object>> applied = selfApply(wa, ta1.tenantId(), "申请甲-" + wa.phone());
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();
        Long id = Long.parseLong(appId);

        // 归属 TA（ta1）可取回申请 contactPhone（默认=注册手机号）
        R<Map<String, Object>> ok = reveal(ta1.token(), "WA_APPLICATION", id);
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("phone")).isEqualTo(wa.phone());

        // OPS 平台审核同样可取
        assertThat(reveal(ops, "WA_APPLICATION", id).getCode()).isEqualTo(0);
        // 跨租户 TA 越权 → 50402
        assertThat(reveal(ta2.token(), "WA_APPLICATION", id).getCode()).isEqualTo(C_FORBIDDEN);
    }

    // ======================================================================
    // D. 查全号 INQUIRY：归属 WA 可取，非归属 50402
    // ======================================================================

    @Test
    @DisplayName("REV-04 INQUIRY：归属 WA 可取回询价买家号；TA 越权 50402")
    void reveal_inquiry_owningWaOnly() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = registerWa();

        // WA 申请 + 审批通过 → wholesalerId 回填（WA 角色绑定）
        R<Map<String, Object>> applied = selfApply(wa, ta.tenantId(), "询价商户-" + wa.phone());
        R<Map<String, Object>> approved = audit(ta.token(), applied.getData().get("applicationId").toString(),
                "APPROVED", "资质齐全");
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        Long wholesalerId = Long.parseLong(approved.getData().get("wholesalerId").toString());

        // 造一条该商户的询价单（mapper-seed，沿用 PII 测试惯例）
        InquiryRequest inq = new InquiryRequest();
        inq.setDocNo("REV-INQ-" + SEQ.incrementAndGet());
        inq.setStoreId(1L);
        inq.setTenantId(ta.tenantId());
        inq.setWholesalerId(wholesalerId);
        inq.setRtPhone("13800001234");
        inq.setStatus(InquiryRequest.STATUS_PENDING);
        inquiryRequestMapper.insert(inq);

        R<Map<String, Object>> ok = reveal(wa.token(), "INQUIRY", inq.getId());
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).isEqualTo(0);
        assertThat(ok.getData().get("phone")).isEqualTo("13800001234");

        // 非归属（TA 无 WA 角色）越权 → 50402
        assertThat(reveal(ta.token(), "INQUIRY", inq.getId()).getCode()).isEqualTo(C_FORBIDDEN);
    }

    // ======================================================================
    // E. 黑名单列表打码 + 检索口径切换（15 §4 阶段2-2）
    // ======================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blacklistRecords(R<Map<String, Object>> listBody) {
        assertThat(listBody).isNotNull();
        assertThat(listBody.getCode()).isEqualTo(0);
        return (List<Map<String, Object>>) listBody.getData().get("records");
    }

    private R<Map<String, Object>> searchBlacklist(String opsToken, String keyword) {
        String url = baseOpsBlacklist + "?page=1&size=50" + (keyword != null ? "&keyword=" + keyword : "");
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
    }

    @Test
    @DisplayName("REV-05 黑名单检索：完整 11 位精确命中 + last4 尾号命中，且列表一律打码")
    void blacklist_search_phoneExactAndLast4() {
        String ops = registerOps();
        String phone = uniquePhone(P_OPS);
        addBlacklist(ops, "PHONE", phone);

        // 完整 11 位 → 精确查（明文/hmac 双轨，hmac 漏填也不漏）
        List<Map<String, Object>> byFull = blacklistRecords(searchBlacklist(ops, phone));
        assertThat(byFull).hasSize(1);
        // 列表打码（138****1234），全号走 phone-reveal
        assertThat(byFull.get(0).get("targetValue")).isEqualTo(SmsUtil.maskPhone(phone));

        // last4 尾号 → RIGHT 命中
        String last4 = phone.substring(7);
        List<Map<String, Object>> byLast4 = blacklistRecords(searchBlacklist(ops, last4));
        assertThat(byLast4).as("last4=%s 应命中 %s", last4, phone).hasSize(1);
        assertThat(byLast4.get(0).get("targetValue")).isEqualTo(SmsUtil.maskPhone(phone));
    }

    @Test
    @DisplayName("REV-06 黑名单检索：执照号子串仍 LIKE 命中（非手机号 PII），且 LICENSE_NO 行不打码")
    void blacklist_search_licenseSubstringKeepsPlain() {
        String ops = registerOps();
        String licToken = "LIC" + uniquePhone(P_OPS).substring(3);
        String license = "91" + licToken + "IC";
        addBlacklist(ops, "LICENSE_NO", license);

        List<Map<String, Object>> byLic = blacklistRecords(searchBlacklist(ops, licToken));
        assertThat(byLic).hasSize(1);
        assertThat(byLic.get(0).get("targetType")).isEqualTo("LICENSE_NO");
        // LICENSE_NO 非手机号 PII：列表原样，不进 maskPhone
        assertThat(byLic.get(0).get("targetValue")).isEqualTo(license);
    }
}
