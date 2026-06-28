package com.cangchu.account;

import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.*;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account 模块场景测试（S2 非法输入 / S4 鉴权越权 / S6 幂等 / S7 并发）。
 *
 * <p>测试基建说明：沿用 {@link AccountControllerTest} 的既有套件风格
 * （@SpringBootTest RANDOM_PORT + TestRestTemplate + H2 内存库 + mock 短信码 888888）。
 * 未引入 RestAssured 以保持一套风格、避免双栈。Sa-Token token 直接放 Authorization 头（无 Bearer 前缀）。
 *
 * <p>数据隔离：每个用例用唯一手机号（类加载时间戳尾段 + 自增 seq），不依赖 @Order 副作用。
 *
 * <p>错误码契约（以源码为准，grep 确认）：
 * <ul>
 *   <li>所有 @Valid 校验失败 → 全局 handler 统一返回 40001（VALIDATION_BASIC_001），
 *       DTO 上 @Pattern 的“手机号格式/密码强度”文案进 fields 明细，但 code 不是 40101/40102。
 *       ⇒ 02 文档期望的 40101/40102 与实现不符，见报告“发现的缺陷/契约偏差”。</li>
 *   <li>重复注册 → 41104（AUTH_ACCOUNT_004）。</li>
 *   <li>未登录调受保护接口 → 41001（AUTH_BASIC_001，HTTP 401）。</li>
 *   <li>role 字段无枚举校验：非法角色被原样接受 ⇒ AC-S2-05 断言会暴露该缺陷。</li>
 * </ul>
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;

    static {
        // JDK HttpURLConnection：4xx 携带 body 的请求默认会尝试重发认证，导致
        // "cannot retry due to server authentication, in streaming mode"。
        // 开启错误流缓冲 + 禁用 keep-alive，使 401 响应体可被正常读取。（纯测试基建）
        System.setProperty("sun.net.http.errorstream.enableBuffering", "true");
        System.setProperty("http.keepAlive", "false");
    }

    // 1 + [3-9] + 5位时间戳尾段 + 4位自增 = 11 位，满足 ^1[3-9]\d{9}$
    private static final String PHONE_PREFIX =
            "13" + String.format("%05d", (System.nanoTime() & 0x7FFFFFFF) % 100000);
    private static final AtomicLong SEQ = new AtomicLong(0);

    /**
     * 非流式 RestTemplate：带 body 的 PUT 命中 401/4xx 时，HttpURLConnection 默认 streaming 模式
     * 会抛 "cannot retry due to server authentication" 而非返回 401 响应体。
     * 关闭 outputStreaming 后可正常读取错误响应体。（纯测试基建修正，不涉业务）
     */
    private RestTemplate nonStreamingRt() {
        // 用 Apache HttpClient5 工厂：JDK 自带的 SimpleClientHttpRequestFactory 在
        // PUT body + 401(WWW-Authenticate) 下会抛 "cannot retry...streaming mode"，无法读响应体。
        org.springframework.http.client.HttpComponentsClientHttpRequestFactory factory =
                new org.springframework.http.client.HttpComponentsClientHttpRequestFactory();
        RestTemplate rt = new RestTemplate(factory);
        // 4xx/5xx 不抛异常，按 TestRestTemplate 风格返回响应体供断言
        rt.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(org.springframework.http.client.ClientHttpResponse r) { }
        });
        return rt;
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1/account";
    }

    private String uniquePhone() {
        long n = SEQ.incrementAndGet();
        return PHONE_PREFIX + String.format("%04d", n % 10000);
    }

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID =
            new ParameterizedTypeReference<>() {};

    private R<LoginVo> register(RegisterDto dto) {
        return restTemplate.exchange(baseUrl + "/register", HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
    }

    private RegisterDto validReg(String phone) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword("Pass1234");
        dto.setSmsCode("888888");
        dto.setRole("TA");
        dto.setNickname("场景测试");
        dto.setAgreedTerms(true);   // D-16：注册需同意协议
        return dto;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", token);
        return h;
    }

    // ======================================================================
    // D-16 注册业务字段落库 / 同意协议门槛
    // ======================================================================

    @Test
    @DisplayName("AC-D16-01 未同意协议(agreedTerms=null) 注册 → 40001 拒绝")
    void acD16_01_notAgreedTerms() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setAgreedTerms(null);
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-D16-01b 显式不同意协议(agreedTerms=false) 注册 → 40001 拒绝")
    void acD16_01b_agreedTermsFalse() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setAgreedTerms(false);
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-D16-02 TA 携 tenantName 注册 → 建 PENDING 租户壳并绑定 tenantId（登录响应 roles 带 tenantId）")
    void acD16_02_taRegisterWithTenantName() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setRole("TA");
        dto.setRealName("张三");
        dto.setTenantName("西湖测试仓-" + dto.getPhone());
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().getPrimaryRole()).isEqualTo("TA");
        // 建仓壳后 TA 角色应已绑定 tenantId（D-16 核心断言）
        assertThat(body.getData().getRoles()).isNotEmpty();
        assertThat(body.getData().getRoles().get(0).getTenantId())
                .as("TA 携仓库名注册应创建 PENDING 租户并把 tenantId 绑定到角色")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-D16-03 TA 不带 tenantName 注册 → 仅建账号，tenantId 仍为空（建仓延后到 apply）")
    void acD16_03_taRegisterWithoutTenantName() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setRole("TA");
        // 不设 tenantName
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData().getRoles().get(0).getTenantId())
                .as("未填仓库名时注册不建仓，tenantId 应为 null")
                .isNull();
    }

    @Test
    @DisplayName("AC-D16-04 expireAt 带时区偏移（D-13，OffsetDateTime ISO-8601 含 +）")
    void acD16_04_expireAtHasOffset() {
        RegisterDto dto = validReg(uniquePhone());
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        // D-13：expireAt 现为 OffsetDateTime —— 若后端仍发无偏移的 LocalDateTime 串，
        // 客户端 ObjectMapper 无法反序列化进 OffsetDateTime 字段（会报错/为 null）。
        // 因此「能成功反序列化为 OffsetDateTime 且为未来时间」即证明对外时间已带时区偏移。
        // 注：客户端 Jackson 默认把偏移归一化到 UTC，故此处不断言具体 +08:00，仅校验类型+语义。
        assertThat(body.getData().getExpireAt())
                .as("expireAt 应为带时区偏移的 OffsetDateTime（非无偏移 LocalDateTime）")
                .isNotNull();
        assertThat(body.getData().getExpireAt().toInstant())
                .as("expireAt 应是未来的过期时刻")
                .isAfter(java.time.Instant.now().minusSeconds(60));
    }

    // ======================================================================
    // S2 非法输入
    // ======================================================================

    @Test
    @DisplayName("AC-S2-01 非法手机号注册(12345) → 校验失败(实现 40001，文档期望 40101)")
    void acS2_01_invalidPhone() {
        RegisterDto dto = validReg("12345");
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        // 实现：所有 @Valid 失败统一 40001。文档期望 40101，此处以源码为准断言 40001。
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-S2-02 密码缺数字(纯字母) → 校验失败(实现 40001，文档期望 40102)")
    void acS2_02_passwordNoDigit() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setPassword("PasswordOnly");
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-S2-02b 密码缺字母(纯数字) → 校验失败 40001")
    void acS2_02b_passwordNoLetter() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setPassword("12345678");
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-S2-03 验证码非法长度(空串) → 校验失败 40001")
    void acS2_03_smsCodeEmpty() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setSmsCode("");                 // @NotBlank 触发
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-S2-03b 验证码错误(非 mock 码) → 验证码错误 41202")
    void acS2_03b_smsCodeWrong() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setSmsCode("000000");           // 通过格式校验但非 mock 码、库中无记录
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(41202);
    }

    @Test
    @DisplayName("AC-S2-04 必填缺失(密码为 null) → 校验失败 40001")
    void acS2_04_missingRequired() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setPassword(null);              // @NotBlank
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(40001);
    }

    @Test
    @DisplayName("AC-S2-05 角色枚举外值注册 → 期望 40001(实现无枚举校验，预计暴露缺陷)")
    void acS2_05_invalidRoleEnum() {
        RegisterDto dto = validReg(uniquePhone());
        dto.setRole("HACKER");              // 非 TA/WK/ST/WA/WE/RT
        R<LoginVo> body = register(dto);
        assertThat(body).isNotNull();
        // 文档期望：非法角色应被拒绝（40001）。实现：RegisterDto.role 无 @Pattern/枚举校验，
        // 服务层原样存入 user_roles ⇒ 此断言预计 FAIL，作为“发现的缺陷”保留正确预期。
        assertThat(body.getCode())
                .as("非法角色应被拒绝；若返回 0 说明后端缺角色枚举校验（缺陷）")
                .isEqualTo(40001);
    }

    // ======================================================================
    // S4 鉴权与越权
    // ======================================================================

    @Test
    @DisplayName("AC-S4-01 无 token 调 /account/password → 41001 未登录")
    void acS4_01_noTokenChangePassword() {
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("Pass1234");
        dto.setNewPassword("NewPass456");

        ResponseEntity<R<Void>> resp = nonStreamingRt().exchange(
                baseUrl + "/password", HttpMethod.PUT,
                new HttpEntity<>(dto), VOID);
        // 注意：/api/v1/account/password 不在 SaInterceptor 拦截路径内，
        // 鉴权由控制器 StpUtil.getLoginIdAsLong() 触发 NotLoginException → 41001 / HTTP 401。
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(41001);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-S4-02 伪造/过期 token 调受保护接口 → 41001 未登录")
    void acS4_02_invalidToken() {
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword("Pass1234");
        dto.setNewPassword("NewPass456");

        ResponseEntity<R<Void>> resp = nonStreamingRt().exchange(
                baseUrl + "/password", HttpMethod.PUT,
                new HttpEntity<>(dto, bearer("invalid-token-xxxx")), VOID);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(41001);
    }

    @Test
    @DisplayName("AC-S4-03 RT 免密 token 调 TA 接口(/tenant/me) → 被拒(非 0)")
    void acS4_03_rtTokenCallsTaApi() {
        // RT 首次免密登录自动注册（仅 RT 角色，无 TA 租户绑定）
        String rtPhone = uniquePhone();
        ResponseEntity<R<LoginVo>> rt = restTemplate.exchange(
                baseUrl + "/login/rt?phone=" + rtPhone + "&code=888888",
                HttpMethod.POST, null, LOGIN_VO);
        assertThat(rt.getBody()).isNotNull();
        assertThat(rt.getBody().getCode()).isEqualTo(0);
        String rtToken = rt.getBody().getData().getToken();

        // 用 RT token 调 TA 专属接口 /tenant/me
        ResponseEntity<R<Object>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/tenant/me", HttpMethod.GET,
                new HttpEntity<>(bearer(rtToken)),
                new ParameterizedTypeReference<R<Object>>() {});
        assertThat(resp.getBody()).isNotNull();
        // 实现无 namespace/role 区分：RT token 通过 checkLogin，但 RT 用户无 TA 租户 ⇒ 50210(TENANT_NOT_FOUND)。
        // 断言“被拒绝”（非 0）即可；记录实际码 50210 供契约对齐。
        assertThat(resp.getBody().getCode())
                .as("RT token 不应取到他人/TA 数据，实际返回 50210 TENANT_NOT_FOUND")
                .isNotEqualTo(0);
    }

    // ======================================================================
    // S6 幂等
    // ======================================================================

    @Test
    @DisplayName("AC-S6-01 同手机号重复注册 → 41104 已注册")
    void acS6_01_duplicateRegister() {
        String phone = uniquePhone();
        R<LoginVo> first = register(validReg(phone));
        assertThat(first).isNotNull();
        assertThat(first.getCode()).isEqualTo(0);

        R<LoginVo> second = register(validReg(phone));
        assertThat(second).isNotNull();
        assertThat(second.getCode()).isEqualTo(41104);
    }

    @Test
    @DisplayName("AC-S6-02 改密后旧 token 失效（全设备踢出）")
    void acS6_02_oldTokenInvalidAfterChangePassword() {
        String phone = uniquePhone();
        R<LoginVo> reg = register(validReg(phone));
        assertThat(reg).isNotNull();
        assertThat(reg.getCode()).isEqualTo(0);
        String oldToken = reg.getData().getToken();

        // 用旧 token 改密
        ChangePasswordDto cp = new ChangePasswordDto();
        cp.setOldPassword("Pass1234");
        cp.setNewPassword("NewPass456");
        ResponseEntity<R<Void>> changeResp = restTemplate.exchange(
                baseUrl + "/password", HttpMethod.PUT,
                new HttpEntity<>(cp, bearer(oldToken)), VOID);
        assertThat(changeResp.getBody()).isNotNull();
        assertThat(changeResp.getBody().getCode()).isEqualTo(0);

        // 改密后 StpUtil.kickout(userId) → 旧 token 再调受保护接口应失效
        ChangePasswordDto cp2 = new ChangePasswordDto();
        cp2.setOldPassword("NewPass456");
        cp2.setNewPassword("Again7890");
        ResponseEntity<R<Void>> reuse = nonStreamingRt().exchange(
                baseUrl + "/password", HttpMethod.PUT,
                new HttpEntity<>(cp2, bearer(oldToken)), VOID);
        assertThat(reuse.getBody()).isNotNull();
        assertThat(reuse.getBody().getCode())
                .as("改密后旧 token 应失效（kickout），不得再次成功")
                .isNotEqualTo(0);
    }

    // ======================================================================
    // S7 并发（Java 21 虚拟线程）
    // ======================================================================

    @Test
    @DisplayName("AC-S7-01 同手机号并发注册 N 次 → 仅 1 成功，其余 41104/失败（虚拟线程）")
    void acS7_01_concurrentRegister() throws Exception {
        String phone = uniquePhone();
        int threads = 20;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    R<LoginVo> body = register(validReg(phone));
                    int code = body == null ? -1 : body.getCode();
                    if (code == 0) ok.incrementAndGet();
                    else if (code == 41104) dup.incrementAndGet();
                    else other.incrementAndGet();
                }));
            }
            for (var f : futures) f.get();
        }

        // 期望唯一性：恰好 1 个成功。H2 上 phone_hash 无唯一约束时，并发窗口可能 >1 成功 ⇒ 暴露缺陷。
        assertThat(ok.get())
                .as("并发注册应仅 1 个成功（ok=%d dup=%d other=%d）；>1 说明缺唯一约束/竞态防护",
                        ok.get(), dup.get(), other.get())
                .isEqualTo(1);
    }
}
