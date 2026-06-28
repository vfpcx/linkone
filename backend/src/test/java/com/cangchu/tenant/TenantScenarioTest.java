package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant 模块场景测试（S2 非法输入 / S4 越权 / S5 状态机 / S6 幂等）。
 *
 * <p>测试基建说明：沿用 {@link TenantControllerTest} 风格
 * （@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 + mock 短信码 888888）。未引入 RestAssured。
 *
 * <p>错误码契约（grep 源码确认）：
 * <ul>
 *   <li>@Valid 校验失败统一 40001；TenantApplyDto 仅 name/contactPhone 有 @NotBlank，lng/lat 无范围校验。</li>
 *   <li>未登录调 /api/v1/tenant/** → SaInterceptor checkLogin → 41001（HTTP 401）。</li>
 *   <li>租户不存在 / 未绑定租户 → 50210（TENANT_NOT_FOUND）。</li>
 *   <li>admin/audit、admin/create 在服务层<strong>无 OPS 角色校验</strong>（仅 checkLogin），
 *       audit 无<strong>状态机校验</strong>，updateMyStore 无<strong>已审核校验</strong>
 *       ⇒ 多条断言预计暴露后端缺陷，详见报告。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String PHONE_PREFIX_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String PHONE_PREFIX_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseTenant;
    private String baseAdmin;
    private String baseAccount;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseAdmin = "http://localhost:" + port + "/api/v1/admin/tenant";
        baseAccount = "http://localhost:" + port + "/api/v1/account";
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Object>> OBJ = new ParameterizedTypeReference<>() {};

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
        R<LoginVo> body = restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData().getToken();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + 申请仓库（PENDING） */
    private TaContext registerTa() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("场景仓库-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply %s", phone).isEqualTo(0);
        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, token, tenantId);
    }

    private String registerOps() {
        return registerAndLogin(uniquePhone(PHONE_PREFIX_OPS), "OpsPass123", "OPS");
    }

    private R<Void> audit(String token, Long tenantId, String action) {
        TenantAuditDto dto = new TenantAuditDto();
        dto.setAction(action);
        dto.setRemark("auto");
        return restTemplate.exchange(baseAdmin + "/" + tenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), VOID).getBody();
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("TN-S2-01 申请缺仓库名 name → 40001 校验失败")
    void tnS2_01_missingName() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();   // name 为 null → @NotBlank
        dto.setContactPhone(phone);
        dto.setAddressText("某地址");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("TN-S2-01b 申请缺联系手机号 contactPhone → 40001 校验失败")
    void tnS2_01b_missingContactPhone() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("缺手机号仓库");           // contactPhone 为 null → @NotBlank
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("TN-S2-02 经纬度越界(lat=200) → 期望校验失败(实现无范围校验，预计暴露缺陷)")
    void tnS2_02_lngLatOutOfRange() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("越界经纬度仓库-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("某地址");
        dto.setLng(new BigDecimal("300"));    // 合法范围 [-180,180]
        dto.setLat(new BigDecimal("200"));    // 合法范围 [-90,90]
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        // 文档期望：经纬度越界应被拒（40001）。实现：TenantApplyDto.lng/lat 无 @DecimalMin/@DecimalMax
        // ⇒ 此断言预计 FAIL，作为缺陷保留正确预期。
        assertThat(body.getCode())
                .as("经纬度越界应被拒绝；若返回 0 说明缺范围校验（缺陷）")
                .isEqualTo(40001);
    }

    // ======================================================================
    // S4 越权与鉴权
    // ======================================================================

    @Test
    @DisplayName("TN-S4-01 非 OPS(TA) 调 /admin/tenant/*/audit → 期望被拒(实现无角色校验，预计暴露缺陷)")
    void tnS4_01_nonOpsAudit() {
        TaContext victim = registerTa();          // 待审核租户
        // 另一个普通 TA（非 OPS）尝试审核
        String attackerTaToken = registerAndLogin(uniquePhone(PHONE_PREFIX_TA), "TaPass123", "TA");

        R<Void> body = audit(attackerTaToken, victim.tenantId, "APPROVED");
        assertThat(body).isNotNull();
        // 文档期望：非 OPS 角色应被拒（42001/42002）。实现：audit() 仅 StpUtil.getLoginIdAsLong()，
        // 无 checkRole("OPS") ⇒ 任意登录用户可越权审核 ⇒ 此断言预计 FAIL，缺陷保留正确预期。
        assertThat(body.getCode())
                .as("非 OPS 调用审核应被拒绝；若返回 0 说明 admin 接口缺角色鉴权（高危缺陷）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("TN-S4-02 TA 跨租户查别家(用别家 tenantId 调 capacity) → 数据隔离/拒绝")
    void tnS4_02_crossTenantQuery() {
        TaContext a = registerTa();
        TaContext b = registerTa();
        // capacity 是公开接口（按 PRD 容量公示），仅校验“拿到的是自己请求的 tenantId 的数据，不串号”。
        ResponseEntity<R<Object>> resp = restTemplate.exchange(
                baseTenant + "/capacity?tenantId=" + b.tenantId, HttpMethod.GET,
                new HttpEntity<>(bearer(a.token)), OBJ);
        assertThat(resp.getBody()).isNotNull();
        if (resp.getBody().getCode() == 0) {
            // 公开容量查询：返回的应是被查 tenant b 的数据，不应泄漏/串成 a 的私有设置
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
            assertThat(data.get("tenantId").toString())
                    .as("跨 tenant 查询不得串号")
                    .isEqualTo(b.tenantId.toString());
        }
        // /tenant/me 不接受外部 tenantId 入参，TA 只能取到自己绑定的租户，结构上无跨租户读取面。
    }

    @Test
    @DisplayName("TN-S4-03 无 token 调 /tenant/me → 41001 未登录")
    void tnS4_03_noTokenGetMyStore() {
        ResponseEntity<R<Object>> resp = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.GET, HttpEntity.EMPTY, OBJ);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(41001);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ======================================================================
    // S5 状态机非法转移
    // ======================================================================

    @Test
    @DisplayName("TN-S5-01 重复审核已通过(ACTIVE)的租户 → 期望被拒(实现无状态机校验，预计暴露缺陷)")
    void tnS5_01_reAuditApproved() {
        TaContext ta = registerTa();
        String opsToken = registerOps();
        assertThat(audit(opsToken, ta.tenantId, "APPROVED").getCode()).isEqualTo(0);   // 首次通过

        R<Void> again = audit(opsToken, ta.tenantId, "APPROVED");                      // 再次审核
        assertThat(again).isNotNull();
        // 文档期望：已通过(ACTIVE)不应可再审核（状态机拒绝）。实现：audit() 不校验当前 status
        // ⇒ 此断言预计 FAIL，缺陷保留正确预期。
        assertThat(again.getCode())
                .as("已通过租户重复审核应被状态机拒绝；若返回 0 说明缺状态机守卫（缺陷）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("TN-S5-02 审核已驳回(REJECTED)租户后再操作 → 期望被拒(实现无状态机校验，预计暴露缺陷)")
    void tnS5_02_reAuditRejected() {
        TaContext ta = registerTa();
        String opsToken = registerOps();
        assertThat(audit(opsToken, ta.tenantId, "REJECTED").getCode()).isEqualTo(0);   // 先驳回

        R<Void> again = audit(opsToken, ta.tenantId, "APPROVED");                      // 驳回后再通过
        assertThat(again).isNotNull();
        assertThat(again.getCode())
                .as("已驳回租户再操作应被状态机拒绝；若返回 0 说明缺状态机守卫（缺陷）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("TN-S5-03 未审核(PENDING)就改店铺设置 → 期望被拒(实现无已审核校验，预计暴露缺陷)")
    void tnS5_03_updateStoreWhileUnaudited() {
        TaContext ta = registerTa();   // 未审核，租户 PENDING

        StoreSettingsDto dto = new StoreSettingsDto();
        dto.setName("未审核就改名-" + ta.phone);
        dto.setBatchEnabled(1);
        R<Void> body = restTemplate.exchange(baseTenant + "/me", HttpMethod.PUT,
                new HttpEntity<>(dto, bearer(ta.token)), VOID).getBody();
        assertThat(body).isNotNull();
        // 文档期望：未审核不可改店铺设置（50101 审核中 / 拒绝）。实现：updateMyStore 不校验租户状态
        // ⇒ 此断言预计 FAIL，缺陷保留正确预期。
        assertThat(body.getCode())
                .as("未审核就改店铺设置应被拒绝；若返回 0 说明缺审核态守卫（缺陷）")
                .isNotEqualTo(0);
    }

    // ======================================================================
    // S6 幂等
    // ======================================================================

    @Test
    @DisplayName("TN-S6-01 同名仓库重复注册 → 期望被拒(实现无唯一性校验，预计暴露缺陷)")
    void tnS6_01_duplicateWarehouseName() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");
        String dupName = "唯一性仓库名-" + phone;

        TenantApplyDto dto1 = new TenantApplyDto();
        dto1.setName(dupName);
        dto1.setContactPhone(phone);
        dto1.setAddressText("地址1");
        R<Map<String, Object>> first = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto1, bearer(token)), MAP).getBody();
        assertThat(first).isNotNull();
        assertThat(first.getCode()).isEqualTo(0);

        TenantApplyDto dto2 = new TenantApplyDto();
        dto2.setName(dupName);            // 同名再申请
        dto2.setContactPhone(phone);
        dto2.setAddressText("地址2");
        R<Map<String, Object>> second = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto2, bearer(token)), MAP).getBody();
        assertThat(second).isNotNull();
        // 文档期望：同名仓库重复注册应被拒。实现：apply() 无同名/同 TA 已有租户校验，每次新建
        // ⇒ 此断言预计 FAIL，缺陷保留正确预期。
        assertThat(second.getCode())
                .as("同名仓库重复注册应被拒绝；若返回 0 说明缺唯一性/重复申请校验（缺陷）")
                .isNotEqualTo(0);
    }

    @Test
    @DisplayName("TN-S6-02 重复生成店铺码 → 幂等返回同码")
    void tnS6_02_storeQrIdempotent() {
        TaContext ta = registerTa();
        String opsToken = registerOps();
        assertThat(audit(opsToken, ta.tenantId, "APPROVED").getCode()).isEqualTo(0);

        ParameterizedTypeReference<R<Map<String, String>>> qrType = new ParameterizedTypeReference<>() {};
        R<Map<String, String>> first = restTemplate.exchange(baseTenant + "/store-qr", HttpMethod.POST,
                new HttpEntity<>(bearer(ta.token)), qrType).getBody();
        R<Map<String, String>> second = restTemplate.exchange(baseTenant + "/store-qr", HttpMethod.POST,
                new HttpEntity<>(bearer(ta.token)), qrType).getBody();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getCode()).isEqualTo(0);
        assertThat(second.getCode()).isEqualTo(0);
        // 店铺码基于固定 tenantSimpleCode 生成 ⇒ 两次应一致（幂等）
        assertThat(second.getData().get("tenantSimpleCode"))
                .isEqualTo(first.getData().get("tenantSimpleCode"));
        assertThat(second.getData().get("qrUrl"))
                .isEqualTo(first.getData().get("qrUrl"));
    }
}
