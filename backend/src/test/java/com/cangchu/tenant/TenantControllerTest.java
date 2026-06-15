package com.cangchu.tenant;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.*;
import com.cangchu.tenant.vo.CapacityVo;
import com.cangchu.tenant.vo.TenantDetailVo;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户模块集成测试：申请 / 审核 / 代建 / 查设置 / 改设置 / 查容量 / 店铺码 / 邀请码
 *
 * 每个 @Test 独立 setUp，不依赖 @Order 副作用：
 *  - 用唯一手机号（基于类加载时间戳 + 自增 seq）避免 PHONE_DUPLICATE
 *  - OPS 操作真正注册 role="OPS" 用户
 *  - 需要租户/审核后状态的 case 内部自己跑完整链路
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // 中国大陆手机号正则: ^1[3-9]\d{9}$，必须 11 位
    // 结构：1 + [3-9] + 5 位 JVM 时间戳尾段 + 4 位 case 自增 = 11 位
    // 不同前缀（13xxx vs 15xxx）让 TA / OPS 互不冲突
    private static final String PHONE_PREFIX_TA  = "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String PHONE_PREFIX_OPS = "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
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

    /** 每次调用拿一个唯一 11 位手机号 */
    private String uniquePhone(String prefix) {
        long n = SEQ.incrementAndGet();
        return prefix + String.format("%04d", n % 10000);
    }

    /** 注册并返回 token，role 可指定 TA / OPS / ... */
    private String registerAndLogin(String phone, String password, String role) {
        RegisterDto regDto = new RegisterDto();
        regDto.setPhone(phone);
        regDto.setPassword(password);
        regDto.setSmsCode("888888");
        regDto.setRole(role);

        ResponseEntity<R<LoginVo>> regResp = restTemplate.exchange(
                baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(regDto),
                new ParameterizedTypeReference<R<LoginVo>>() {});
        R<LoginVo> body = regResp.getBody();
        assertThat(body).as("register response for %s", phone).isNotNull();
        assertThat(body.getCode()).as("register code for %s", phone).isEqualTo(0);
        assertThat(body.getData()).as("register data for %s", phone).isNotNull();
        return body.getData().getToken();
    }

    /** 注册 TA + 申请仓库，返回上下文 */
    private TaContext registerTa() {
        String phone = uniquePhone(PHONE_PREFIX_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA");

        TenantApplyDto applyDto = new TenantApplyDto();
        applyDto.setName("测试仓库-" + phone);
        applyDto.setLegalName("测试仓储有限公司-" + phone);
        applyDto.setContactPhone(phone);
        applyDto.setAddressText("浙江省杭州市西湖区");

        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(applyDto, bearer(token)),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});
        R<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("apply code for %s", phone).isEqualTo(0);

        Long tenantId = Long.valueOf(body.getData().get("tenantId").toString());
        return new TaContext(phone, token, tenantId);
    }

    /** 注册 OPS 用户 + 拿 token */
    private String registerOpsAndLogin() {
        String phone = uniquePhone(PHONE_PREFIX_OPS);
        return registerAndLogin(phone, "OpsPass123", "OPS");
    }

    /** TA 注册 + 申请 + OPS 审核通过 */
    private TaContext registerTaApprovedTenant() {
        TaContext ta = registerTa();
        String opsToken = registerOpsAndLogin();
        approveTenant(opsToken, ta.tenantId);
        return ta;
    }

    /** OPS 审核通过指定 tenant */
    private void approveTenant(String opsToken, Long tenantId) {
        TenantAuditDto auditDto = new TenantAuditDto();
        auditDto.setAction("APPROVED");
        auditDto.setRemark("审核通过");

        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseAdmin + "/" + tenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(auditDto, bearer(opsToken)),
                new ParameterizedTypeReference<R<Void>>() {});
        R<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("approve tenant %s", tenantId).isEqualTo(0);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        return headers;
    }

    /** TA 测试上下文 */
    private record TaContext(String phone, String token, Long tenantId) {}

    // ==================== Case 1: TA 自助注册仓库 ====================
    @Test
    @DisplayName("TA 自助注册仓库（待审核）")
    void testApply() {
        TaContext ta = registerTa();
        assertThat(ta.tenantId).isNotNull();
    }

    // ==================== Case 2: OPS 审核入驻通过 ====================
    @Test
    @DisplayName("OPS 审核入驻通过")
    void testAudit() {
        TaContext ta = registerTa();
        String opsToken = registerOpsAndLogin();
        approveTenant(opsToken, ta.tenantId);  // 内部已 assert code == 0
    }

    // ==================== Case 3: OPS 代建租户 ====================
    @Test
    @DisplayName("OPS 代建租户直接通过")
    void testCreateByOps() {
        String opsToken = registerOpsAndLogin();
        String taPhone = uniquePhone(PHONE_PREFIX_TA);

        TenantCreateDto createDto = new TenantCreateDto();
        createDto.setName("OPS代建仓库-" + taPhone);
        createDto.setContactPhone(taPhone);
        createDto.setAddressText("上海市浦东新区");

        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseAdmin + "/create", HttpMethod.POST,
                new HttpEntity<>(createDto, bearer(opsToken)),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});
        R<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        Map<String, Object> data = body.getData();
        assertThat(data.get("status")).isEqualTo("ACTIVE");
        assertThat(data.get("tenantId")).isNotNull();
    }

    // ==================== Case 4: 查当前 TA 的本店设置 ====================
    @Test
    @DisplayName("查当前 TA 的本店设置")
    void testGetMyStore() {
        TaContext ta = registerTaApprovedTenant();

        ResponseEntity<R<TenantDetailVo>> response = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token)),
                new ParameterizedTypeReference<R<TenantDetailVo>>() {});
        R<TenantDetailVo> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        TenantDetailVo data = body.getData();
        assertThat(data).isNotNull();
        assertThat(data.getName()).startsWith("测试仓库-" + ta.phone);
        assertThat(data.getBatchEnabled()).isEqualTo(0);
        assertThat(data.getPhotoMode()).isEqualTo("NONE");
    }

    // ==================== Case 5: 改店铺设置（5 开关 + lng/lat） ====================
    @Test
    @DisplayName("改店铺设置（含开关）")
    void testUpdateMyStore() {
        TaContext ta = registerTaApprovedTenant();

        StoreSettingsDto dto = new StoreSettingsDto();
        dto.setName("已更新的仓库-" + ta.phone);
        dto.setBatchEnabled(1);
        dto.setPhotoMode("REQUIRED");
        dto.setBillingDim("PALLET");
        dto.setExpiryThresholdDays(45);
        dto.setLng(new java.math.BigDecimal("120.1552000"));
        dto.setLat(new java.math.BigDecimal("30.2741000"));

        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.PUT,
                new HttpEntity<>(dto, bearer(ta.token)),
                new ParameterizedTypeReference<R<Void>>() {});
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(0);

        // 验证更新结果
        ResponseEntity<R<TenantDetailVo>> getResp = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token)),
                new ParameterizedTypeReference<R<TenantDetailVo>>() {});
        TenantDetailVo updated = getResp.getBody().getData();
        assertThat(updated.getBatchEnabled()).isEqualTo(1);
        assertThat(updated.getPhotoMode()).isEqualTo("REQUIRED");
        assertThat(updated.getBillingDim()).isEqualTo("PALLET");
        assertThat(updated.getExpiryThresholdDays()).isEqualTo(45);
    }

    // ==================== Case 6: 查实时容量 ====================
    @Test
    @DisplayName("查实时容量（快照）")
    void testGetCapacity() {
        TaContext ta = registerTaApprovedTenant();

        // 容量查询是公开接口，不需要鉴权
        ResponseEntity<R<CapacityVo>> response = restTemplate.exchange(
                baseTenant + "/capacity?tenantId=" + ta.tenantId, HttpMethod.GET,
                null,
                new ParameterizedTypeReference<R<CapacityVo>>() {});
        R<CapacityVo> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);

        CapacityVo data = body.getData();
        assertThat(data.getTenantId()).isEqualTo(ta.tenantId);
        assertThat(data.getPrecision()).isEqualTo("TIER");
        assertThat(data.getTierLabel()).isIn("空闲", "余量充足", "余量适中", "余量紧张", "接近满仓");
    }

    // ==================== Case 7: 店铺码 ====================
    @Test
    @DisplayName("生成店铺码")
    void testStoreQr() {
        TaContext ta = registerTaApprovedTenant();

        ResponseEntity<R<Map<String, String>>> response = restTemplate.exchange(
                baseTenant + "/store-qr", HttpMethod.POST,
                new HttpEntity<>(bearer(ta.token)),
                new ParameterizedTypeReference<R<Map<String, String>>>() {});
        R<Map<String, String>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("qrUrl")).isNotBlank();
        assertThat(body.getData().get("tenantSimpleCode")).isNotBlank();
    }

    // ==================== Case 8: 生成员工邀请码 ====================
    @Test
    @DisplayName("生成员工注册码")
    void testInviteCode() {
        TaContext ta = registerTaApprovedTenant();

        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseTenant + "/invite-code?targetRole=WK&maxUses=5&expireDays=30",
                HttpMethod.POST,
                new HttpEntity<>(bearer(ta.token)),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});
        R<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().get("code")).isNotNull();
    }
}
