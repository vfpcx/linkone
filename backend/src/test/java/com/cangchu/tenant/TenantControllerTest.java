package com.cangchu.tenant;

import cn.dev33.satoken.stp.StpUtil;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户模块测试（6 个用例）：申请 / 审核 / 代建 / 查设置 / 改设置 / 查容量
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseTenant;
    private String baseAdmin;

    private static String taToken;
    private static Long taTenantId;
    private static Long taUserId;
    private static Long opsUserId;

    @BeforeEach
    void setUp() {
        baseTenant = "http://localhost:" + port + "/api/v1/tenant";
        baseAdmin = "http://localhost:" + port + "/api/v1/admin/tenant";
    }

    /** 辅助：注册 TA 并登录 */
    private String registerAndLogin(String phone, String password) {
        String accountUrl = "http://localhost:" + port + "/api/v1/account";

        RegisterDto regDto = new RegisterDto();
        regDto.setPhone(phone);
        regDto.setPassword(password);
        regDto.setSmsCode("888888");
        regDto.setRole("TA");

        ResponseEntity<R<LoginVo>> regResp = restTemplate.exchange(
                accountUrl + "/register", HttpMethod.POST,
                new HttpEntity<>(regDto),
                new ParameterizedTypeReference<R<LoginVo>>() {});
        return regResp.getBody().getData().getToken();
    }

    /** 辅助：OPS token (use an existing user as OPS) */
    private String getOpsToken() {
        // Register an OPS-like user with TA role and use that (MVP mock OPS)
        return registerAndLogin("13800002000", "OpsAdmin1");
    }

    // ==================== Case 1: TA 自助注册仓库 ====================
    @Test
    @Order(1)
    @DisplayName("TA 自助注册仓库（待审核）")
    void testApply() {
        taToken = registerAndLogin("13800003001", "TaPass123");

        TenantApplyDto applyDto = new TenantApplyDto();
        applyDto.setName("杭州测试仓库");
        applyDto.setLegalName("杭州测试仓储有限公司");
        applyDto.setContactPhone("13800003001");
        applyDto.setAddressText("浙江省杭州市西湖区");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", taToken);
        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(applyDto, headers),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        Map<String, Object> data = response.getBody().getData();
        assertThat(data.get("tenantId")).isNotNull();
        assertThat(data.get("status")).isEqualTo("PENDING");

        taTenantId = Long.valueOf(data.get("tenantId").toString());
        taUserId = Long.valueOf(data.get("applicationId") != null
                ? data.get("applicationId").toString().substring(0, Math.min(data.get("applicationId").toString().length(), 18))
                : data.get("tenantId").toString());
    }

    // ==================== Case 2: OPS 审核入驻 ====================
    @Test
    @Order(2)
    @DisplayName("OPS 审核入驻通过")
    void testAudit() {
        String opsToken = getOpsToken();

        TenantAuditDto auditDto = new TenantAuditDto();
        auditDto.setAction("APPROVED");
        auditDto.setRemark("资质审核通过");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", opsToken);
        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseAdmin + "/" + taTenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(auditDto, headers),
                new ParameterizedTypeReference<R<Void>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
    }

    // ==================== Case 3: OPS 代建租户 ====================
    @Test
    @Order(3)
    @DisplayName("OPS 代建租户直接通过")
    void testCreateByOps() {
        String opsToken = getOpsToken();

        TenantCreateDto createDto = new TenantCreateDto();
        createDto.setName("OPS代建仓库");
        createDto.setContactPhone("13800004001");
        createDto.setAddressText("上海市浦东新区");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", opsToken);
        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseAdmin + "/create", HttpMethod.POST,
                new HttpEntity<>(createDto, headers),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        Map<String, Object> data = response.getBody().getData();
        assertThat(data.get("status")).isEqualTo("ACTIVE");
        assertThat(data.get("tenantId")).isNotNull();
    }

    // ==================== Case 4: 查当前 TA 的本店设置 ====================
    @Test
    @Order(4)
    @DisplayName("查当前 TA 的本店设置")
    void testGetMyStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", taToken);
        ResponseEntity<R<TenantDetailVo>> response = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<R<TenantDetailVo>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        TenantDetailVo data = response.getBody().getData();
        assertThat(data.getName()).isEqualTo("杭州测试仓库");
        assertThat(data.getBatchEnabled()).isEqualTo(0);
        assertThat(data.getPhotoMode()).isEqualTo("NONE");
    }

    // ==================== Case 5: 改店铺设置（5 开关 + lng/lat） ====================
    @Test
    @Order(5)
    @DisplayName("改店铺设置（含开关）")
    void testUpdateMyStore() {
        StoreSettingsDto dto = new StoreSettingsDto();
        dto.setName("杭州测试仓库（已更新）");
        dto.setBatchEnabled(1);
        dto.setPhotoMode("REQUIRED");
        dto.setBillingDim("PALLET");
        dto.setExpiryThresholdDays(45);
        dto.setLng(new java.math.BigDecimal("120.1552000"));
        dto.setLat(new java.math.BigDecimal("30.2741000"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", taToken);
        ResponseEntity<R<Void>> response = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.PUT,
                new HttpEntity<>(dto, headers),
                new ParameterizedTypeReference<R<Void>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);

        // 验证更新结果
        ResponseEntity<R<TenantDetailVo>> getResp = restTemplate.exchange(
                baseTenant + "/me", HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<R<TenantDetailVo>>() {});
        TenantDetailVo updated = getResp.getBody().getData();
        assertThat(updated.getBatchEnabled()).isEqualTo(1);
        assertThat(updated.getPhotoMode()).isEqualTo("REQUIRED");
        assertThat(updated.getBillingDim()).isEqualTo("PALLET");
        assertThat(updated.getExpiryThresholdDays()).isEqualTo(45);
    }

    // ==================== Case 6: 查实时容量 ====================
    @Test
    @Order(6)
    @DisplayName("查实时容量（快照）")
    void testGetCapacity() {
        ResponseEntity<R<CapacityVo>> response = restTemplate.exchange(
                baseTenant + "/capacity?tenantId=" + taTenantId, HttpMethod.GET,
                null,
                new ParameterizedTypeReference<R<CapacityVo>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        CapacityVo data = response.getBody().getData();
        assertThat(data.getTenantId()).isEqualTo(taTenantId);
        assertThat(data.getPrecision()).isEqualTo("TIER");
        assertThat(data.getTierLabel()).isIn("空闲", "余量充足", "余量适中", "余量紧张", "接近满仓");
    }

    // ==================== Case 7: 店铺码 ====================
    @Test
    @Order(7)
    @DisplayName("生成店铺码")
    void testStoreQr() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", taToken);
        ResponseEntity<R<Map<String, String>>> response = restTemplate.exchange(
                baseTenant + "/store-qr", HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<R<Map<String, String>>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        assertThat(response.getBody().getData().get("qrUrl")).isNotBlank();
        assertThat(response.getBody().getData().get("tenantSimpleCode")).isNotBlank();
    }

    // ==================== Case 8: 生成员工邀请码 ====================
    @Test
    @Order(8)
    @DisplayName("生成员工注册码")
    void testInviteCode() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", taToken);
        ResponseEntity<R<Map<String, Object>>> response = restTemplate.exchange(
                baseTenant + "/invite-code?targetRole=WK&maxUses=5&expireDays=30",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<R<Map<String, Object>>>() {});

        assertThat(response.getBody().getCode()).isEqualTo(0);
        assertThat(response.getBody().getData().get("code")).isNotNull();
    }
}
