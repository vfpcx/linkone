package com.cangchu.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.EmployeeInviteCreateDto;
import com.cangchu.tenant.dto.TenantApplyDto;
import com.cangchu.tenant.dto.TenantAuditDto;
import com.cangchu.tenant.entity.InviteCode;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.InviteCodeMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.vo.EmployeeInviteVo;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 员工注册码场景测试（phase-1：TA 生码 → 员工凭码注册绑定 → 解锁 WK 入库）。
 *
 * <p>测试基建：@SpringBootTest RANDOM_PORT + TestRestTemplate（HTTP 层走真实 register/TA 生码闭环），
 * H2 + mock 短信码 888888（沿用 {@link TenantScenarioTest} 风格）。入库端到端用 service + mapper 直连
 * （沿用 {@code InboundScenarioTest}：seed wholesaler/sku + 操控 {@link TenantContext}），
 * 但 WK 账号与 user_roles 绑定是经真实「凭码注册」产生的——验证注册码确实解锁了 requireWkRole。
 *
 * <p>覆盖：
 * <ul>
 *   <li>S1 TA 生码(WK) → 凭码注册 → user_roles 出现 (WK, 该 tenant, ACTIVE) → 该 WK registerByWk 成功入库。</li>
 *   <li>S2 过期 / 超次 / 角色非 WK-ST / 无效码 → 拒绝。</li>
 *   <li>S4 非 TA 生码拒绝；跨租户作废拒绝。</li>
 *   <li>S6 maxUses 用尽后再注册拒绝。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeInviteScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private InboundRequestService inboundRequestService;
    @Autowired
    private InviteCodeMapper inviteCodeMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static final String PHONE_TA  = "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String PHONE_EMP = "17" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final String PHONE_OPS = "15" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    private String baseAccount;
    private String baseTenant;
    private String baseAdmin;

    @BeforeEach
    void setUp() {
        baseAccount = "http://localhost:" + port + "/api/v1/account";
        baseTenant  = "http://localhost:" + port + "/api/v1/tenant";
        baseAdmin   = "http://localhost:" + port + "/api/v1/admin/tenant";
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Map<String, Object>>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<EmployeeInviteVo>> INVITE_VO = new ParameterizedTypeReference<>() {};
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

    /** 注册并登录，返回 LoginVo（含 token + roles）。允许失败（用于 S2/S6 期望拒绝场景）。 */
    private R<LoginVo> register(String phone, String password, String role, String inviteCode, String tenantName) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode("888888");
        dto.setRole(role);
        dto.setAgreedTerms(true);
        dto.setRealName("员工" + phone.substring(phone.length() - 4));
        if (inviteCode != null) dto.setInviteCode(inviteCode);
        if (tenantName != null) dto.setTenantName(tenantName);
        return restTemplate.exchange(baseAccount + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    /** 注册一个 TA + 建仓壳 + OPS 审核通过 → 返回 (token, tenantId)（ACTIVE 租户）。 */
    private TaCtx registerActiveTa() {
        String phone = uniquePhone(PHONE_TA);
        R<LoginVo> reg = register(phone, "TaPass123", "TA", null, "员工码仓库-" + phone);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).as("TA 注册建仓").isEqualTo(0);
        String token = reg.getData().getToken();
        Long tenantId = reg.getData().getRoles().stream()
                .filter(r -> "TA".equals(r.getRole()))
                .map(LoginVo.RoleInfo::getTenantId)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();

        // OPS 审核通过 → 租户 ACTIVE（registerByWk 不强制租户 ACTIVE，但保持闭环真实）
        String opsToken = register(uniquePhone(PHONE_OPS), "OpsPass123", "OPS", null, null).getData().getToken();
        TenantAuditDto audit = new TenantAuditDto();
        audit.setAction("APPROVED");
        audit.setRemark("auto");
        R<Void> auditRes = restTemplate.exchange(baseAdmin + "/" + tenantId + "/audit", HttpMethod.POST,
                new HttpEntity<>(audit, bearer(opsToken)), VOID).getBody();
        // audit 需 OPS 角色，OPS 注册即拥有 → 应通过
        assertThat(auditRes).isNotNull();
        return new TaCtx(token, tenantId);
    }

    private record TaCtx(String token, Long tenantId) {}

    /** TA 生成员工注册码。 */
    private R<EmployeeInviteVo> createInvite(String taToken, String role, Integer maxUses, Integer expiresInDays) {
        EmployeeInviteCreateDto dto = new EmployeeInviteCreateDto();
        dto.setRole(role);
        dto.setMaxUses(maxUses);
        dto.setExpiresInDays(expiresInDays);
        return restTemplate.exchange(baseTenant + "/employee-invites", HttpMethod.POST,
                new HttpEntity<>(dto, bearer(taToken)), INVITE_VO).getBody();
    }

    /** seed 一个本租户商户。 */
    private long seedWholesaler(long tenantId) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("商户-" + w.getId());
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return w.getId();
    }

    /** seed 一个 sku 挂在 wholesaler 下。 */
    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

    // ======================================================================
    // S1 端到端：TA 生码 → 凭码注册 → user_roles 绑定 → WK 入库解锁
    // ======================================================================

    @Test
    @DisplayName("EI-S1-01 TA生码(WK)→凭码注册→user_roles(WK,该tenant,ACTIVE)→该WK registerByWk 成功入库")
    void s1_inviteRegisterUnlocksInbound() {
        TaCtx ta = registerActiveTa();

        // 1) TA 生成 WK 注册码
        R<EmployeeInviteVo> inviteRes = createInvite(ta.token(), "WK", 1, 7);
        assertThat(inviteRes).isNotNull();
        assertThat(inviteRes.getCode()).as("TA 生码应成功").isEqualTo(0);
        String code = inviteRes.getData().getCode();
        assertThat(inviteRes.getData().getRole()).isEqualTo("WK");
        assertThat(inviteRes.getData().getRemaining()).isEqualTo(1);

        // 2) 员工凭码注册（role 以码为准，无需 tenantName）
        String empPhone = uniquePhone(PHONE_EMP);
        R<LoginVo> empReg = register(empPhone, "WkPass123", "WK", code, null);
        assertThat(empReg).isNotNull();
        assertThat(empReg.getCode()).as("凭码注册应成功").isEqualTo(0);
        Long wkUserId = empReg.getData().getUserId();

        // 3) user_roles 出现 (WK, 该 tenant, ACTIVE)
        long bound = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, wkUserId)
                .eq(UserRole::getRole, "WK")
                .eq(UserRole::getTenantId, ta.tenantId())
                .eq(UserRole::getStatus, "ACTIVE"));
        assertThat(bound).as("凭码注册后应绑定 (WK, 该 tenant, ACTIVE)").isEqualTo(1);

        // 4) 端到端解锁：该 WK 能通过 requireWkRole 成功 registerByWk 入库
        long wid = seedWholesaler(ta.tenantId());
        long sku = seedSku(ta.tenantId(), wid);
        TenantContext.set(TenantContext.TenantInfo.of(ta.tenantId(), wkUserId, "WK"));

        InboundRegisterDto inDto = new InboundRegisterDto();
        inDto.setWholesalerId(wid);
        inDto.setSkuId(sku);
        inDto.setQty(40);
        inDto.setPalletQty(2);
        InboundRequestVo vo = inboundRequestService.registerByWk(inDto, wkUserId);

        assertThat(vo.getDocNo()).startsWith("WK-");
        assertThat(vo.getQty()).isEqualTo(40);
        assertThat(vo.getCurrentStock()).isEqualTo(40);
        assertThat(vo.getTenantId()).isEqualTo(ta.tenantId());
    }

    @Test
    @DisplayName("EI-S1-02 TA生码(ST)→凭码注册→user_roles(ST,该tenant,ACTIVE)")
    void s1_stInvite() {
        TaCtx ta = registerActiveTa();
        R<EmployeeInviteVo> inviteRes = createInvite(ta.token(), "ST", 1, 7);
        assertThat(inviteRes.getCode()).isEqualTo(0);
        String code = inviteRes.getData().getCode();

        R<LoginVo> empReg = register(uniquePhone(PHONE_EMP), "StPass123", "ST", code, null);
        assertThat(empReg.getCode()).isEqualTo(0);
        long bound = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, empReg.getData().getUserId())
                .eq(UserRole::getRole, "ST")
                .eq(UserRole::getTenantId, ta.tenantId())
                .eq(UserRole::getStatus, "ACTIVE"));
        assertThat(bound).isEqualTo(1);
    }

    // ======================================================================
    // S2 非法：无效码 / 角色非 WK-ST / 过期(0 天后即过期) — 拒绝
    // ======================================================================

    @Test
    @DisplayName("EI-S2-01 无效码凭码注册 → 拒绝(41301 邀请码无效)")
    void s2_invalidCode() {
        R<LoginVo> reg = register(uniquePhone(PHONE_EMP), "WkPass123", "WK", "NOSUCHCODE99", null);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(41301);
    }

    @Test
    @DisplayName("EI-S2-02 TA 生码角色非 WK/ST(如 TA) → 拒绝(50290)")
    void s2_roleNotAllowed() {
        TaCtx ta = registerActiveTa();
        R<EmployeeInviteVo> res = createInvite(ta.token(), "TA", 1, 7);
        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo(50290);
    }

    @Test
    @DisplayName("EI-S2-03 过期码凭码注册 → 拒绝(41302 已过期)")
    void s2_expiredCode() {
        TaCtx ta = registerActiveTa();
        R<EmployeeInviteVo> inviteRes = createInvite(ta.token(), "WK", 1, 7);
        assertThat(inviteRes.getCode()).isEqualTo(0);
        String code = inviteRes.getData().getCode();

        // 直接把 expireAt 回拨到过去，模拟过期（避免时间推进依赖）
        InviteCode invite = inviteCodeMapper.selectById(inviteRes.getData().getId());
        invite.setExpireAt(java.time.LocalDateTime.now().minusDays(1));
        inviteCodeMapper.updateById(invite);

        R<LoginVo> reg = register(uniquePhone(PHONE_EMP), "WkPass123", "WK", code, null);
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(41302);
    }

    // ======================================================================
    // S4 越权：非 TA 生码拒绝；跨租户作废拒绝
    // ======================================================================

    @Test
    @DisplayName("EI-S4-01 非 TA(无租户的普通账号) 生码 → 拒绝(非 0)")
    void s4_nonTaCreate() {
        // 注册一个不建仓的 TA（无绑定租户）—— requireTaRole 应判为「未建仓」50210
        String phone = uniquePhone(PHONE_TA);
        R<LoginVo> reg = register(phone, "TaPass123", "TA", null, null);  // 不填 tenantName → 无租户绑定
        assertThat(reg.getCode()).isEqualTo(0);
        String token = reg.getData().getToken();

        R<EmployeeInviteVo> res = createInvite(token, "WK", 1, 7);
        assertThat(res).isNotNull();
        assertThat(res.getCode()).as("未建仓 TA 生码应被拒").isNotEqualTo(0);
    }

    @Test
    @DisplayName("EI-S4-02 跨租户作废他人注册码 → 拒绝(50291 不存在)")
    void s4_crossTenantRevoke() {
        TaCtx taA = registerActiveTa();
        TaCtx taB = registerActiveTa();

        R<EmployeeInviteVo> inviteA = createInvite(taA.token(), "WK", 1, 7);
        assertThat(inviteA.getCode()).isEqualTo(0);
        Long inviteIdA = inviteA.getData().getId();

        // B 试图作废 A 的注册码 → 按不存在处理（50291）
        R<Void> res = restTemplate.exchange(baseTenant + "/employee-invites/" + inviteIdA, HttpMethod.DELETE,
                new HttpEntity<>(bearer(taB.token())), VOID).getBody();
        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo(50291);

        // A 自己作废成功
        R<Void> ok = restTemplate.exchange(baseTenant + "/employee-invites/" + inviteIdA, HttpMethod.DELETE,
                new HttpEntity<>(bearer(taA.token())), VOID).getBody();
        assertThat(ok.getCode()).isEqualTo(0);

        // 作废后凭该码注册 → 拒绝(50292 已作废)
        R<LoginVo> reg = register(uniquePhone(PHONE_EMP), "WkPass123", "WK", inviteA.getData().getCode(), null);
        assertThat(reg.getCode()).isEqualTo(50292);
    }

    // ======================================================================
    // S6 maxUses 用尽后再注册拒绝
    // ======================================================================

    @Test
    @DisplayName("EI-S6-01 maxUses=1 用尽后再凭码注册 → 拒绝(41303 已用完)")
    void s6_exhausted() {
        TaCtx ta = registerActiveTa();
        R<EmployeeInviteVo> inviteRes = createInvite(ta.token(), "WK", 1, 7);
        assertThat(inviteRes.getCode()).isEqualTo(0);
        String code = inviteRes.getData().getCode();

        // 第一次注册成功
        R<LoginVo> first = register(uniquePhone(PHONE_EMP), "WkPass123", "WK", code, null);
        assertThat(first.getCode()).as("首次凭码注册成功").isEqualTo(0);

        // 第二次（不同手机号）注册 → 已用完
        R<LoginVo> second = register(uniquePhone(PHONE_EMP), "WkPass123", "WK", code, null);
        assertThat(second).isNotNull();
        assertThat(second.getCode()).as("maxUses 用尽后再注册应被拒").isEqualTo(41303);

        // 列表里该码 status 应为 EXHAUSTED、remaining=0
        R<List<EmployeeInviteVo>> listRes = restTemplate.exchange(baseTenant + "/employee-invites",
                HttpMethod.GET, new HttpEntity<>(bearer(ta.token())),
                new ParameterizedTypeReference<R<List<EmployeeInviteVo>>>() {}).getBody();
        assertThat(listRes).isNotNull();
        EmployeeInviteVo target = listRes.getData().stream()
                .filter(v -> code.equals(v.getCode())).findFirst().orElseThrow();
        assertThat(target.getStatus()).isEqualTo("EXHAUSTED");
        assertThat(target.getRemaining()).isEqualTo(0);
    }
}
