package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.dto.SmsCodeSendDto;
import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.TestUniq;
import com.cangchu.common.pii.PiiShadowReader.Verdict;
import com.cangchu.common.response.R;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.pricing.dto.BatchCustomerPriceDto;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.entity.PriceChangeLog;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.pricing.vo.BatchPriceResultVo;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 阶段 1 Step1 关卡：影子双查（波次 PII-W4 登录/黑名单 8 切点 + PII-W5 定价/短信 5 切点，15 §4 阶段1）。
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
 *
 * <p><b>W5 追加的 5 个切点</b>（C1 upsert 唯一键匹配 / C2 价格解析 / C3 批量调价按 rtPhone 圈选 /
 * SMS 验证码校验）沿用完全相同的两条断言：一致路径记 MATCHED，造漏填记 MISSING 且<b>主路结果分毫不变</b>
 * ——价照旧、行照旧、码照旧过。定价三例的"零行为变化"各有各的爆炸半径：C1 走成 insert 会撞唯一键、
 * C2 回退公开价是资损、C3 少圈一行是漏调价，故逐例分开断言而不是共用一条。
 * inquiry_requests 没有对应用例，因为主代码里根本没有按 rt_phone 读该表的路径（见
 * {@link PiiShadowReader} 类注释）。
 *
 * <p><b>全量日志里的 4 条非本类 mismatch 是夹具噪音，不是回填缺口</b>：
 * {@code PricingSettleScenarioTest}（C1 ×1）与 {@code PricingRtMatchScenarioTest}（C2 ×3）
 * 直接 {@code customerPriceMapper.insert} 造价行，绕过了双写切点，{@code rt_phone_hmac} 天生为
 * NULL——同 S0 波次把对账基线改走 {@code flattenBackfillBaseline()} 的那个成因。生产没有
 * mapper 造行这回事，7 天/3 天闸门读的是 prod 的 Micrometer 计数，不受测试态影响，故不追改兄弟类。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PII-W4/W5 关卡：影子双查零行为变化 / 不一致可检出")
class PiiShadowReadScenarioTest {

    /**
     * 手机号发号器，避开兄弟测试类占用的号段（S0 关卡类用 177 段），防唯一键撞车。
     *
     * <p><b>起点按本次运行随机偏移</b>：H2 每次跑都重建，Redis 不会——sms-code 端点的
     * 60s 重发冷却键 {@code sms:cd:{phoneHash}:{scene}} 会跨 JVM 存活。固定起点意味着
     * 60 秒内复跑本类必撞 41204，SMS 那两例会假红。偏移量留足 1000 万号余量。
     */
    private static final AtomicLong PHONE_SEQ =
            new AtomicLong(17600000000L + Math.floorMod(System.nanoTime(), 90_000_000L));

    /** 与 src/test/resources/application.yml 的 cangchu.sms.mock-code 一致（仅测试态短路）。 */
    private static final String MOCK_SMS_CODE = "888888";

    /**
     * 非万能验证码：mock 短路发生在 sms_codes 的 DB 读<b>之前</b>，用 888888 走注册永远碰不到
     * SMS 切点。故先经真端点发码，再把落库那行的 code 改成本值——读切点仍由真端点驱动，
     * 改的只是夹具数据，不是绕过切点。
     */
    private static final String REAL_SMS_CODE = "246813";

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID_BODY = new ParameterizedTypeReference<>() {};

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

    // ---- W5 新增：定价链 C1/C2/C3 与 sms 校验切点的脚手架 ----
    @Autowired
    private SmsCodeMapper smsCodeMapper;
    @Autowired
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

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

    // ------------------------------------------------------------ 定价链 C1 / C2 / C3（PII-W5）

    @Test
    @DisplayName("切点 C1（议价沉淀 upsert）：双写齐全时影子与旧列同指一行，upsert 语义不变")
    void c1_settle_shadowAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-C1-A", 1L);

        Snapshot before = snapshot("C1-price-settle");
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("6.60"), "PII-W5-C1-B", 1L);

        assertThat(soleCustomerPrice(f).getUnitPrice())
                .as("影子期 upsert 语义不得变：命中既有行改价，不新插")
                .isEqualByComparingTo("6.60");
        Snapshot after = snapshot("C1-price-settle");
        assertThat(after.matched() - before.matched())
                .as("C1 影子双查应记一次 MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("检出力 C1：hmac 漏填的存量价行 → 记 MISSING，且 upsert 仍复用原行（零行为变化）")
    void c1_settle_detectsMissingBackfillWithoutSplittingTheRow() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-C1-M1", 1L);
        // 造「V30 之前落库、回填还没扫到」的历史价行：旧列齐全，hmac 为 NULL
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        Snapshot before = snapshot("C1-price-settle");
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("5.50"), "PII-W5-C1-M2", 1L);

        CustomerPrice after = soleCustomerPrice(f);
        assertThat(after.getId())
                .as("影子列缺失绝不能让 upsert 走成 insert——唯一键冲突会连累 confirmByWa 整单回滚")
                .isEqualTo(rowId);
        assertThat(after.getUnitPrice()).isEqualByComparingTo("5.50");
        assertThat(snapshot("C1-price-settle").missing() - before.missing())
                .as("旧列命中而 hmac 未命中 → 必须计 MISSING")
                .isEqualTo(1);
        // 命中分支自带机会性回填，本行已自愈；无需收尾
        assertThat(after.getRtPhoneHmac()).isEqualTo(piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切点 C2（价格解析）：双写齐全时影子与旧列同指一行，专属价照常命中")
    void c2_resolvePrice_shadowAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-C2-A", 1L);

        Snapshot before = snapshot("C2-price-resolve");
        BigDecimal price = pricingService.resolvePrice(f.wholesalerId(), f.skuId(), f.rtPhone(), 1);

        assertThat(price).as("影子期成交价不得变").isEqualByComparingTo("7.70");
        Snapshot after = snapshot("C2-price-resolve");
        assertThat(after.matched() - before.matched())
                .as("写后失效缓存 → 本次必是 DB 实读，应记一次 MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("检出力 C2：hmac 漏填的价行 → 记 MISSING，且解析出的成交价一分不差")
    void c2_resolvePrice_detectsMissingBackfillWithoutChangingPrice() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-C2-M", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        Snapshot before = snapshot("C2-price-resolve");
        BigDecimal price = pricingService.resolvePrice(f.wholesalerId(), f.skuId(), f.rtPhone(), 1);

        assertThat(price)
                .as("影子列缺失时成交价仍须由明文列给出——回退公开价 9.90 就是一笔资损")
                .isEqualByComparingTo("7.70");
        assertThat(snapshot("C2-price-resolve").missing() - before.missing()).isEqualTo(1);
        // 收尾：解析路径不会机会性回填，手工补回，别把脏行留给兄弟类的对账用例
        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切点 C3（批量调价按 rtPhone 圈选）：影子圈到同一批行 id，计 MATCHED")
    void c3_batch_shadowAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        long skuB = seedSku(f.tenantId(), f.wholesalerId());
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-C3-A", 1L);
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), skuB,
                new BigDecimal("20.00"), "PII-W5-C3-B", 1L);

        Snapshot before = snapshot("C3-price-batch");
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(
                batchByPhone(f.wholesalerId(), f.rtPhone()), 1L);

        assertThat(result.getAffectedCount()).as("影子期圈选结果不得变").isEqualTo(2);
        Snapshot after = snapshot("C3-price-batch");
        assertThat(after.matched() - before.matched())
                .as("两边圈到同一组行 id → 一次 MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
    }

    @Test
    @DisplayName("检出力 C3：两行里抹掉一行 hmac → 记 MISSING，且明文列照样圈全 2 行")
    void c3_batch_detectsMissingBackfillWithoutDroppingRows() {
        PriceFixture f = seedPriceFixture();
        long skuB = seedSku(f.tenantId(), f.wholesalerId());
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-C3-M1", 1L);
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), skuB,
                new BigDecimal("20.00"), "PII-W5-C3-M2", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        Snapshot before = snapshot("C3-price-batch");
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(
                batchByPhone(f.wholesalerId(), f.rtPhone()), 1L);

        assertThat(result.getAffectedCount())
                .as("影子少圈一行也不得影响主路——漏调一行就是一笔错价")
                .isEqualTo(2);
        assertThat(snapshot("C3-price-batch").missing() - before.missing())
                .as("影子圈选是旧列结果的真子集 → MISSING")
                .isEqualTo(1);
        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切点 C3（批量调价）：走显式 ids 分支时没读明文手机号列，不进影子分母")
    void c3_batch_explicitIdsBranchIsNotProbedAtAll() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-C3-IDS", 1L);
        Long rowId = soleCustomerPrice(f).getId();

        BatchCustomerPriceDto dto = batchByPhone(f.wholesalerId(), null);
        dto.setIds(List.of(rowId));

        Snapshot before = snapshot("C3-price-batch");
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(dto, 1L);

        assertThat(result.getAffectedCount()).isEqualTo(1);
        Snapshot after = snapshot("C3-price-batch");
        assertThat(after.total() - before.total())
                .as("ids 分支根本没读 rt_phone，不该稀释「按手机号圈选」这个切点的闸门分母")
                .isZero();
    }

    // ------------------------------------------------------------ 短信码校验（PII-W5）

    @Test
    @DisplayName("切点 SMS（验证码校验）：双写齐全时影子与旧列同指一条码，注册照常通过")
    void sms_verify_shadowAgreesWithLegacy() {
        String phone = nextPhone();
        Long codeRowId = seedRealSmsCode(phone);

        Snapshot before = snapshot("SMS-verify");
        R<LoginVo> body = register(phone, "PiiShadowPass123", "TA", REAL_SMS_CODE);

        assertThat(body.getCode()).as("影子期注册必须照常成功，实际 %s", body).isZero();
        Snapshot after = snapshot("SMS-verify");
        assertThat(after.matched() - before.matched())
                .as("SMS 影子双查应记一次 MATCHED")
                .isEqualTo(1);
        after.assertNoMismatchSince(before);
        assertThat(codeRowId).isNotNull();
    }

    @Test
    @DisplayName("检出力 SMS：hmac 漏填的验证码行 → 记 MISSING，且验证码照样校验通过")
    void sms_verify_detectsMissingBackfillWithoutBreakingVerify() {
        String phone = nextPhone();
        Long codeRowId = seedRealSmsCode(phone);
        setSmsCodeHmac(codeRowId, null);

        Snapshot before = snapshot("SMS-verify");
        R<LoginVo> body = register(phone, "PiiShadowPass123", "TA", REAL_SMS_CODE);

        assertThat(body.getCode())
                .as("影子列缺失绝不能挡住验证码校验——挡住就是全员注册失败，实际 %s", body)
                .isZero();
        assertThat(snapshot("SMS-verify").missing() - before.missing())
                .as("旧列捞到码而 hmac 捞不到 → 必须计 MISSING")
                .isEqualTo(1);
        setSmsCodeHmac(codeRowId, piiCrypto.phoneHmac(phone));
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
        R<LoginVo> body = register(phone, password, role, MOCK_SMS_CODE);
        assertThat(body.getCode()).as("register %s 应成功，实际 %s", phone, body).isZero();
        return body;
    }

    /** 带验证码入参的重载：SMS 切点用例要用非万能码，才会真的落到 sms_codes 的 DB 读上。 */
    private R<LoginVo> register(String phone, String password, String role, String smsCode) {
        RegisterDto dto = new RegisterDto();
        dto.setPhone(phone);
        dto.setPassword(password);
        dto.setSmsCode(smsCode);
        dto.setRole(role);
        dto.setAgreedTerms(true);
        R<LoginVo> body = restTemplate.exchange(url("/api/v1/account/register"), HttpMethod.POST,
                new HttpEntity<>(dto), LOGIN_VO).getBody();
        assertThat(body).as("register %s 无响应体", phone).isNotNull();
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

    // ---- W5：定价链脚手架（mapper-seed 风格同 S0 关卡类，切点本身一律真调）----

    /** 一套互不干扰的定价夹具：独立租户/商户/SKU/手机号，避免与兄弟用例的圈选结果串味。 */
    private record PriceFixture(long tenantId, long wholesalerId, long skuId, String rtPhone) {}

    private PriceFixture seedPriceFixture() {
        long tenantId = seedTenant();
        long wholesalerId = seedWholesaler(tenantId);
        return new PriceFixture(tenantId, wholesalerId, seedSku(tenantId, wholesalerId), nextPhone());
    }

    private long seedTenant() {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("PIIW5仓-" + t.getId());
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhone("13800000000");
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        return t.getId();
    }

    private long seedWholesaler(long tenantId) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("PIIW5商户-" + w.getId());
        w.setOwnerUserId(snowflakeIdUtil.nextId());
        w.setStatus("ACTIVE");
        w.setSource("SELF_OPERATED");
        wholesalerMapper.insert(w);
        return w.getId();
    }

    private long seedSku(long tenantId, long wholesalerId) {
        Sku s = new Sku();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setWholesalerId(wholesalerId);
        s.setName("PIIW5品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

    /** 按手机号圈选的批量调价请求；rtPhone 传 null 即用于「显式 ids 分支」那例。 */
    private BatchCustomerPriceDto batchByPhone(long wholesalerId, String rtPhone) {
        BatchCustomerPriceDto dto = new BatchCustomerPriceDto();
        dto.setWholesalerId(wholesalerId);
        dto.setRtPhone(rtPhone);
        dto.setAdjustMode(PriceChangeLog.ADJUST_MODE_SET_VALUE);
        dto.setValue(new BigDecimal("9.99"));
        return dto;
    }

    private CustomerPrice soleCustomerPrice(PriceFixture f) {
        List<CustomerPrice> rows = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, f.wholesalerId())
                .eq(CustomerPrice::getRtPhone, f.rtPhone())
                .eq(CustomerPrice::getSkuId, f.skuId()));
        assertThat(rows).as("(wholesaler=%s, phone=%s, sku=%s) 应恰好一行",
                f.wholesalerId(), f.rtPhone(), f.skuId()).hasSize(1);
        return rows.get(0);
    }

    private void setCustomerPriceHmac(Long id, String value) {
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .set(CustomerPrice::getRtPhoneHmac, value)
                .eq(CustomerPrice::getId, id));
    }

    // ---- W5：sms_codes 脚手架 ----

    /**
     * 经真端点发一次码，再把 code 改成非万能码并返回行 id。
     *
     * <p>不这么做就测不到这个切点：测试态 {@code cangchu.sms.mock=true} 下发出的就是 888888，
     * 而 888888 会在 {@code verifySmsCode} 首行短路，永远走不到 sms_codes 的 DB 读。
     * 行本身仍由真端点写入（hmac 来自真双写切点），改的只是 code 这一个夹具字段。
     */
    private Long seedRealSmsCode(String phone) {
        SmsCodeSendDto dto = new SmsCodeSendDto();
        dto.setPhone(phone);
        dto.setScene("REGISTER");
        R<Void> body = restTemplate.exchange(url("/api/v1/account/sms-code"), HttpMethod.POST,
                new HttpEntity<>(dto), VOID_BODY).getBody();
        assertThat(body).as("sendSmsCode %s 无响应体", phone).isNotNull();
        assertThat(body.getCode()).as("sendSmsCode %s 应成功，实际 %s", phone, body).isZero();

        List<SmsCode> rows = smsCodeMapper.selectList(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone));
        assertThat(rows).as("sms_codes 中 %s 应恰好一行", phone).hasSize(1);
        Long id = rows.get(0).getId();
        smsCodeMapper.update(null, new LambdaUpdateWrapper<SmsCode>()
                .set(SmsCode::getCode, REAL_SMS_CODE)
                .eq(SmsCode::getId, id));
        return id;
    }

    private void setSmsCodeHmac(Long id, String value) {
        smsCodeMapper.update(null, new LambdaUpdateWrapper<SmsCode>()
                .set(SmsCode::getPhoneHmac, value)
                .eq(SmsCode::getId, id));
    }
}
