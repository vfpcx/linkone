package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.ChangePhoneDto;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.dto.ResetPasswordDto;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiFallbackHealer.Verdict;
import com.cangchu.common.response.R;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 阶段 1 Step3 关卡：登录链双读切换（15-pii-hardening-v2 §4 Step 3 / 波次 PII-W6）。
 *
 * <p>本类与 {@link PiiHmacReadScenarioTest}（Step 2 硬切）**口径不同，不是它的复制**：
 * Step 2 每个切点钉的是「hmac 命中 == 旧列命中」+「hmac 未命中<b>即真未命中</b>」；登录链的第二条
 * 换成了「hmac 漏填时<b>兜底自愈</b>，主路照常成功，事后 hmac 已补齐」。差别就是 W6 存在的理由：
 * 硬切在登录上的代价是那个人登不上、找不回密码、重复注册撞唯一键，且毫无征兆。
 *
 * <h3>为什么每条「一致」用例都必须断言计数</h3>
 * 这是本类最容易被写漏、也最要命的一点。双读兜底会让登录<b>无论如何都成功</b>——哪怕 hmac 查询
 * 完全失效（比如谓词写错、查了个永远不存在的值），所有登录仍会经旧列兜底跑通，纯行为断言一条都
 * 不会红。所以每条一致用例都钉 {@code HMAC_HIT +1 且 FALLBACK 不变}：这才是「真的由 hmac 列出了
 * 结果」的证据，也是 RED 变异 B 唯一杀得死的地方。同理，生产上「兜底把回填有洞永久掩盖」这件事，
 * 也只能靠 {@code pii.fallback} 这个计数暴露——测试里的道理和生产里的是同一个。
 *
 * <h3>新号注册不是洞</h3>
 * {@link #confirmedMiss_brandNewPhoneIsNotAHole} 钉死「两列都未命中」记 CONFIRMED_MISS 而非
 * FALLBACK。否则每天每个新用户注册都会往闸门指标里灌一笔，FALLBACK 恒为 0 的准入线当场作废。
 *
 * <p><b>默认值不许动</b>：login 模块的默认覆写为空、回落全局 {@code shadow}，由
 * {@link #defaults_loginFallsBackToGlobalShadow} 守住。本波交付的是「代码就绪 + 开关可拨」。
 *
 * <p>运行期改开关而不是起多个 Spring 上下文，理由同 {@link PiiHmacReadScenarioTest}。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PII-W6 关卡：Step 3 登录链双读切换（hmac 出结果 / 漏填兜底自愈 / 秒级拨回）")
class PiiLoginHmacReadScenarioTest {

    /**
     * 手机号发号器，独占 179 段（影子类 176、S0 类 177、W5 切读类 178），起点按本次运行随机偏移
     * ——理由同兄弟类：sms 冷却键在 Redis 里跨 JVM 存活，固定起点会让 60 秒内复跑撞 41204 假红。
     */
    private static final AtomicLong PHONE_SEQ =
            new AtomicLong(17900000000L + Math.floorMod(System.nanoTime(), 90_000_000L));

    /** 与 src/test/resources/application.yml 的 cangchu.sms.mock-code 一致（仅测试态短路）。 */
    private static final String MOCK_SMS_CODE = "888888";

    private static final String PASSWORD = "PiiW6Pass123";

    /** 异步补写的等待上限：补写可能要等主路事务先释放该行的写锁（H2 LOCK_TIMEOUT=10s）。 */
    private static final Duration HEAL_TIMEOUT = Duration.ofSeconds(10);

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID_BODY = new ParameterizedTypeReference<>() {};

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private PiiProperties piiProperties;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private PiiShadowReader shadowReader;
    @Autowired
    private PiiFallbackHealer fallbackHealer;
    @Autowired
    private PiiBackfillService backfillService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserService userService;

    /** 每例跑完必须还原，否则会把切读状态漏给兄弟测试类（同一 JVM 共享上下文）。 */
    @AfterEach
    void restoreReadModes() {
        piiProperties.getReadModes().clear();
    }

    // ------------------------------------------------------------ 开关与默认值

    @Test
    @DisplayName("默认值：login 模块默认无覆写、回落全局 shadow —— 交付的是「可拨」，不是「已切」")
    void defaults_loginFallsBackToGlobalShadow() {
        assertThat(PiiModule.ALL)
                .as("Step 3 起登录链必须有自己的灰度模块，否则「拨回即恢复」无从谈起")
                .contains(PiiModule.LOGIN);
        assertThat(piiProperties.getReadModes().get(PiiModule.LOGIN))
                .as("主配那行是空占位符（等运维经 PII_READ_MODE_LOGIN 注入），默认不得预置覆写")
                .satisfiesAnyOf(v -> assertThat(v).isNull(), v -> assertThat(v).isBlank());
        assertThat(piiProperties.isHmacRead(PiiModule.LOGIN))
                .as("生产闸门未过之前，登录链默认不得处于切读")
                .isFalse();
        assertThat(piiProperties.isShadowRead(PiiModule.LOGIN))
                .as("默认须回落全局 read-mode=shadow —— Step 1 的七天闸门还得靠这个探针供分母")
                .isTrue();
    }

    // ------------------------------------------------------------ A1 注册查重

    @Test
    @DisplayName("切读 A1（注册查重）：hmac 命中 == 旧列命中，重复注册照样 40004")
    void a1_register_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        registerOk(phone);

        cutOverLogin();
        Counts before = fallback("A1-register");
        R<LoginVo> again = register(phone);

        assertThat(again.getCode())
                .as("切读后由 phone_hmac 查重，结论必须与 phone_hash 逐字节相同，实际 %s", again)
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_004.getCode());
        assertHmacServedTheAnswer("A1-register", before);
    }

    @Test
    @DisplayName("切读 A1：hmac 漏填 → 双读兜底，仍拦下重复注册，事后 hmac 已补齐")
    void a1_register_missingHmacIsHealedByFallback() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        clearUserHmac(userId);

        cutOverLogin();
        Counts before = fallback("A1-register");
        R<LoginVo> again = register(phone);

        assertThat(again.getCode())
                .as("硬切在这里会「查不到 → 当成新号 → insert」，撞 uk_users_phone_hash 变成 500，"
                        + "或在没有唯一键的表上直接造出重号。双读兜底必须拦住，实际 %s", again)
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_004.getCode());
        assertFallbackHealed("A1-register", before, userId, phone);
    }

    // ------------------------------------------------------------ A2 密码登录（命门）

    @Test
    @DisplayName("切读 A2（密码登录）：hmac 命中 == 旧列命中，登录照常成功")
    void a2_login_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        registerOk(phone);

        cutOverLogin();
        Counts before = fallback("A2-login");
        R<LoginVo> body = login(phone);

        assertThat(body.getCode()).as("切读后登录必须与明文口径一致，实际 %s", body).isZero();
        assertThat(body.getData().getToken()).isNotBlank();
        assertHmacServedTheAnswer("A2-login", before);
    }

    @Test
    @DisplayName("切读 A2：hmac 漏填 → 双读兜底，登录照常成功（硬切在这里就是这个人登不上），事后已补齐")
    void a2_login_missingHmacIsHealedByFallback() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        clearUserHmac(userId);

        cutOverLogin();
        Counts before = fallback("A2-login");
        R<LoginVo> body = login(phone);

        assertThat(body.getCode())
                .as("这是整个 W6 的论点：硬切下 hmac 漏填 = 用户被判「账号不存在」，防枚举语义下还会"
                        + "统一返回「账号或密码错误」并累加失败计数，5 次后直接锁定 30 分钟——"
                        + "而他做什么都改变不了。双读兜底必须让他照常登进来，实际 %s", body)
                .isZero();
        assertThat(body.getData().getUserId()).isEqualTo(userId);
        assertFallbackHealed("A2-login", before, userId, phone);
    }

    // ------------------------------------------------------------ A3 找回密码

    @Test
    @DisplayName("切读 A3（找回密码）：hmac 命中 == 旧列命中，密码确实被改掉")
    void a3_resetPassword_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        String before = passwordHashOf(userId);

        cutOverLogin();
        Counts counts = fallback("A3-reset-password");
        R<Void> body = resetPassword(phone, "PiiW6Reset123");

        assertThat(body.getCode()).as("找回密码应成功，实际 %s", body).isZero();
        assertThat(passwordHashOf(userId)).as("切读后密码必须真的被改掉").isNotEqualTo(before);
        assertHmacServedTheAnswer("A3-reset-password", counts);
    }

    @Test
    @DisplayName("切读 A3：hmac 漏填 → 双读兜底，密码照改（硬切在这里是静默 return，用户毫无提示）")
    void a3_resetPassword_missingHmacIsHealedByFallback() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        String hashBefore = passwordHashOf(userId);
        clearUserHmac(userId);

        cutOverLogin();
        Counts counts = fallback("A3-reset-password");
        R<Void> body = resetPassword(phone, "PiiW6Reset456");

        assertThat(body.getCode())
                .as("防枚举语义下「查不到账号」本就返回成功，所以硬切在这里失败得**完全无声**："
                        + "接口 200、用户以为改好了、下次仍用旧密码登不上。实际 %s", body)
                .isZero();
        assertThat(passwordHashOf(userId))
                .as("兜底必须让密码真的落库，否则上面那个 200 就是骗人的")
                .isNotEqualTo(hashBefore);
        assertFallbackHealed("A3-reset-password", counts, userId, phone);
    }

    // ------------------------------------------------------------ A4 换绑查重

    @Test
    @DisplayName("切读 A4（换绑查重）：hmac 命中 == 旧列命中，换到已占用号仍报 40004")
    void a4_changePhone_hmacAgreesWithLegacy() {
        String occupied = nextPhone();
        registerOk(occupied);
        String mover = nextPhone();
        String token = registerToken(mover);

        cutOverLogin();
        Counts before = fallback("A4-change-phone");
        R<Void> body = changePhone(token, occupied);

        assertThat(body.getCode())
                .as("切读后仍须查得到占用者，实际 %s", body)
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_004.getCode());
        assertHmacServedTheAnswer("A4-change-phone", before);
    }

    @Test
    @DisplayName("切读 A4：hmac 漏填 → 双读兜底，仍报 40004（硬切会放行换绑并撞唯一键退化成 500）")
    void a4_changePhone_missingHmacIsHealedByFallback() {
        String occupied = nextPhone();
        Long occupiedId = registerOk(occupied);
        clearUserHmac(occupiedId);
        String mover = nextPhone();
        String token = registerToken(mover);

        cutOverLogin();
        Counts before = fallback("A4-change-phone");
        R<Void> body = changePhone(token, occupied);

        assertThat(body.getCode())
                .as("硬切下查重放空 → updateById 写入重复 phone_hash → 语义码「新手机号已被注册」"
                        + "退化成唯一键异常（500）。双读兜底必须保住这个错误码，实际 %s", body)
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_004.getCode());
        assertFallbackHealed("A4-change-phone", before, occupiedId, occupied);
    }

    // ------------------------------------------------------------ A5 RT 免密登录

    @Test
    @DisplayName("切读 A5（RT 免密）：hmac 命中 == 旧列命中，复用原账号 isNew=false")
    void a5_rtSmsLogin_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        Long userId = registerOk(phone);

        cutOverLogin();
        Counts before = fallback("A5-rt-sms-login");
        R<LoginVo> body = rtSmsLogin(phone);

        assertThat(body.getCode()).as("RT 免密登录应成功，实际 %s", body).isZero();
        assertThat(body.getData().getUserId()).isEqualTo(userId);
        assertThat(body.getData().getIsNew()).as("已有账号不得被判成新用户").isFalse();
        assertHmacServedTheAnswer("A5-rt-sms-login", before);
    }

    @Test
    @DisplayName("切读 A5：hmac 漏填 → 双读兜底复用原账号（硬切会重新建号，老账号订单/专属价全失联）")
    void a5_rtSmsLogin_missingHmacIsHealedByFallback() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        clearUserHmac(userId);

        cutOverLogin();
        Counts before = fallback("A5-rt-sms-login");
        R<LoginVo> body = rtSmsLogin(phone);

        assertThat(body.getCode()).as("兜底后仍应成功，实际 %s", body).isZero();
        assertThat(body.getData().getUserId())
                .as("硬切下这里会走「自动注册 RT 账号」分支：先撞 uk_users_phone_hash，"
                        + "就算撞不上也意味着这个人的订单、专属价、角色绑定全挂在旧 userId 上再也找不回")
                .isEqualTo(userId);
        assertThat(body.getData().getIsNew()).isFalse();
        assertFallbackHealed("A5-rt-sms-login", before, userId, phone);
    }

    // ------------------------------------------------------------ A6 代建开号

    @Test
    @DisplayName("切读 A6（代建开号）：hmac 命中 == 旧列命中，幂等复用同一行")
    void a6_ensureUser_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        Long first = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        cutOverLogin();
        Counts before = fallback("A6-ensure-user");
        Long second = userService.ensureUserByPhone(phone, "WA_PROVISION").userId();

        assertThat(second).as("切读后幂等语义不得改变").isEqualTo(first);
        assertHmacServedTheAnswer("A6-ensure-user", before);
    }

    @Test
    @DisplayName("切读 A6：hmac 漏填 → 双读兜底保住幂等（硬切会重复建号并撞唯一键）")
    void a6_ensureUser_missingHmacIsHealedByFallback() {
        String phone = nextPhone();
        Long first = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        clearUserHmac(first);

        cutOverLogin();
        Counts before = fallback("A6-ensure-user");
        Long second = userService.ensureUserByPhone(phone, "WA_PROVISION").userId();

        assertThat(second)
                .as("硬切下幂等判定落空 → 走建号分支 → 撞 uk_users_phone_hash，"
                        + "代建开号（WA/OPS 侧入驻）当场失败")
                .isEqualTo(first);
        assertFallbackHealed("A6-ensure-user", before, first, phone);
    }

    // ------------------------------------------------------------ 闸门口径本身

    @Test
    @DisplayName("闸门口径：新号注册两列都未命中 → 记 CONFIRMED_MISS 而非 FALLBACK（否则准入线当场作废）")
    void confirmedMiss_brandNewPhoneIsNotAHole() {
        String phone = nextPhone();

        cutOverLogin();
        Counts before = fallback("A1-register");
        R<LoginVo> body = register(phone);

        assertThat(body.getCode()).as("新号注册应成功，实际 %s", body).isZero();
        assertThat(fallback("A1-register").confirmedMiss() - before.confirmedMiss())
                .as("hmac 未命中且旧列也未命中 = 真未命中，这是注册的常态")
                .isEqualTo(1);
        assertThat(fallback("A1-register").fallback() - before.fallback())
                .as("真未命中绝不能计入 FALLBACK——否则每天每个新用户都往闸门里灌一笔，"
                        + "「切读后 FALLBACK 恒为 0」这条准入线就永远不可能成立")
                .isZero();
    }

    @Test
    @DisplayName("指标分家：切读期影子探针停摆、兜底计数接管；两个指标互不串味")
    void metrics_fallbackDoesNotPolluteShadowCounter() {
        String phone = nextPhone();
        registerOk(phone);

        cutOverLogin();
        long shadowBefore = shadowTotal("A2-login");
        Counts before = fallback("A2-login");

        assertThat(login(phone).getCode()).isZero();

        assertThat(shadowTotal("A2-login") - shadowBefore)
                .as("切读期该模块的影子探针必须停摆——已经没有「旧列的答案」可比，"
                        + "再计数就是拿 hmac 跟自己比。这也正是七天闸门分母归零、"
                        + "必须由 pii.fallback 接棒的那个时刻")
                .isZero();
        assertThat(fallback("A2-login").hit() - before.hit())
                .as("接棒的是 pii.fallback，不是 pii.shadow：两个指标名不同、结论枚举不同，不可混算")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ 回滚与模块隔离

    @Test
    @DisplayName("回滚：login 拨回 shadow，立刻恢复 phone_hash 读路径，影子探针同时复活")
    void rollback_loginBackToShadowRestoresLegacyRead() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        clearUserHmac(userId);

        cutOverLogin();
        Counts cutCounts = fallback("A2-login");
        assertThat(login(phone).getCode()).as("切读期靠兜底登进来").isZero();
        assertThat(fallback("A2-login").fallback() - cutCounts.fallback())
                .as("前置：这次登录确实走了兜底")
                .isEqualTo(1);
        assertThat(fallbackHealer.awaitQuiescence(HEAL_TIMEOUT)).isTrue();

        piiProperties.getReadModes().put(PiiModule.LOGIN, "shadow");
        long shadowBefore = shadowTotal("A2-login");
        Counts fallbackBefore = fallback("A2-login");

        assertThat(login(phone).getCode())
                .as("拨回 shadow 即恢复明文读路径，秒级、无数据迁移")
                .isZero();
        assertThat(shadowTotal("A2-login") - shadowBefore)
                .as("影子探针随之复活，七天闸门重新有分母")
                .isEqualTo(1);
        assertThat(fallback("A2-login").total() - fallbackBefore.total())
                .as("拨回后兜底计数不再增长——它只在切读期才有意义")
                .isZero();
    }

    @Test
    @DisplayName("模块隔离：login 切读不影响其余模块 —— 否则「只拨回出事的那块」就是空话")
    void moduleIsolation_loginCutDoesNotTouchOtherModules() {
        cutOverLogin();

        assertThat(piiProperties.isHmacRead(PiiModule.LOGIN)).isTrue();
        assertThat(piiProperties.isHmacRead(PiiModule.BLACKLIST)).isFalse();
        assertThat(piiProperties.isHmacRead(PiiModule.SMS)).isFalse();
        assertThat(piiProperties.isHmacRead(PiiModule.PRICING)).isFalse();
        assertThat(piiProperties.isHmacRead(PiiModule.REDIS_KEY)).isFalse();
        assertThat(piiProperties.isShadowRead(PiiModule.BLACKLIST))
                .as("未登记覆写的模块须回落全局 read-mode=shadow，影子探针照跑")
                .isTrue();
        assertThat(piiProperties.isShadowRead(PiiModule.LOGIN))
                .as("已切读的模块不该再被当作影子期")
                .isFalse();
    }

    // ------------------------------------------------------------ 补写口径

    @Test
    @DisplayName("补写是 CAS：已有 hmac 的行不被覆盖（与批量回填同一份幂等口径）")
    void heal_isCasAndNeverOverwritesAnExistingHmac() {
        String phone = nextPhone();
        Long userId = registerOk(phone);
        String original = hmacOf(userId);
        assertThat(original).as("前置：双写应已填好 hmac").isNotNull();

        // 拿另一个号去补写同一行：若不是 CAS，就会把正确的 hmac 改成错的
        PiiBackfillService.HealResult result = backfillService.healUserHmac(userId, nextPhone());

        assertThat(result)
                .as("CAS 条件 hmac IS NULL 必须挡住这次写入——并发双写/批量回填与兜底补写"
                        + "会抢同一行，后到者影响 0 行而不是覆盖")
                .isEqualTo(PiiBackfillService.HealResult.NOOP);
        assertThat(hmacOf(userId)).as("原值分毫未动").isEqualTo(original);
    }

    // ------------------------------------------------------------ 断言组合

    /** 「一致」用例的共同断言：结果由 hmac 列出，且没有偷偷走兜底。 */
    private void assertHmacServedTheAnswer(String pointcut, Counts before) {
        Counts after = fallback(pointcut);
        assertThat(after.hit() - before.hit())
                .as("必须真的由 hmac 列出结果。只断言业务结果是不够的——双读兜底会让 hmac 侧"
                        + "彻底失效时登录照样成功，纯行为断言一条都不会红")
                .isEqualTo(1);
        assertThat(after.fallback() - before.fallback())
                .as("hmac 已命中就不该有兜底：这里一旦 >0，说明切读根本没发生或谓词跑偏了")
                .isZero();
    }

    /** 「漏填兜底」用例的共同断言：记了账、补了列。 */
    private void assertFallbackHealed(String pointcut, Counts before, Long userId, String phone) {
        Counts after = fallback(pointcut);
        assertThat(after.fallback() - before.fallback())
                .as("兜底必须记一笔：这是回填有洞的**唯一**证据。主路照常成功、日志无异常、"
                        + "监控无毛刺——不记这一笔，缺陷就被兜底永久掩盖，"
                        + "Step 1 的七天闸门从此再没有归零的一天")
                .isEqualTo(1);
        assertThat(after.hit() - before.hit())
                .as("这一路 hmac 未命中，不该计 HMAC_HIT")
                .isZero();

        assertThat(fallbackHealer.awaitQuiescence(HEAL_TIMEOUT))
                .as("异步补写应在 %s 内跑完", HEAL_TIMEOUT)
                .isTrue();
        assertThat(hmacOf(userId))
                .as("自愈：兜底顺手把这一行的 hmac 补上，下次登录就走 hmac 直连了。"
                        + "但它**不抵消** FALLBACK 那笔账——自愈说明这行以后不出事，"
                        + "不说明当初的闸门是对的")
                .isEqualTo(piiCrypto.phoneHmac(phone));
        assertThat(fallback(pointcut).healed() - before.healed())
                .as("补写结论应记为 HEALED")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ helpers

    private void cutOverLogin() {
        piiProperties.getReadModes().put(PiiModule.LOGIN, "hmac");
    }

    /** {@code pii.fallback} 的单切点读数快照。 */
    private record Counts(long hit, long confirmedMiss, long fallback, long healed) {
        long total() {
            return hit + confirmedMiss + fallback + healed;
        }
    }

    private Counts fallback(String pointcut) {
        return new Counts(
                fallbackHealer.count(pointcut, Verdict.HMAC_HIT),
                fallbackHealer.count(pointcut, Verdict.CONFIRMED_MISS),
                fallbackHealer.count(pointcut, Verdict.FALLBACK),
                fallbackHealer.count(pointcut, Verdict.HEALED));
    }

    /** {@code pii.shadow} 在该切点上的全部读数（影子探针是否还在跑）。 */
    private long shadowTotal(String pointcut) {
        return shadowReader.count(pointcut, PiiShadowReader.Verdict.MATCHED)
                + shadowReader.count(pointcut, PiiShadowReader.Verdict.MISSING)
                + shadowReader.count(pointcut, PiiShadowReader.Verdict.EXTRA)
                + shadowReader.count(pointcut, PiiShadowReader.Verdict.DIVERGED)
                + shadowReader.count(pointcut, PiiShadowReader.Verdict.ERROR);
    }

    private static String nextPhone() {
        return String.valueOf(PHONE_SEQ.incrementAndGet());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private R<LoginVo> register(String phone) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(PASSWORD);
        dto.setSmsCode(MOCK_SMS_CODE);
        dto.setRole("TA");
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(url("/api/v1/account/register"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).as("register %s 无响应体", phone).isNotNull();
        return body;
    }

    private Long registerOk(String phone) {
        R<LoginVo> body = register(phone);
        assertThat(body.getCode()).as("脚手架注册应成功，实际 %s", body).isZero();
        return body.getData().getUserId();
    }

    private String registerToken(String phone) {
        R<LoginVo> body = register(phone);
        assertThat(body.getCode()).as("脚手架注册应成功，实际 %s", body).isZero();
        return body.getData().getToken();
    }

    private R<LoginVo> login(String phone) {
        LoginDto dto = new LoginDto();
        dto.setPhone(phone);
        dto.setPassword(PASSWORD);
        R<LoginVo> body = restTemplate.exchange(url("/api/v1/account/login"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).as("login %s 无响应体", phone).isNotNull();
        return body;
    }

    private R<Void> resetPassword(String phone, String newPassword) {
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setPhone(phone);
        dto.setSmsCode(MOCK_SMS_CODE);
        dto.setNewPassword(newPassword);
        R<Void> body = restTemplate.exchange(url("/api/v1/account/password/reset"), HttpMethod.POST,
                new HttpEntity<>(dto), VOID_BODY).getBody();
        assertThat(body).as("resetPassword %s 无响应体", phone).isNotNull();
        return body;
    }

    private R<Void> changePhone(String token, String newPhone) {
        ChangePhoneDto dto = new ChangePhoneDto();
        dto.setPassword(PASSWORD);
        dto.setOldSmsCode(MOCK_SMS_CODE);
        dto.setNewPhone(newPhone);
        dto.setNewSmsCode(MOCK_SMS_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        R<Void> body = restTemplate.exchange(url("/api/v1/account/phone"), HttpMethod.PUT,
                new HttpEntity<>(dto, headers), VOID_BODY).getBody();
        assertThat(body).as("changePhone → %s 无响应体", newPhone).isNotNull();
        return body;
    }

    private R<LoginVo> rtSmsLogin(String phone) {
        R<LoginVo> body = restTemplate.exchange(
                url("/api/v1/account/login/rt?phone=" + phone + "&code=" + MOCK_SMS_CODE),
                HttpMethod.POST, new HttpEntity<>(null), LOGIN_VO).getBody();
        assertThat(body).as("rtSmsLogin %s 无响应体", phone).isNotNull();
        return body;
    }

    /** 造「回填漏了这一行」：把 hmac 列抹成 NULL（同兄弟类对 blacklist/sms/pricing 的手法）。 */
    private void clearUserHmac(Long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getPhoneHmac, null)
                .eq(User::getId, userId));
    }

    private String hmacOf(Long userId) {
        return userMapper.selectById(userId).getPhoneHmac();
    }

    private String passwordHashOf(Long userId) {
        return userMapper.selectById(userId).getPasswordHash();
    }
}
