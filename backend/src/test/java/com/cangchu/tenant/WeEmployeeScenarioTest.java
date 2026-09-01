package com.cangchu.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.entity.InviteCode;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.mapper.InviteCodeMapper;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 入驻生态 Wave3 场景测试：WE 批发商员工（码/绑定/授权位/R17 禁用/D52 路由）+ OPS 租户列表。
 *
 * <p>测试基建沿用 {@link WithdrawOfflineScenarioTest}（HTTP 主链 + mapper seed 混合）。
 * 用例编号对齐 04-onboarding-test-plan WEM/SEC 组；30 天恢复边界口径同 Wave2 BND-S3-01
 * （disabled_at 起点、数据库时间 TIMESTAMPADD 拨盘、29/30/31 天边界）。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeEmployeeScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private InviteCodeMapper inviteCodeMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;
    @Autowired
    private PiiCrypto piiCrypto;

    private static final String P_TA =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WA =
            "16" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String P_WE =
            "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String base;
    private String baseAccount;
    private String baseInvites;
    private String baseEmployees;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        baseAccount = base + "/api/v1/account";
        baseInvites = base + "/api/v1/wholesaler/employee-invites";
        baseEmployees = base + "/api/v1/wholesaler/employees";
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<List<Map<String, Object>>>> LIST =
            new ParameterizedTypeReference<>() {};

    // ==================== HTTP helpers（沿用 Wave2 风格） ====================

    private String uniquePhone(String prefix) {
        return prefix + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    private R<LoginVo> registerRaw(String phone, String password, String role, String inviteCode) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        if (inviteCode != null) dto.setInviteCode(inviteCode);
        return restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private LoginVo registerAndLogin(String phone, String password, String role) {
        R<LoginVo> body = registerRaw(phone, password, role, null);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).as("register %s role=%s", phone, role).isEqualTo(0);
        return body.getData();
    }

    private R<LoginVo> loginRaw(String phone, String password) {
        LoginDto dto = new LoginDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        return restTemplate.exchange(baseAccount + "/login", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private record TaContext(String phone, String token, Long tenantId) {}
    private record WaContext(String phone, String password, String token, Long userId, Long wholesalerId) {}
    private record WeContext(String phone, String password, String token, Long userId, Long roleRowId) {}

    /** 注册 TA + apply 建仓 + 置 ACTIVE + seed 店铺。 */
    private TaContext registerTaWithTenant() {
        String phone = uniquePhone(P_TA);
        String token = registerAndLogin(phone, "TaPass123", "TA").getToken();
        TenantApplyDto dto = new TenantApplyDto();
        dto.setName("WE仓-" + phone);
        dto.setContactPhone(phone);
        dto.setAddressText("江苏省南京市江宁区");
        R<Map<String, Object>> body = restTemplate.exchange(base + "/api/v1/tenant/apply", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        long tenantId = Long.parseLong(body.getData().get("tenantId").toString());
        Tenant tenant = tenantMapper.selectById(tenantId);
        tenant.setStatus("ACTIVE");
        tenantMapper.updateById(tenant);
        ensureStore(tenantId);
        return new TaContext(phone, token, tenantId);
    }

    private void ensureStore(long tenantId) {
        Store existing = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getTenantId, tenantId).last("LIMIT 1"));
        if (existing != null) return;
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);
    }

    /** 完整入驻一个 WA（Wave1 主链：注册 → 自助申请 → TA 通过）。 */
    private WaContext onboardWa(TaContext ta) {
        String phone = uniquePhone(P_WA);
        LoginVo reg = registerAndLogin(phone, "WaPass123", "WA");

        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("targetTenantId", ta.tenantId().toString());
        apply.put("name", "WE测试商户-" + phone);
        R<Map<String, Object>> applied = restTemplate.exchange(base + "/api/v1/wholesaler/applications",
                HttpMethod.POST, new HttpEntity<>(apply, bearer(reg.getToken())), MAP).getBody();
        assertThat(applied).isNotNull();
        assertThat(applied.getCode()).isEqualTo(0);
        String appId = applied.getData().get("applicationId").toString();

        Map<String, Object> auditDto = Map.of("action", "APPROVED", "remark", "Wave3 测试放行");
        R<Map<String, Object>> approved = restTemplate.exchange(
                base + "/api/v1/tenant/wholesaler-applications/" + appId + "/audit",
                HttpMethod.POST, new HttpEntity<>(auditDto, bearer(ta.token())), MAP).getBody();
        assertThat(approved).isNotNull();
        assertThat(approved.getCode()).isEqualTo(0);
        long wholesalerId = Long.parseLong(approved.getData().get("wholesalerId").toString());

        R<LoginVo> relogin = loginRaw(phone, "WaPass123");
        assertThat(relogin).isNotNull();
        assertThat(relogin.getCode()).isEqualTo(0);
        return new WaContext(phone, "WaPass123", relogin.getData().getToken(), reg.getUserId(), wholesalerId);
    }

    /** WA 生 WE 码。 */
    private R<Map<String, Object>> createWeInvite(String waToken, List<String> permissions,
                                                  Integer expireDays, Integer maxUses) {
        Map<String, Object> dto = new LinkedHashMap<>();
        if (permissions != null) dto.put("permissions", permissions);
        if (expireDays != null) dto.put("expireDays", expireDays);
        if (maxUses != null) dto.put("maxUses", maxUses);
        return restTemplate.exchange(baseInvites, HttpMethod.POST,
                new HttpEntity<>(dto, bearer(waToken)), MAP).getBody();
    }

    /** 生码 + 凭码注册一个 WE，返回上下文（含 user_roles 行 id）。 */
    private WeContext onboardWe(WaContext wa, List<String> permissions) {
        R<Map<String, Object>> invite = createWeInvite(wa.token(), permissions, 7, 1);
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).isEqualTo(0);
        String code = invite.getData().get("code").toString();

        String phone = uniquePhone(P_WE);
        R<LoginVo> reg = registerRaw(phone, "WePass123", "TA" /* 入口 role 被码覆盖 */, code);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).as("WE 凭码注册").isEqualTo(0);
        UserRole row = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, reg.getData().getUserId())
                .eq(UserRole::getRole, "WE"));
        assertThat(row).as("WE 角色行必须落库").isNotNull();
        return new WeContext(phone, "WePass123", reg.getData().getToken(), reg.getData().getUserId(), row.getId());
    }

    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("WE品-" + s.getId());
        s.setUnitPrice(new BigDecimal("15.00"));
        s.setMoqPrice(new BigDecimal("13.00"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        TenantContext.clear();
        return s.getId();
    }

    /** WE 设置专属价（PRICE_EDIT 切点探针）。 */
    private R<Map<String, Object>> setCustomerPrice(String token, long wholesalerId, long skuId, String price) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("wholesalerId", wholesalerId);
        dto.put("skuId", skuId);
        dto.put("rtPhone", "18899990000");
        dto.put("unitPrice", price);
        return restTemplate.exchange(base + "/api/v1/tenant/customer-prices", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(token)), MAP).getBody();
    }

    /** 登录态探针：WE 可读询价列表（0=在线；被踢后 41001）。 */
    private int probeAuth(String token) {
        R<List<Map<String, Object>>> body = restTemplate.exchange(base + "/api/v1/tenant/inquiry",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), LIST).getBody();
        return body == null ? -1 : body.getCode();
    }

    /** 数据库时间拨盘：disabled_at 拨回 daysAgo 天前（30 天边界口径同 Wave2）。 */
    private void rewindDisabledAt(long roleRowId, int daysAgo) {
        jdbcTemplate.update(
                "UPDATE user_roles SET disabled_at = TIMESTAMPADD(DAY, ?, NOW()) WHERE id = ?",
                -daysAgo, roleRowId);
    }

    // ======================================================================
    // WEM-S1：主链
    // ======================================================================

    @Test
    @DisplayName("WEM-S1-01/02 WA生WE码(绑定wholesaler_id+初始授权)→WE凭码注册→user_roles(WE,wholesaler,permissions)")
    void wem_s1_01_02_inviteAndRegisterBinding() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);

        R<Map<String, Object>> invite = createWeInvite(wa.token(), List.of("PRICE_EDIT"), 7, 2);
        assertThat(invite).isNotNull();
        assertThat(invite.getCode()).isEqualTo(0);
        assertThat(invite.getData().get("role")).isEqualTo("WE");
        assertThat(invite.getData().get("wholesalerId")).as("WEM-S1-01 码必须写 wholesaler_id")
                .isEqualTo(wa.wholesalerId().toString());
        assertThat(invite.getData().get("permissions")).isEqualTo(List.of("PRICE_EDIT"));

        // 落库校验
        InviteCode dbCode = inviteCodeMapper.selectOne(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getCode, invite.getData().get("code").toString()));
        assertThat(dbCode.getWholesalerId()).isEqualTo(wa.wholesalerId());
        assertThat(dbCode.getTargetRole()).isEqualTo("WE");

        // WE 凭码注册 → 绑定 + 初始授权
        String phone = uniquePhone(P_WE);
        R<LoginVo> reg = registerRaw(phone, "WePass123", "TA", dbCode.getCode());
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        UserRole row = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, reg.getData().getUserId())
                .eq(UserRole::getRole, "WE"));
        assertThat(row).isNotNull();
        assertThat(row.getWholesalerId()).as("WEM-S1-02 注册须绑定 wholesaler_id").isEqualTo(wa.wholesalerId());
        assertThat(row.getTenantId()).isEqualTo(ta.tenantId());
        assertThat(row.getPermissions()).contains("PRICE_EDIT").doesNotContain("INQUIRY_CONFIRM");
    }

    @Test
    @DisplayName("WEM-S1-03 WE 登录落 WA 端路由 /wa/inquiry（D52 修正，不再跳 /ta/dashboard）")
    void wem_s1_03_loginRouter() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of());

        R<LoginVo> login = loginRaw(we.phone(), we.password());
        assertThat(login).isNotNull();
        assertThat(login.getCode()).isEqualTo(0);
        assertThat(login.getData().getPrimaryRole()).isEqualTo("WE");
        assertThat(login.getData().getPrimaryRouter()).as("D52：WE 落 WA 端路由").isEqualTo("/wa/inquiry");
    }

    @Test
    @DisplayName("D52 多角色优先级：TA+WE 双角色用户登录主角色为 TA（TA>ST>WK>WA>WE）")
    void d52_multiRolePriority() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of());

        // 给该 WE 用户再补一条 TA 绑定（多角色）
        UserRole taRole = new UserRole();
        taRole.setId(snowflakeIdUtil.nextId());
        taRole.setUserId(we.userId());
        taRole.setRole("TA");
        taRole.setTenantId(ta.tenantId());
        taRole.setStatus("ACTIVE");
        taRole.setPriority(10);
        userRoleMapper.insert(taRole);

        R<LoginVo> login = loginRaw(we.phone(), we.password());
        assertThat(login).isNotNull();
        assertThat(login.getData().getPrimaryRole()).as("D52 优先级 TA>WE").isEqualTo("TA");
        assertThat(login.getData().getPrimaryRouter()).isEqualTo("/ta/dashboard");
    }

    // ======================================================================
    // WEM-S2：白名单收敛
    // ======================================================================

    @Test
    @DisplayName("WEM-S2-01 WA 生码 permissions 传白名单外值 → 50319 拒绝")
    void wem_s2_01_invalidPermission() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        R<Map<String, Object>> bad = createWeInvite(wa.token(), List.of("BILLING_VIEW"), 7, 1);
        assertThat(bad).isNotNull();
        assertThat(bad.getCode()).isEqualTo(50319);
    }

    @Test
    @DisplayName("WEM-S2-02 TA 端 /tenant/employee-invites 生 WE 码仍被拒（白名单影响面收敛，仅 WK/ST）")
    void wem_s2_02_taSideStillWkStOnly() {
        TaContext ta = registerTaWithTenant();
        Map<String, Object> dto = Map.of("role", "WE", "maxUses", 1, "expiresInDays", 7);
        R<Map<String, Object>> res = restTemplate.exchange(base + "/api/v1/tenant/employee-invites",
                HttpMethod.POST, new HttpEntity<>(dto, bearer(ta.token())), MAP).getBody();
        assertThat(res).isNotNull();
        assertThat(res.getCode()).as("TA 端白名单仍仅 WK/ST").isEqualTo(50290);
    }

    @Test
    @DisplayName("SEC 非 WA（无商户）调用 WE 生码/员工列表 → 拒绝")
    void sec_nonWaCreate() {
        String phone = uniquePhone(P_WE);
        LoginVo plain = registerAndLogin(phone, "NoWa12345", "TA");
        R<Map<String, Object>> res = createWeInvite(plain.getToken(), List.of(), 7, 1);
        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo(50230);
        R<Map<String, Object>> list = restTemplate.exchange(baseEmployees, HttpMethod.GET,
                new HttpEntity<>(bearer(plain.getToken())), MAP).getBody();
        assertThat(list).isNotNull();
        assertThat(list.getCode()).isEqualTo(50230);
    }

    // ======================================================================
    // WEM-S4：授权位正反（PRICE_EDIT 切点）
    // ======================================================================

    @Test
    @DisplayName("WEM-S1-04/S4-01 PRICE_EDIT 正反：有权 WE 改价成功；未授权 WE 改价 42004")
    void wem_priceEditPermission() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());

        // 未授权 WE → 42004
        WeContext weNoPerm = onboardWe(wa, List.of());
        R<Map<String, Object>> denied = setCustomerPrice(weNoPerm.token(), wa.wholesalerId(), skuId, "12.00");
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).as("WEM-S4-01 未授 PRICE_EDIT 拒绝").isEqualTo(42004);

        // 已授 PRICE_EDIT WE → 成功（效果同 WA 改价）
        WeContext wePerm = onboardWe(wa, List.of("PRICE_EDIT"));
        R<Map<String, Object>> ok = setCustomerPrice(wePerm.token(), wa.wholesalerId(), skuId, "12.00");
        assertThat(ok).isNotNull();
        assertThat(ok.getCode()).as("WEM-S1-04 已授 PRICE_EDIT 成功").isEqualTo(0);

        // WA 本人不受限
        R<Map<String, Object>> waOk = setCustomerPrice(wa.token(), wa.wholesalerId(), skuId, "11.50");
        assertThat(waOk).isNotNull();
        assertThat(waOk.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("SEC-S4-09/WEM-S4-02 INQUIRY_CONFIRM 反向：未授权 WE 直调确认 API 42004（Service 层校验）")
    void wem_s4_02_inquiryConfirmDenied() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of("PRICE_EDIT")); // 有改价权但无确认权

        // 不需要真实询价单：授权切点在 requireWaRole 里先于状态校验？——否，先查单。
        // 这里 seed 一条 PENDING 询价（直插表太重，直接断言切点：用不存在单号会先 50284）。
        // 因此改为直插一条 PENDING 询价行以命中授权切点。
        long skuId = seedSku(ta.tenantId(), wa.wholesalerId());
        long inqId = snowflakeIdUtil.nextId();
        // W8（16 §1.3/V34）：V33/V34 已删 rt_phone 明文列，直插 PENDING 询价行改落 hmac + cipher
        jdbcTemplate.update(
                "INSERT INTO inquiry_requests (id, doc_no, tenant_id, store_id, wholesaler_id, rt_phone_hmac, rt_phone_cipher, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW())",
                inqId, "INQ-WE-" + inqId, ta.tenantId(), 0L, wa.wholesalerId(),
                piiCrypto.phoneHmac("18811112222"), piiCrypto.encrypt("18811112222"));

        R<Map<String, Object>> denied = restTemplate.exchange(
                base + "/api/v1/tenant/inquiry/" + inqId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(we.token())), MAP).getBody();
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).as("未授 INQUIRY_CONFIRM 的 WE 确认询价拒绝").isEqualTo(42004);

        // 授权后再确认 → 通过授权切点（skuId 无库存 → 后续库存不足 60001/50251 也证明已过鉴权）
        Map<String, Object> grant = Map.of("permissions", List.of("PRICE_EDIT", "INQUIRY_CONFIRM"));
        R<Map<String, Object>> granted = restTemplate.exchange(
                baseEmployees + "/" + we.roleRowId() + "/permissions", HttpMethod.PUT,
                new HttpEntity<>(grant, bearer(wa.token())), MAP).getBody();
        assertThat(granted).isNotNull();
        assertThat(granted.getCode()).isEqualTo(0);

        R<Map<String, Object>> afterGrant = restTemplate.exchange(
                base + "/api/v1/tenant/inquiry/" + inqId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(we.token())), MAP).getBody();
        assertThat(afterGrant).isNotNull();
        assertThat(afterGrant.getCode()).as("授权后不再被 42004 拦截").isNotEqualTo(42004);
    }

    @Test
    @DisplayName("WEM-S4-03 WE 任意授权组合访问账单类端点 → 拒绝（无对应授权位，无端点即 404/鉴权拒）")
    void wem_s4_03_billingNeverVisible() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of("PRICE_EDIT", "INQUIRY_CONFIRM"));

        // billing 域 P4 未建：不存在任何账单端点。防回归断言——常见猜测路径一律非 0 成功码。
        for (String path : List.of("/api/v1/wholesaler/bills", "/api/v1/tenant/bills", "/api/v1/billing")) {
            R<Map<String, Object>> res = restTemplate.exchange(base + path, HttpMethod.GET,
                    new HttpEntity<>(bearer(we.token())), MAP).getBody();
            // 404 时 body 可能为 null 或非 R 结构；只要不是 code=0 即未泄漏
            if (res != null) {
                assertThat(res.getCode()).as("WE 不可见账单端点 %s", path).isNotEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("SEC-S4-10 WE 属商户A，操作商户B的专属价 → 拒绝（wholesaler 归属校验）")
    void sec_s4_10_crossWholesaler() {
        TaContext ta = registerTaWithTenant();
        WaContext waA = onboardWa(ta);
        WaContext waB = onboardWa(ta);
        long skuB = seedSku(ta.tenantId(), waB.wholesalerId());

        WeContext weA = onboardWe(waA, List.of("PRICE_EDIT")); // A 商户员工，有改价授权
        R<Map<String, Object>> res = setCustomerPrice(weA.token(), waB.wholesalerId(), skuB, "10.00");
        assertThat(res).isNotNull();
        assertThat(res.getCode()).as("跨商户改价必须拒绝").isIn(42101, 42004);
    }

    // ======================================================================
    // WEM-S1-06/07 + S5：R17 禁用/恢复
    // ======================================================================

    @Test
    @DisplayName("WEM-S1-06 R17 禁用：置 DISABLED+disabledAt → token 即踢(41001) → 授权操作全拒")
    void wem_s1_06_disableKicksOut() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of("PRICE_EDIT"));
        assertThat(probeAuth(we.token())).as("禁用前 WE token 可用").isEqualTo(0);

        R<Map<String, Object>> disabled = restTemplate.exchange(
                baseEmployees + "/" + we.roleRowId() + "/disable", HttpMethod.POST,
                new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(disabled).isNotNull();
        assertThat(disabled.getCode()).isEqualTo(0);
        assertThat(disabled.getData().get("status")).isEqualTo("DISABLED");

        UserRole row = userRoleMapper.selectById(we.roleRowId());
        assertThat(row.getStatus()).isEqualTo("DISABLED");
        assertThat(row.getDisabledAt()).isNotNull();
        assertThat(probeAuth(we.token())).as("R17 禁用即踢").isEqualTo(41001);

        // 重复禁用 → 50321（CAS 防 disabled_at 被改写续期）
        R<Map<String, Object>> again = restTemplate.exchange(
                baseEmployees + "/" + we.roleRowId() + "/disable", HttpMethod.POST,
                new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(again).isNotNull();
        assertThat(again.getCode()).isEqualTo(50321);
    }

    @Test
    @DisplayName("WEM-S5-01 已禁用 WE 登录 → 41110 语义拒绝（不兜底 TA 放行）")
    void wem_s5_01_disabledLoginRejected() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of());
        restTemplate.exchange(baseEmployees + "/" + we.roleRowId() + "/disable", HttpMethod.POST,
                new HttpEntity<>(bearer(wa.token())), MAP);

        R<LoginVo> login = loginRaw(we.phone(), we.password());
        assertThat(login).isNotNull();
        assertThat(login.getCode()).as("全角色禁用登录语义拒绝").isEqualTo(41110);
    }

    @Test
    @DisplayName("WEM-S1-07 30天内恢复：授权保持禁用前设置，可重新登录")
    void wem_s1_07_restoreWithinWindow() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);
        WeContext we = onboardWe(wa, List.of("PRICE_EDIT"));
        restTemplate.exchange(baseEmployees + "/" + we.roleRowId() + "/disable", HttpMethod.POST,
                new HttpEntity<>(bearer(wa.token())), MAP);
        rewindDisabledAt(we.roleRowId(), 29); // 29 天前：窗口内

        R<Map<String, Object>> restored = restTemplate.exchange(
                baseEmployees + "/" + we.roleRowId() + "/restore", HttpMethod.POST,
                new HttpEntity<>(bearer(wa.token())), MAP).getBody();
        assertThat(restored).isNotNull();
        assertThat(restored.getCode()).isEqualTo(0);

        UserRole row = userRoleMapper.selectById(we.roleRowId());
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getDisabledAt()).isNull();
        assertThat(row.getPermissions()).as("授权保持禁用前设置").contains("PRICE_EDIT");

        R<LoginVo> login = loginRaw(we.phone(), we.password());
        assertThat(login).isNotNull();
        assertThat(login.getCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("BND 30天边界：30/31 天恢复被拒 50322（数据库时间口径，>=30 天整拒绝）")
    void bnd_restoreWindowBoundary() {
        TaContext ta = registerTaWithTenant();
        WaContext wa = onboardWa(ta);

        for (int days : new int[]{30, 31}) {
            WeContext we = onboardWe(wa, List.of());
            restTemplate.exchange(baseEmployees + "/" + we.roleRowId() + "/disable", HttpMethod.POST,
                    new HttpEntity<>(bearer(wa.token())), MAP);
            rewindDisabledAt(we.roleRowId(), days);
            R<Map<String, Object>> res = restTemplate.exchange(
                    baseEmployees + "/" + we.roleRowId() + "/restore", HttpMethod.POST,
                    new HttpEntity<>(bearer(wa.token())), MAP).getBody();
            assertThat(res).isNotNull();
            assertThat(res.getCode()).as("禁用 %d 天恢复应被拒", days).isEqualTo(50322);
        }
    }

    // ======================================================================
    // 员工管理列表 + 码管理
    // ======================================================================

    @Test
    @DisplayName("员工列表：本商户 WE 全量（含禁用）+ permissions/status/disabledAt；他商户不可见")
    void employeeListScoped() {
        TaContext ta = registerTaWithTenant();
        WaContext waA = onboardWa(ta);
        WaContext waB = onboardWa(ta);
        WeContext weA = onboardWe(waA, List.of("INQUIRY_CONFIRM"));
        onboardWe(waB, List.of());

        R<List<Map<String, Object>>> listA = restTemplate.exchange(baseEmployees, HttpMethod.GET,
                new HttpEntity<>(bearer(waA.token())), LIST).getBody();
        assertThat(listA).isNotNull();
        assertThat(listA.getCode()).isEqualTo(0);
        assertThat(listA.getData()).hasSize(1);
        Map<String, Object> item = listA.getData().get(0);
        assertThat(item.get("id")).isEqualTo(String.valueOf(weA.roleRowId()));
        assertThat(item.get("status")).isEqualTo("ACTIVE");
        assertThat(item.get("permissions")).isEqualTo(List.of("INQUIRY_CONFIRM"));
    }

    @Test
    @DisplayName("WEM-S6-01 码作废后凭码注册拒绝；DELETE 跨商户作废按不存在处理")
    void wem_s6_01_revokedCode() {
        TaContext ta = registerTaWithTenant();
        WaContext waA = onboardWa(ta);
        WaContext waB = onboardWa(ta);

        R<Map<String, Object>> invite = createWeInvite(waA.token(), List.of(), 7, 5);
        assertThat(invite).isNotNull();
        String inviteId = invite.getData().get("id").toString();
        String code = invite.getData().get("code").toString();

        // 跨商户作废 → 50291
        R<Map<String, Object>> crossRevoke = restTemplate.exchange(baseInvites + "/" + inviteId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(waB.token())), MAP).getBody();
        assertThat(crossRevoke).isNotNull();
        assertThat(crossRevoke.getCode()).isEqualTo(50291);

        // 本商户作废 → 成功；再凭码注册 → 50292
        R<Map<String, Object>> revoke = restTemplate.exchange(baseInvites + "/" + inviteId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(waA.token())), MAP).getBody();
        assertThat(revoke).isNotNull();
        assertThat(revoke.getCode()).isEqualTo(0);

        R<LoginVo> reg = registerRaw(uniquePhone(P_WE), "WePass123", "TA", code);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(50292);
    }

    @Test
    @DisplayName("授权变更越界值 → 50319；跨商户员工操作 → 50320")
    void permissionUpdateGuards() {
        TaContext ta = registerTaWithTenant();
        WaContext waA = onboardWa(ta);
        WaContext waB = onboardWa(ta);
        WeContext weA = onboardWe(waA, List.of());

        R<Map<String, Object>> bad = restTemplate.exchange(
                baseEmployees + "/" + weA.roleRowId() + "/permissions", HttpMethod.PUT,
                new HttpEntity<>(Map.of("permissions", List.of("SUPER_ADMIN")), bearer(waA.token())), MAP).getBody();
        assertThat(bad).isNotNull();
        assertThat(bad.getCode()).isEqualTo(50319);

        R<Map<String, Object>> cross = restTemplate.exchange(
                baseEmployees + "/" + weA.roleRowId() + "/permissions", HttpMethod.PUT,
                new HttpEntity<>(Map.of("permissions", List.of("PRICE_EDIT")), bearer(waB.token())), MAP).getBody();
        assertThat(cross).isNotNull();
        assertThat(cross.getCode()).as("SEC-S4-10 跨商户按不存在处理").isEqualTo(50320);
    }

    // ======================================================================
    // OPS 租户列表（Wave3 顺路补齐）
    // ======================================================================

    @Test
    @DisplayName("admin/tenants：OPS 分页+status 过滤+AdminTenantItem 字段；非 OPS 42002")
    void adminTenantsList() {
        TaContext ta = registerTaWithTenant(); // ACTIVE 租户一枚（带地址快照）
        String opsToken = registerAndLogin(
                uniquePhone("15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000)),
                "OpsPass123", "OPS").getToken();

        R<Map<String, Object>> pageRes = restTemplate.exchange(
                base + "/api/v1/admin/tenants?status=ACTIVE&page=1&size=50", HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(pageRes).isNotNull();
        assertThat(pageRes.getCode()).isEqualTo(0);
        Map<String, Object> data = pageRes.getData();
        // PageData 契约形状
        assertThat(data).containsKeys("list", "total", "page", "pageSize", "totalPages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        Map<String, Object> mine = list.stream()
                .filter(t -> String.valueOf(ta.tenantId()).equals(t.get("tenantId")))
                .findFirst().orElseThrow();
        assertThat(mine.get("status")).isEqualTo("ACTIVE");
        // PII-W7：管理端租户列表 contactPhone 打码（138****1234），全号走 phone-reveal 接口
        assertThat(mine.get("contactPhone")).isEqualTo(SmsUtil.maskPhone(ta.phone()));
        assertThat(mine.get("addressText")).isEqualTo("江苏省南京市江宁区");
        assertThat(mine).containsKeys("name", "appliedAt");

        // status 过滤有效：PENDING 过滤不应包含该 ACTIVE 租户
        R<Map<String, Object>> pending = restTemplate.exchange(
                base + "/api/v1/admin/tenants?status=PENDING&page=1&size=50", HttpMethod.GET,
                new HttpEntity<>(bearer(opsToken)), MAP).getBody();
        assertThat(pending).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pendingList = (List<Map<String, Object>>) pending.getData().get("list");
        assertThat(pendingList.stream().noneMatch(
                t -> String.valueOf(ta.tenantId()).equals(t.get("tenantId")))).isTrue();

        // 非 OPS → 42002
        R<Map<String, Object>> denied = restTemplate.exchange(
                base + "/api/v1/admin/tenants", HttpMethod.GET,
                new HttpEntity<>(bearer(ta.token())), MAP).getBody();
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).isEqualTo(42002);
    }
}
