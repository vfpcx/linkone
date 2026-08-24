package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.pii.PiiShadowReader.Verdict;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.service.BlacklistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 阶段 1 Step1 关卡：影子双查（波次 PII-W4，15 §4 阶段1）。
 *
 * <p><b>本类要钉住的两件事</b>，缺一不可：
 * <ol>
 *   <li><b>零行为变化</b>——影子期开着，登录/加黑/命中检查的结果与 plain 期<b>逐字节相同</b>；
 *       即便影子列被抹成 NULL（最坏情况），主路照样按旧列出正确答案。这是"影子"二字的全部含义。</li>
 *   <li><b>确实能抓到不一致</b>——只会报 MATCHED 的探针等于没探。故意造回填遗漏，
 *       断言计数器落在 {@link Verdict#MISSING} 上。不然 7 天 mismatch=0 的闸门就是自欺。</li>
 * </ol>
 *
 * <p><b>差值断言</b>：H2 内存库与 {@link PiiShadowReader} 的计数器都在整个 JVM 内跨测试类共享
 * （兄弟类的注册/登录也会打进同一批计数器），故一律取用例前后<b>快照差值</b>，绝不断言绝对值。
 *
 * <p><b>配置前提</b>：{@code src/test/resources/application.yml} 的 {@code read-mode=shadow}。
 * {@link #shadowRead_switchIsOn} 专门守住它——有人拨回 plain 时，本类其余用例会因为探针
 * 静默不动作而变成"全过"，那是最阴险的失效方式，必须先把开关本身断言死。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PII-W4 关卡：影子双查零行为变化 / 不一致可检出")
class PiiShadowReadScenarioTest {

    /** 手机号发号器，避开兄弟测试类占用的号段（S0 关卡类用 177 段），防唯一键撞车。 */
    private static final AtomicLong PHONE_SEQ = new AtomicLong(17600000000L);

    /** 与 src/test/resources/application.yml 的 cangchu.sms.mock-code 一致（仅测试态短路）。 */
    private static final String MOCK_SMS_CODE = "888888";

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BlacklistMapper blacklistMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private BlacklistService blacklistService;
    @Autowired
    private PiiShadowReader shadowReader;
    /** 仅用于造数/收尾（把 hmac 摆成想要的形状）。算法正确性由 S0 关卡类独立实现锚定，此处不重复。 */
    @Autowired
    private PiiCrypto piiCrypto;

    // ------------------------------------------------------------ 开关本身

    @Test
    @DisplayName("开关：测试态 read-mode=shadow 生效（否则本类其余用例将静默失效）")
    void shadowRead_switchIsOn() {
        assertThat(shadowReader.isShadowRead())
                .as("测试态须 read-mode=shadow，见 src/test/resources/application.yml")
                .isTrue();
    }

    // ------------------------------------------------------------ 一致路径（回填齐全）

    @Test
    @DisplayName("切点 A2（密码登录）：双写齐全时影子与旧列同指一行，计 MATCHED")
    void a2_login_shadowAgreesWithLegacy() {
        String phone = nextPhone();
        Long userId = register(phone, "PiiShadowPass123", "TA").getData().getUserId();

        Snapshot before = snapshot("A2-login");
        R<LoginVo> body = login(phone, "PiiShadowPass123");

        assertThat(body.getCode()).as("影子期登录必须照常成功，实际 %s", body).isZero();
        assertThat(body.getData().getUserId()).isEqualTo(userId);

        Snapshot after = snapshot("A2-login");
        assertThat(after.matched() - before.matched())
                .as("A2 影子双查应记一次 MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("切点 A1（注册查重）：新号两列都未命中，同样算一致")
    void a1_register_bothColumnsMissIsMatched() {
        Snapshot before = snapshot("A1-register");
        register(nextPhone(), "PiiShadowPass123", "TA");

        Snapshot after = snapshot("A1-register");
        assertThat(after.matched() - before.matched())
                .as("新号注册：旧列未命中、hmac 列也未命中 → MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("切点 A6（代建开号）：幂等复用时影子与旧列同指一行")
    void a6_ensureUser_shadowAgreesWithLegacy() {
        String phone = nextPhone();
        Long first = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        Snapshot before = snapshot("A6-ensure-user");
        Long second = userService.ensureUserByPhone(phone, "WA_PROVISION").userId();

        assertThat(second).as("影子期不得改变幂等语义").isEqualTo(first);
        Snapshot after = snapshot("A6-ensure-user");
        assertThat(after.matched() - before.matched()).isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    // ------------------------------------------------------------ 不一致可检出（这才是影子期的目的）

    @Test
    @DisplayName("检出力 A2：hmac 漏填的历史行 → 记 MISSING，且登录照常成功（零行为变化）")
    void a2_login_detectsMissingBackfillWithoutBreakingLogin() {
        String phone = nextPhone();
        String password = "PiiShadowPass123";
        Long userId = register(phone, password, "TA").getData().getUserId();
        // 造「V27 之前落库、回填还没扫到」的历史行：旧列齐全，hmac 为 NULL
        setUserHmac(userId, null);

        Snapshot before = snapshot("A2-login");
        R<LoginVo> body = login(phone, password);

        assertThat(body.getCode())
                .as("影子列缺失绝不能影响主路——旧列出结果，登录须照常成功")
                .isZero();
        assertThat(body.getData().getUserId()).isEqualTo(userId);

        Snapshot after = snapshot("A2-login");
        assertThat(after.missing() - before.missing())
                .as("旧列命中而 hmac 未命中 → 必须计 MISSING，这正是 7 天闸门要盯的数")
                .isEqualTo(1);
        assertThat(after.matched() - before.matched()).isZero();
    }

    @Test
    @DisplayName("检出力 A2：hmac 指向别的行 → 记 DIVERGED（规范化漂移/撞键的信号）")
    void a2_login_detectsDivergedRow() {
        String victim = nextPhone();
        String password = "PiiShadowPass123";
        Long victimId = register(victim, password, "TA").getData().getUserId();
        // 把另一行的 hmac 篡改成 victim 的 hmac：影子查询会查到"不是同一行"
        String impostor = nextPhone();
        Long impostorId = userService.ensureUserByPhone(impostor, "OPS_PROXY").userId();
        try {
            setUserHmac(victimId, null);
            setUserHmac(impostorId, piiCrypto.phoneHmac(victim));

            Snapshot before = snapshot("A2-login");
            R<LoginVo> body = login(victim, password);

            assertThat(body.getData().getUserId())
                    .as("影子指错人也不得影响主路——旧列说是谁就是谁")
                    .isEqualTo(victimId);
            assertThat(snapshot("A2-login").diverged() - before.diverged()).isEqualTo(1);
        } finally {
            // 收尾：把库恢复自洽，不留脏行给兄弟测试类的对账用例
            setUserHmac(impostorId, piiCrypto.phoneHmac(impostor));
            setUserHmac(victimId, piiCrypto.phoneHmac(victim));
        }
    }

    // ------------------------------------------------------------ 黑名单链 B1 / B2

    @Test
    @DisplayName("切点 B1（入驻命中）：双写齐全时影子与明文列命中结论一致")
    void b1_blacklistHit_shadowAgreesWithLegacy() {
        Long ops = registerOps();
        String phone = nextPhone();
        blacklistService.add(ops, blacklistDto("PHONE", phone));

        Snapshot before = snapshot("B1-blacklist-hit");
        boolean hit = blacklistService.isBlacklisted(phone, null);

        assertThat(hit).as("影子期命中判定不得变").isTrue();
        Snapshot after = snapshot("B1-blacklist-hit");
        assertThat(after.matched() - before.matched()).isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("检出力 B1：hmac 漏填的黑名单行 → 记 MISSING，且仍然照常命中")
    void b1_blacklistHit_detectsMissingBackfillWithoutMissingTheHit() {
        Long ops = registerOps();
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(ops, blacklistDto("PHONE", phone));
        clearBlacklistHmac(entry.getId());

        Snapshot before = snapshot("B1-blacklist-hit");
        boolean hit = blacklistService.isBlacklisted(phone, null);

        assertThat(hit)
                .as("影子列缺失时，命中仍须由明文列给出——漏放一个黑名单就是风控事故")
                .isTrue();
        assertThat(snapshot("B1-blacklist-hit").missing() - before.missing()).isEqualTo(1);
    }

    @Test
    @DisplayName("切点 B2（加黑查重）：LICENSE_NO 行 hmac 恒 NULL，不进影子分母")
    void b2_licenseNoIsNotProbedAtAll() {
        Long ops = registerOps();
        Snapshot before = snapshot("B2-blacklist-add");
        blacklistService.add(ops, blacklistDto("LICENSE_NO", "PIIW4" + PHONE_SEQ.incrementAndGet()));

        Snapshot after = snapshot("B2-blacklist-add");
        assertThat(after.total() - before.total())
                .as("LICENSE_NO 保留明文分支（15 §2-1），不是缺口，不该稀释 PHONE 行的闸门分母")
                .isZero();
    }

    @Test
    @DisplayName("切点 B2（加黑查重）：PHONE 行新增时两列都未命中，计 MATCHED")
    void b2_phoneAddIsProbedAndMatched() {
        Long ops = registerOps();
        Snapshot before = snapshot("B2-blacklist-add");
        blacklistService.add(ops, blacklistDto("PHONE", nextPhone()));

        Snapshot after = snapshot("B2-blacklist-add");
        assertThat(after.matched() - before.matched()).isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    // ------------------------------------------------------------ helpers

    /** 某切点的各结论读数快照；一切断言取两次快照的差值（计数器 JVM 内共享）。 */
    private record Snapshot(long matched, long missing, long extra, long diverged, long error) {
        long total() {
            return matched + missing + extra + diverged + error;
        }

        void assertNoMismatchSince(Snapshot before) {
            assertThat(missing - before.missing).as("不应出现 MISSING").isZero();
            assertThat(extra - before.extra).as("不应出现 EXTRA").isZero();
            assertThat(diverged - before.diverged).as("不应出现 DIVERGED").isZero();
            assertThat(error - before.error).as("影子查询不应抛异常").isZero();
        }
    }

    private Snapshot snapshot(String pointcut) {
        return new Snapshot(
                shadowReader.count(pointcut, Verdict.MATCHED),
                shadowReader.count(pointcut, Verdict.MISSING),
                shadowReader.count(pointcut, Verdict.EXTRA),
                shadowReader.count(pointcut, Verdict.DIVERGED),
                shadowReader.count(pointcut, Verdict.ERROR));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private R<LoginVo> register(String phone, String password, String role) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode(MOCK_SMS_CODE);
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(url("/api/v1/account/register"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).as("register %s 无响应体", phone).isNotNull();
        assertThat(body.getCode()).as("register %s 应成功，实际 %s", phone, body).isZero();
        return body;
    }

    private R<LoginVo> login(String phone, String password) {
        LoginDto dto = new LoginDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        R<LoginVo> body = restTemplate.exchange(url("/api/v1/account/login"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).as("login %s 无响应体", phone).isNotNull();
        return body;
    }

    /** B1/B2 的 OPS 脚手架：真注册一个 OPS 账号，requireOpsRole 查的就是它落下的 user_roles 行。 */
    private Long registerOps() {
        return register(nextPhone(), "PiiShadowOps123", "OPS").getData().getUserId();
    }

    private BlacklistAddDto blacklistDto(String type, String value) {
        BlacklistAddDto dto = new BlacklistAddDto();
        dto.setTargetType(type);
        dto.setTargetValue(value);
        dto.setReason("PII-W4 影子双查关卡测试造数");
        return dto;
    }

    private static String nextPhone() {
        return String.valueOf(PHONE_SEQ.incrementAndGet());
    }

    private void setUserHmac(Long userId, String value) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getPhoneHmac, value)
                .eq(User::getId, userId));
    }

    private void clearBlacklistHmac(Long id) {
        blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                .set(Blacklist::getTargetValueHmac, null)
                .eq(Blacklist::getId, id));
    }
}
