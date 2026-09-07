package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F6 RT 登录子波（US-RT-04 / D-RT-01）：RT 本人对已确认/已完成意向单查批发商（WA）联系方式。
 *
 * <p>覆盖 /api/v1/pii/phone-reveal?biz=RT_WHOLESALER&amp;id={意向单 id} 的权限矩阵：
 * <ul>
 *   <li>REV-07 本人 CONFIRMED/COMPLETED → 200 全号（该批发商绑定的 ACTIVE WA 登录手机号）；</li>
 *   <li>REV-08 PENDING/VOIDED 未成交 → 50402（确认后展示闸门）；</li>
 *   <li>REV-09 非本人 RT / 无 RT 角色（WA）→ 50402；</li>
 *   <li>REV-10 无 ACTIVE WA 绑定 → 50401；</li>
 *   <li>REV-11 匿名 → HTTP 401 + 41001。</li>
 * </ul>
 * 基建沿用 {@code PiiRevealScenarioTest}（RANDOM_PORT + TestRestTemplate + H2 + mock 888888）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RtWholesalerContactRevealScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private PiiCrypto piiCrypto;

    /** 11 位静态号码：不同前缀 + nano 尾号（9 位），保证本类内不撞号。 */
    private static String staticPhone(String prefix) {
        return prefix + String.format("%09d", (System.nanoTime() & 0x7FFFFFFF) % 1_000_000_000L);
    }

    /** RT 意向单提交手机号（与询价归属同源）。 */
    private static final String P_RT_OWNER = staticPhone("18");
    /** 另一 RT（非本人）。 */
    private static final String P_RT_OTHER = staticPhone("17");
    /** 7 位前缀骨架，配合 {@link #uniquePhone} 拼 11 位（每方法唯一，避免共享上下文重复注册）。 */
    private static final String P_WA7 =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_TA7 =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseWaApply;
    private String baseTaApps;
    private String baseReveal;

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseTenant = base + "/api/v1/tenant";
        baseWaApply = base + "/api/v1/wholesaler/applications";
        baseTaApps = base + "/api/v1/tenant/wholesaler-applications";
        baseReveal = base + "/api/v1/pii/phone-reveal";
    }

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

    /** RT 免密验证码登录（首次自动注册，mock 888888）。 */
    private String rtSmsLogin(String phone) {
        R<LoginVo> body = restTemplate.exchange(
                baseAccount + "/login/rt?phone=" + phone + "&code=888888",
                HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("rt login %s", phone).isEqualTo(0);
        return body.getData().getToken();
    }

    private record TaContext(String token, Long tenantId) {}

    /** 注册 TA + apply 建仓 + 直接置 ACTIVE（同 OnboardingScenarioTest 惯例）。 */
    private TaContext registerTaWithTenant() {
        String token = registerAndLogin(uniquePhone(P_TA7), "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("RT查全号仓-" + SEQ.incrementAndGet());
        dto.setContactPhone(uniquePhone(P_TA7));
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply tenant").isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        com.cangchu.tenant.entity.Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        return new TaContext(token, tenantId);
    }

    private Long selfApplyAndApprove(String waToken, TaContext ta, String name) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("targetTenantId", ta.tenantId().toString());
        dto.put("name", name);
        dto.put("contactName", "联系人-" + SEQ.incrementAndGet());
        R<Map<String, Object>> applied = restTemplate.exchange(baseWaApply, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(waToken)), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).as("WA apply").isEqualTo(0);
        String applicationId = applied.getData().get("applicationId").toString();

        Map<String, Object> auditDto = new LinkedHashMap<>();
        auditDto.put("action", "APPROVED");
        auditDto.put("remark", "资质齐全");
        R<Map<String, Object>> approved = restTemplate.exchange(
                baseTaApps + "/" + applicationId + "/audit", HttpMethod.POST,
                new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).as("WA audit").isEqualTo(0);
        return Long.parseLong(approved.getData().get("wholesalerId").toString());
    }

    /** 造询价单（mapper-seed，沿用 PII 测试惯例），返回意向单 id。 */
    private Long seedInquiry(Long tenantId, Long wholesalerId, String rtPhone, String status) {
        InquiryRequest inq = new InquiryRequest();
        inq.setDocNo("RTREV-" + SEQ.incrementAndGet());
        inq.setStoreId(1L);
        inq.setTenantId(tenantId);
        inq.setWholesalerId(wholesalerId);
        inq.setRtPhoneHmac(piiCrypto.phoneHmac(rtPhone));
        inq.setRtPhoneCipher(piiCrypto.encrypt(rtPhone));
        inq.setStatus(status);
        inquiryRequestMapper.insert(inq);
        return inq.getId();
    }

    private R<Map<String, Object>> reveal(String token, String biz, Long id) {
        String url = baseReveal + "?biz=" + biz + "&id=" + id;
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), MAP).getBody();
    }

    /** 软删该批发商下的 WA 绑定（模拟商户无 ACTIVE 联系人）。 */
    private void deleteWaBindings(Long wholesalerId) {
        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getRole, "WA"));
        roles.forEach(r -> userRoleMapper.deleteById(r.getId()));
    }

    private static final int C_FORBIDDEN = ErrorCode.PII_REVEAL_FORBIDDEN.getCode();
    private static final int C_NOT_FOUND = ErrorCode.PII_REVEAL_TARGET_NOT_FOUND.getCode();

    private static final String BIZ = "RT_WHOLESALER";

    // ======================================================================
    // REV-07：本人 CONFIRMED / COMPLETED 意向单 → 200，取回该批发商 WA 全号
    // ======================================================================

    @Test
    @DisplayName("REV-07 RT_WHOLESALER：本人已确认/已完成意向单可取回批发商联系方式")
    void reveal_rtOwnerSettledInquiry_returnsWaPhone() {
        String rtOwner = rtSmsLogin(P_RT_OWNER);
        String waPhone = uniquePhone(P_WA7);
        String waToken = registerAndLogin(waPhone, "WaPass123", "WA");
        TaContext ta = registerTaWithTenant();
        Long wholesalerId = selfApplyAndApprove(waToken, ta, "联系商户-" + waPhone);

        Long confirmedId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_CONFIRMED);
        R<Map<String, Object>> ok = reveal(rtOwner, BIZ, confirmedId);
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).as("CONFIRMED 应可取回").isEqualTo(0);
        assertThat(ok.getData().get("phone")).isEqualTo(waPhone);

        Long completedId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_COMPLETED);
        R<Map<String, Object>> ok2 = reveal(rtOwner, BIZ, completedId);
        assertThat(ok2).isNotNull();
        assertThat(ok2.getCode()).as("COMPLETED 应可取回").isEqualTo(0);
        assertThat(ok2.getData().get("phone")).isEqualTo(waPhone);
    }

    // ======================================================================
    // REV-08：未确认（PENDING/VOIDED）→ 50402（确认后展示闸门）
    // ======================================================================

    @Test
    @DisplayName("REV-08 RT_WHOLESALER：PENDING/VOIDED 本人意向单 → 50402")
    void reveal_unSettled_denied() {
        String rtOwner = rtSmsLogin(P_RT_OWNER);
        String waToken = registerAndLogin(uniquePhone(P_WA7), "WaPass123", "WA");
        TaContext ta = registerTaWithTenant();
        Long wholesalerId = selfApplyAndApprove(waToken, ta, "未成交商户-" + SEQ.incrementAndGet());

        Long pendingId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_PENDING);
        assertThat(reveal(rtOwner, BIZ, pendingId).getCode()).isEqualTo(C_FORBIDDEN);

        Long voidedId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_VOIDED);
        assertThat(reveal(rtOwner, BIZ, voidedId).getCode()).isEqualTo(C_FORBIDDEN);
    }

    // ======================================================================
    // REV-09：非本人 RT / 无 RT 角色（WA）→ 50402
    // ======================================================================

    @Test
    @DisplayName("REV-09 RT_WHOLESALER：他人意向单（另一 RT）与无 RT 角色者 → 50402")
    void reveal_notOwner_denied() {
        String rtOwner = rtSmsLogin(P_RT_OWNER);
        String rtOther = rtSmsLogin(P_RT_OTHER);
        String waToken = registerAndLogin(uniquePhone(P_WA7), "WaPass123", "WA");
        TaContext ta = registerTaWithTenant();
        Long wholesalerId = selfApplyAndApprove(waToken, ta, "归属校验商户-" + SEQ.incrementAndGet());

        Long confirmedId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_CONFIRMED);

        // 另一 RT 登录（手机号 ≠ 询价提交手机号）→ 50402
        assertThat(reveal(rtOther, BIZ, confirmedId).getCode()).isEqualTo(C_FORBIDDEN);
        // 批发商本人（WA 角色，无 RT 角色）→ 50402
        assertThat(reveal(waToken, BIZ, confirmedId).getCode()).isEqualTo(C_FORBIDDEN);
        // 该批发商绑定的 WA 直接看别的商户也不可取（RT 角色闸门先拒）
        // 不存在对象 → 50401
        assertThat(reveal(rtOwner, BIZ, 99999999999L).getCode()).isEqualTo(C_NOT_FOUND);
    }

    // ======================================================================
    // REV-10：无 ACTIVE WA 绑定 → 50401（该商户暂无可联系联系人）
    // ======================================================================

    @Test
    @DisplayName("REV-10 RT_WHOLESALER：批发商无 ACTIVE WA 绑定 → 50401")
    void reveal_noActiveWa_notFound() {
        String rtOwner = rtSmsLogin(P_RT_OWNER);
        String waToken = registerAndLogin(uniquePhone(P_WA7), "WaPass123", "WA");
        TaContext ta = registerTaWithTenant();
        Long wholesalerId = selfApplyAndApprove(waToken, ta, "无联系人商户-" + SEQ.incrementAndGet());
        deleteWaBindings(wholesalerId);

        Long confirmedId = seedInquiry(ta.tenantId(), wholesalerId, P_RT_OWNER, InquiryRequest.STATUS_CONFIRMED);
        assertThat(reveal(rtOwner, BIZ, confirmedId).getCode()).isEqualTo(C_NOT_FOUND);
    }

    // ======================================================================
    // REV-11：匿名 → HTTP 401 + 41001
    // ======================================================================

    @Test
    @DisplayName("REV-11 RT_WHOLESALER：未登录访问查全号 → 401/41001")
    void reveal_anonymous_unauthorized() {
        String url = baseReveal + "?biz=" + BIZ + "&id=1";
        ResponseEntity<R<Map<String, Object>>> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.AUTH_BASIC_001.getCode());
    }
}
