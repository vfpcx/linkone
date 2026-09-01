package com.cangchu.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.TestUniq;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.response.R;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.Tenant;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 入驻生态 Wave6 缺陷修复场景测试（07-onboarding-e2e-report §5）：
 * <ul>
 *   <li>DEF-3：WA 直申审批通过后，注册占位 WA 角色行被就地升级/清理——
 *       一个用户对同一商户只有一条有效 WA 角色，登录不再出现重复工作空间。</li>
 *   <li>DEF-6：GET /ops/blacklist 分页（PageRecords 契约 records/total/page/size）+ keyword 搜索。</li>
 *   <li>DEF-1：GET /tenants/directory 公开租户目录（仅 ACTIVE 的 id+name，limit 上限 20，IP 限流）。</li>
 * </ul>
 * 基建沿用 {@link OnboardingScenarioTest}（RANDOM_PORT + TestRestTemplate + H2 + mock 888888）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Wave6DefectFixScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private com.cangchu.tenant.mapper.TenantMapper tenantMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private com.cangchu.common.util.SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private PiiCrypto piiCrypto;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_OPS =
            "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseTaApps;
    private String baseOpsBlacklist;
    private String baseDirectory;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseTenant = base + "/api/v1/tenant";
        baseTaApps = base + "/api/v1/tenant/wholesaler-applications";
        baseOpsBlacklist = base + "/api/v1/ops/blacklist";
        baseDirectory = base + "/api/v1/tenants/directory";
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

    private R<LoginVo> register(String phone, String password, String role,
                                String targetTenantId, String wholesalerName) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        if (targetTenantId != null) dto.setTargetTenantId(targetTenantId);
        if (wholesalerName != null) dto.setWholesalerName(wholesalerName);
        return restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private record TaContext(String phone, String token, Long tenantId) {}

    /** 注册 TA + apply 建仓 + 直接置 ACTIVE（同 OnboardingScenarioTest 惯例）。 */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        R<LoginVo> reg = register(phone, "TaPass123", "TA", null, null);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        String token = reg.getData().getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("W6仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("浙江省杭州市西湖区");
        R<Map<String, Object>> body = restTemplate.exchange(baseTenant + "/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        return new TaContext(phone, token, tenantId);
    }

    private String registerOps() {
        R<LoginVo> reg = register(uniquePhone(P_OPS), "OpsPass123", "OPS", null, null);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        return reg.getData().getToken();
    }

    /** TA 审批通过某申请单（按商户名在 PENDING 列表定位），返回 wholesalerId。 */
    private String approveByName(TaContext ta, String wholesalerName) {
        R<Map<String, Object>> listBody = restTemplate.exchange(
                baseTaApps + "?page=1&size=50&status=PENDING", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(listBody).isNotNull();
        assertThat(listBody.getCode()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) listBody.getData().get("records");
        String appId = recs.stream().filter(m -> wholesalerName.equals(m.get("name")))
                .findFirst().orElseThrow().get("id").toString();
        Map<String, Object> audit = Map.of("action", "APPROVED", "remark", "Wave6 审批通过");
        R<Map<String, Object>> approved = restTemplate.exchange(baseTaApps + "/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(audit, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        assertThat(approved.getData().get("wholesalerId")).isNotNull();
        return approved.getData().get("wholesalerId").toString();
    }

    private List<UserRole> activeWaRows(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "WA")
                .eq(UserRole::getStatus, "ACTIVE"));
    }

    // ======================================================================
    // DEF-3 直申→审批通过→WA 角色行唯一
    // ======================================================================

    @Test
    @DisplayName("DEF-3a 注册直申→审批通过→占位行被就地升级：该用户恰一条 WA 角色且绑定正确，登录无重复空间")
    void def3_directApplyApproveMergesPlaceholder() {
        TaContext ta = registerTaWithTenant();
        String phone = uniquePhone(P_WA);
        String name = "直申商户-" + phone;

        // 注册直申：register 落占位 WA 行 + 自动建 PENDING 申请单
        R<LoginVo> reg = register(phone, "WaPass123", "WA", ta.tenantId().toString(), name);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        Long waUserId = reg.getData().getUserId();
        assertThat(activeWaRows(waUserId)).as("审批前仅注册占位一行").hasSize(1);

        // TA 审批通过
        String wholesalerId = approveByName(ta, name);

        // 根治断言：该用户 WA 角色行唯一，且绑定 tenantId + wholesalerId
        List<UserRole> rows = activeWaRows(waUserId);
        assertThat(rows).as("审批通过后 WA 角色行必须唯一（DEF-3 根治）").hasSize(1);
        UserRole bound = rows.get(0);
        assertThat(bound.getTenantId()).isEqualTo(ta.tenantId());
        assertThat(bound.getWholesalerId()).isEqualTo(Long.valueOf(wholesalerId));

        // 登录视角：roles 中恰一条 WA，且带 wholesalerId（前端不再弹重复工作空间）
        Map<String, Object> login = Map.of("phone", phone, "password", "WaPass123");
        R<LoginVo> relogin = restTemplate.exchange(baseAccount + "/login", HttpMethod.POST,
                new HttpEntity<>(login), LOGIN_VO).getBody();
        assertThat(relogin).isNotNull();
        assertThat(relogin.getCode()).isEqualTo(0);
        List<LoginVo.RoleInfo> waRoles = relogin.getData().getRoles().stream()
                .filter(r -> "WA".equals(r.getRole())).toList();
        assertThat(waRoles).as("登录 roles 中 WA 条目唯一").hasSize(1);
        assertThat(waRoles.get(0).getWholesalerId()).isEqualTo(Long.valueOf(wholesalerId));
        assertThat(relogin.getData().getPrimaryRole()).isEqualTo("WA");
    }

    @Test
    @DisplayName("DEF-3b 存量脏数据兼容：绑定行已在时再次 ensureWholesalerRole，残留占位行被清理且 id 不变")
    void def3_replayCleansLegacyPlaceholder() {
        TaContext ta = registerTaWithTenant();
        String phone = uniquePhone(P_WA);
        String name = "存量商户-" + phone;
        R<LoginVo> reg = register(phone, "WaPass123", "WA", ta.tenantId().toString(), name);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        Long waUserId = reg.getData().getUserId();
        String wholesalerId = approveByName(ta, name);
        Long boundRoleId = activeWaRows(waUserId).get(0).getId();

        // 人工回放存量脏数据：补插一条注册占位行（模拟修复前已产生的双行账号）
        UserRole legacy = new UserRole();
        legacy.setId(snowflakeIdUtil.nextId());
        legacy.setUserId(waUserId);
        legacy.setRole("WA");
        legacy.setStatus("ACTIVE");
        legacy.setPriority(40);
        legacy.setCreatedBy(waUserId);
        userRoleMapper.insert(legacy);
        assertThat(activeWaRows(waUserId)).hasSize(2);

        // 幂等重放绑定：返回既有绑定行 id，同时清掉占位行（兼容逻辑）
        Long ensured = authService.ensureWholesalerRole(waUserId, "WA", ta.tenantId(),
                Long.valueOf(wholesalerId), waUserId);
        assertThat(ensured).isEqualTo(boundRoleId);
        List<UserRole> rows = activeWaRows(waUserId);
        assertThat(rows).as("兼容路径清理后 WA 行唯一").hasSize(1);
        assertThat(rows.get(0).getWholesalerId()).isEqualTo(Long.valueOf(wholesalerId));
    }

    // ======================================================================
    // DEF-6 黑名单分页 + keyword 搜索
    // ======================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blacklistRecords(R<Map<String, Object>> body) {
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        return (List<Map<String, Object>>) body.getData().get("records");
    }

    @Test
    @DisplayName("DEF-6 黑名单列表分页（PageRecords 契约）+ keyword 匹配手机号/执照号")
    void def6_blacklistPagingAndKeyword() {
        String ops = registerOps();
        // 平台级共享表：用唯一前缀隔离本用例数据，保证 total 断言确定性
        String prefix = "199" + uniquePhone("").substring(0, 4) + String.format("%03d", SEQ.incrementAndGet() % 1000);
        // 执照号共用唯一 token（不含 prefix），供分页圈选；W8（16 §1.5）后 LICENSE_NO 行保留明文，
        // LIKE 子串检索语义不变（PHONE 行 target_value 已收敛为摘要，不再支持 10 位前缀 LIKE）
        String licToken = "W6L" + prefix.substring(3);
        String license1 = "91" + licToken + "1IC";
        String phone = prefix + "2";
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> dto = Map.of("targetType", "LICENSE_NO",
                    "targetValue", "91" + licToken + i + "IC", "reason", "Wave6 分页测试" + i);
            R<Map<String, Object>> added = restTemplate.exchange(baseOpsBlacklist, HttpMethod.POST,
                    new HttpEntity<>(dto, bearer(ops)), MAP).getBody();
            assertThat(added).isNotNull();
            assertThat(added.getCode()).isEqualTo(0);
        }
        Map<String, Object> phDto = Map.of("targetType", "PHONE",
                "targetValue", phone, "reason", "Wave6 手机号精确检索测试");
        assertThat(restTemplate.exchange(baseOpsBlacklist, HttpMethod.POST,
                new HttpEntity<>(phDto, bearer(ops)), MAP).getBody().getCode()).isEqualTo(0);

        // 分页契约：records/total/page/size；size=2 → 第 1 页 2 条、第 2 页 1 条
        R<Map<String, Object>> page1 = restTemplate.exchange(
                baseOpsBlacklist + "?page=1&size=2&status=ACTIVE&keyword=" + licToken,
                HttpMethod.GET, new HttpEntity<>(bearer(ops)), MAP).getBody();
        List<Map<String, Object>> recs1 = blacklistRecords(page1);
        assertThat(page1.getData().keySet()).containsExactly("records", "total", "page", "size");
        assertThat(recs1).hasSize(2);
        assertThat(Long.parseLong(page1.getData().get("total").toString())).isEqualTo(3);
        assertThat(Long.parseLong(page1.getData().get("page").toString())).isEqualTo(1);
        assertThat(Long.parseLong(page1.getData().get("size").toString())).isEqualTo(2);

        R<Map<String, Object>> page2 = restTemplate.exchange(
                baseOpsBlacklist + "?page=2&size=2&status=ACTIVE&keyword=" + licToken,
                HttpMethod.GET, new HttpEntity<>(bearer(ops)), MAP).getBody();
        assertThat(blacklistRecords(page2)).hasSize(1);

        // keyword 完整 11 位手机号 → hmac 精确命中（W8：V34 后 PHONE 行 target_value 为摘要）
        R<Map<String, Object>> byPhone = restTemplate.exchange(
                baseOpsBlacklist + "?page=1&size=10&keyword=" + phone,
                HttpMethod.GET, new HttpEntity<>(bearer(ops)), MAP).getBody();
        List<Map<String, Object>> phoneRecs = blacklistRecords(byPhone);
        assertThat(phoneRecs).hasSize(1);
        assertThat(phoneRecs.get(0).get("targetValue")).isEqualTo("PHONE_****" + phone.substring(7));

        // keyword 匹配执照号子串
        R<Map<String, Object>> byLic = restTemplate.exchange(
                baseOpsBlacklist + "?page=1&size=10&keyword=" + licToken + "1",
                HttpMethod.GET, new HttpEntity<>(bearer(ops)), MAP).getBody();
        List<Map<String, Object>> licRecs = blacklistRecords(byLic);
        assertThat(licRecs).hasSize(1);
        assertThat(licRecs.get(0).get("targetType")).isEqualTo("LICENSE_NO");
        assertThat(licRecs.get(0).get("targetValue")).isEqualTo(license1);

        // 非 OPS 仍拒（越权面不因签名变化回归）
        TaContext ta = registerTaWithTenant();
        R<Map<String, Object>> denied = restTemplate.exchange(
                baseOpsBlacklist + "?page=1&size=10", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).isEqualTo(42002);
    }

    // ======================================================================
    // DEF-1 公开租户目录 /api/v1/tenants/directory
    // ======================================================================

    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> DIR_LIST =
            new ParameterizedTypeReference<>() {};

    /** 直插租户行（绕开注册链路，直接控制 status，供目录端点断言）。 */
    private Tenant insertTenant(String name, String status) {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName(name);
        t.setContactUserId(1L);
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        t.setStatus(status);
        tenantMapper.insert(t);
        return t;
    }

    @Test
    @DisplayName("DEF-1a 目录匿名可访问：仅 ACTIVE 租户、仅 id+name 两字段（无手机号等敏感字段）")
    void def1_directoryAnonymousActiveOnly() {
        String token = "W6DIRA" + uniquePhone("");
        Tenant active = insertTenant(token + "-生效仓", "ACTIVE");
        insertTenant(token + "-待审仓", "PENDING");

        // 匿名（无 Authorization 头）访问
        R<List<Map<String, Object>>> body = restTemplate.exchange(
                baseDirectory + "?keyword=" + token, HttpMethod.GET,
                HttpEntity.EMPTY, DIR_LIST).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("目录端点须匿名可访问").isEqualTo(0);
        List<Map<String, Object>> items = body.getData();
        assertThat(items).as("仅 ACTIVE 租户可见（PENDING 不泄漏存在性）").hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item.get("id")).isEqualTo(active.getId().toString());
        assertThat(item.get("name")).isEqualTo(token + "-生效仓");
        // 防枚举收敛：DTO 恰好 id+name 两字段，严禁 contactPhone/licenseNo 等敏感字段
        assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name");
    }

    @Test
    @DisplayName("DEF-1b limit 钳制：请求 limit=100 至多返回 20 条")
    void def1_directoryLimitCapped() {
        String token = "W6DIRB" + uniquePhone("");
        for (int i = 0; i < 25; i++) {
            insertTenant(token + "-仓" + String.format("%02d", i), "ACTIVE");
        }
        R<List<Map<String, Object>>> body = restTemplate.exchange(
                baseDirectory + "?keyword=" + token + "&limit=100", HttpMethod.GET,
                HttpEntity.EMPTY, DIR_LIST).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData()).as("limit 上限 20（防批量枚举）").hasSize(20);

        // 默认 limit=10
        R<List<Map<String, Object>>> dft = restTemplate.exchange(
                baseDirectory + "?keyword=" + token, HttpMethod.GET,
                HttpEntity.EMPTY, DIR_LIST).getBody();
        assertThat(dft).isNotNull();
        assertThat(dft.getData()).hasSize(10);
    }

    @Test
    @DisplayName("DEF-1c IP 限流：同一 IP 每分钟 30 次，第 31 次 43001（沿用 Redisson 限流基建）")
    void def1_directoryRateLimited() {
        // 伪造非环回 IP（IpUtil 取 X-Forwarded-For 首位）；随机化避免 60s 窗口内重跑相互污染
        long seed = System.nanoTime();
        String fakeIp = "10." + ((seed >> 16) & 0xFF) + "." + ((seed >> 8) & 0xFF) + "." + (seed & 0xFF);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", fakeIp);

        for (int i = 1; i <= 30; i++) {
            R<List<Map<String, Object>>> ok = restTemplate.exchange(
                    baseDirectory + "?limit=1", HttpMethod.GET,
                    new HttpEntity<>(headers), DIR_LIST).getBody();
            assertThat(ok).isNotNull();
            assertThat(ok.getCode()).as("第 %s 次仍在阈值内", i).isEqualTo(0);
        }
        R<List<Map<String, Object>>> blocked = restTemplate.exchange(
                baseDirectory + "?limit=1", HttpMethod.GET,
                new HttpEntity<>(headers), DIR_LIST).getBody();
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).as("超过阈值应 43001 限流").isEqualTo(43001);
    }
}
