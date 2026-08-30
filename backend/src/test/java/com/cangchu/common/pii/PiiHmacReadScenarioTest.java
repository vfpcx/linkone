package com.cangchu.common.pii;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.LoginDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.dto.SmsCodeSendDto;
import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.TestUniq;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PII 阶段 1 Step2 关卡：非命门切读（15-pii-hardening-v2 §4 Step 2 / 波次 PII-W5 后半段）。
 *
 * <p>影子期（{@code PiiShadowReadScenarioTest}）钉的是「开着也等于没开」；本类钉的是<b>真切过去之后</b>
 * 的三件事，缺一不可：
 * <ol>
 *   <li><b>hmac 命中 == 旧列命中</b>——每个切读点都得有一条：同一份数据，切读前后主路结论逐字节相同。
 *       这是「可以切」的正面证据。</li>
 *   <li><b>hmac 未命中 == 真未命中</b>——影子期那批「造漏填 → 记 MISSING 且主路结果分毫不变」的用例
 *       <b>一条没删</b>，它们仍在 shadow 口径下跑；本类把切读后的<b>另一半语义</b>重新钉一遍：同样是漏填，
 *       切读后主路就该按未命中处理（黑名单放行、专属价回退公开价、验证码不通过、批量少圈一行）。
 *       这不是缺陷，是硬切的定义——回填有没有填全由切读<b>之前</b>的影子闸门证明，不靠运行时兜底掩盖
 *       （见 {@link PiiReadRouter} 类注释）。也正因为每一条的代价都在这里写明了，那道
 *       「pricing 全量 + 入驻黑名单用例 + E2E 45×2 全绿，观察 ≥3 天」的闸门才不是走过场。</li>
 *   <li><b>拨回即恢复</b>——模块拨回 shadow，同一份漏填数据立刻又能按明文列命中，且影子探针复活。
 *       秒级回滚不是文档里的一句话，是 {@link #rollback_moduleBackToShadowRestoresLegacyRead} 这一条。</li>
 * </ol>
 *
 * <p><b>灰度粒度是模块，不是一个全局开关</b>：{@link #moduleIsolation_pricingCutDoesNotTouchBlacklist}
 * 钉死 pricing 切了不影响 blacklist——否则「单模块拨回」就是空话。
 *
 * <p><b>默认值不许动</b>：{@link #defaults_stayOnShadowUntilGateIsMet} 守住测试态/默认配置仍是
 * {@code shadow}。本波交付的是「代码就绪 + 开关可拨」，不是「已经切了」；生产闸门没过之前，
 * 把 hmac 设成默认等于跳过闸门。
 *
 * <p><b>运行期改开关而不是起多个 Spring 上下文</b>：{@link PiiProperties} 是普通 bean，
 * 本类直接改它的 {@code readModes} 再在 {@link #restoreReadModes()} 里清干净。多开一个
 * {@code @SpringBootTest} 上下文要多付一次全量启动，且与兄弟类共享的 H2/计数器反而更难对齐。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PII-W5 关卡：Step 2 分模块切读（hmac 出结果 / 未命中即真未命中 / 秒级拨回）")
class PiiHmacReadScenarioTest {

    /**
     * 手机号发号器，独占 178 段（影子关卡类用 176、S0 类用 177），起点按本次运行随机偏移。
     * 理由同影子类：sms 冷却键在 Redis 里跨 JVM 存活，固定起点会让 60 秒内复跑撞 41204 假红。
     */
    private static final AtomicLong PHONE_SEQ =
            new AtomicLong(17800000000L + Math.floorMod(System.nanoTime(), 90_000_000L));

    /** 与 src/test/resources/application.yml 的 cangchu.sms.mock-code 一致（仅测试态短路）。 */
    private static final String MOCK_SMS_CODE = "888888";

    /** 非万能验证码：888888 在 verifySmsCode 首行短路，用它永远走不到 sms_codes 的 DB 读。 */
    private static final String REAL_SMS_CODE = "135791";

    private static final ParameterizedTypeReference<R<LoginVo>> LOGIN_VO = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<R<Void>> VOID_BODY = new ParameterizedTypeReference<>() {};

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private PiiProperties piiProperties;
    @Autowired
    private PiiReadRouter piiReadRouter;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private PiiShadowReader shadowReader;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private BlacklistService blacklistService;
    @Autowired
    private BlacklistMapper blacklistMapper;
    @Autowired
    private SmsCodeMapper smsCodeMapper;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    /** 每例跑完必须还原，否则会把切读状态漏给兄弟测试类（同一 JVM 共享上下文）。 */
    @AfterEach
    void restoreReadModes() {
        piiProperties.getReadModes().clear();
    }

    /** 把某模块拨到切读。 */
    private void cutOver(String module) {
        piiProperties.getReadModes().put(module, "hmac");
    }

    /** 把某模块拨回影子（回滚口径）。 */
    private void rollback(String module) {
        piiProperties.getReadModes().put(module, "shadow");
    }

    // ------------------------------------------------------------ 开关与默认值

    @Test
    @DisplayName("默认值：闸门未过之前，测试态与各模块默认仍是 shadow，不许把 hmac 设成默认")
    void defaults_stayOnShadowUntilGateIsMet() {
        assertThat(piiProperties.getReadMode())
                .as("全局 read-mode 应仍为 shadow（生产闸门是 pricing 全量 + 黑名单用例 + E2E 45×2 全绿且观察 ≥3 天）")
                .isEqualToIgnoringCase("shadow");
        assertThat(piiProperties.getReadModes().values().stream()
                .filter(m -> m != null && !m.isBlank()).toList())
                .as("默认不得预置任何模块的覆写——本波交付的是「可拨」，不是「已切」。"
                        + "主配那四行是空占位符（等待运维经环境变量注入），空值一律视为未登记")
                .isEmpty();
        for (String module : PiiModule.ALL) {
            assertThat(piiProperties.isHmacRead(module))
                    .as("模块 %s 默认不应处于切读", module)
                    .isFalse();
        }

        // 空占位符必须回落全局，否则主配那四行会把所有模块的读模式判成非法/未知
        piiProperties.getReadModes().put(PiiModule.PRICING, "");
        assertThat(piiProperties.readMode(PiiModule.PRICING))
                .as("空值 = 未登记，须回落全局 read-mode")
                .isEqualToIgnoringCase("shadow");
    }

    @Test
    @DisplayName("启动校验：模块名/模式写错即拒绝启动，空占位符放行（否则「拨错了但看起来正常」）")
    void startupValidation_rejectsTyposButAllowsEmptyPlaceholders() {
        piiProperties.getReadModes().put(PiiModule.PRICING, "");
        piiProperties.getReadModes().put(PiiModule.SMS, "hmac");
        piiReadRouter.validateReadModes();

        piiProperties.getReadModes().put("pricng", "hmac");
        assertThatThrownBy(() -> piiReadRouter.validateReadModes())
                .as("模块名打错会静默回落全局模式——想切的没切、想拨回的没拨回，且毫无征兆，必须拦在启动期")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知模块");
        piiProperties.getReadModes().remove("pricng");

        piiProperties.getReadModes().put(PiiModule.BLACKLIST, "hmca");
        assertThatThrownBy(() -> piiReadRouter.validateReadModes())
                .as("模式值打错同理：hmca 会被当成「不是 hmac」而静默不切读")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("取值非法");
    }

    @Test
    @DisplayName("灰度粒度：模块覆写只影响自己，未登记的模块回落全局 read-mode")
    void moduleOverride_onlyAffectsItsOwnModule() {
        cutOver(PiiModule.PRICING);

        assertThat(piiProperties.isHmacRead(PiiModule.PRICING)).isTrue();
        assertThat(piiProperties.isHmacRead(PiiModule.BLACKLIST)).isFalse();
        assertThat(piiProperties.isShadowRead(PiiModule.BLACKLIST))
                .as("未登记覆写的模块须回落全局 read-mode=shadow")
                .isTrue();
        assertThat(piiProperties.isShadowRead(PiiModule.PRICING))
                .as("已切读的模块不该再被当作影子期——没有旧列答案可比了")
                .isFalse();
    }

    // ------------------------------------------------------------ 黑名单 B1 / B2

    @Test
    @DisplayName("切读 B1（入驻命中检查）：hmac 命中 == 旧列命中，该拦的照样拦")
    void b1_hit_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        blacklistService.add(registerOps(), blacklistDto("PHONE", phone));
        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("前置：影子期本就该命中").isTrue();

        cutOver(PiiModule.BLACKLIST);

        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("切读后由 target_value_hmac 出结果，结论必须与明文列逐字节相同")
                .isTrue();
    }

    @Test
    @DisplayName("切读 B1：hmac 漏填 → 真未命中（放行）。影子期这条只记 MISSING，切读后代价落地")
    void b1_hit_missingHmacBecomesTrueMiss() {
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(registerOps(), blacklistDto("PHONE", phone));
        clearBlacklistHmac(entry.getId());

        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("影子期：hmac 漏填也不影响明文列的判定")
                .isTrue();

        cutOver(PiiModule.BLACKLIST);

        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("切读后 hmac 未命中就是真未命中——该拦的人会被放进来。"
                        + "这正是「7 天 mismatch=0」闸门要买的保险，不是可以靠兜底掩盖的事")
                .isFalse();
    }

    @Test
    @DisplayName("切读 B2（加黑查重）：hmac 命中既有 REMOVED 行 → 照常复活，不走 insert")
    void b2_add_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        Long ops = registerOps();
        Blacklist entry = blacklistService.add(ops, blacklistDto("PHONE", phone));
        markRemoved(entry.getId());

        cutOver(PiiModule.BLACKLIST);
        Blacklist revived = blacklistService.add(ops, blacklistDto("PHONE", phone));

        assertThat(revived.getId())
                .as("切读后仍须命中同一物理行并复活——查不到就会走 insert 撞唯一键")
                .isEqualTo(entry.getId());
        assertThat(revived.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("切读 B2：hmac 漏填 → 真未命中，走 insert 撞唯一键 → 50310（复活语义丢失，由 uk 兜底）")
    void b2_add_missingHmacBecomesTrueMiss() {
        String phone = nextPhone();
        Long ops = registerOps();
        Blacklist entry = blacklistService.add(ops, blacklistDto("PHONE", phone));
        markRemoved(entry.getId());
        clearBlacklistHmac(entry.getId());

        cutOver(PiiModule.BLACKLIST);

        assertThatThrownBy(() -> blacklistService.add(ops, blacklistDto("PHONE", phone)))
                .as("hmac 查不到 → 当成新条目 insert → 撞 uk_blacklist_type_value → 语义码 50310。"
                        + "对比影子期同一份数据走的是复活分支：这就是切读后 MISSING 的可观察代价")
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.BLACKLIST_ENTRY_EXISTS.getCode());

        assertThat(blacklistMapper.selectList(new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getTargetType, "PHONE")
                .eq(Blacklist::getTargetValue, phone)))
                .as("唯一键兜住了，绝不能因此多出一行")
                .hasSize(1);
    }

    @Test
    @DisplayName("切读 B2：LICENSE_NO 行 hmac 恒 NULL，切读期照走明文列，不被误伤")
    void b2_licenseNoKeepsReadingPlainColumn() {
        String license = "PIIW5" + TestUniq.tenantSimpleCode();
        Long ops = registerOps();
        blacklistService.add(ops, blacklistDto("LICENSE_NO", license));

        cutOver(PiiModule.BLACKLIST);

        assertThatThrownBy(() -> blacklistService.add(ops, blacklistDto("LICENSE_NO", license)))
                .as("切的是「手机号这一列」，不是「这张表」——LICENSE_NO 仍须由明文列查得到重复")
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.BLACKLIST_ENTRY_EXISTS.getCode());
    }

    // ------------------------------------------------------------ 短信码校验

    @Test
    @DisplayName("切读 SMS（验证码校验）：hmac 取到同一条码，注册照常通过")
    void sms_verify_hmacAgreesWithLegacy() {
        String phone = nextPhone();
        seedRealSmsCode(phone);

        cutOver(PiiModule.SMS);
        R<LoginVo> body = register(phone, "PiiHmacPass123", "TA", REAL_SMS_CODE);

        assertThat(body.getCode())
                .as("切读后由 phone_hmac 取码，校验结论必须与明文列相同，实际 %s", body)
                .isZero();
    }

    @Test
    @DisplayName("切读 SMS：hmac 漏填 → 真未命中 → 41202 验证码错误（全员注册受阻，故闸门不可跳过）")
    void sms_verify_missingHmacBecomesTrueMiss() {
        String phone = nextPhone();
        Long codeRowId = seedRealSmsCode(phone);
        setSmsCodeHmac(codeRowId, null);

        cutOver(PiiModule.SMS);
        R<LoginVo> body = register(phone, "PiiHmacPass123", "TA", REAL_SMS_CODE);

        assertThat(body.getCode())
                .as("切读后取不到码就是码不对——sms_codes 回填有洞的代价是全员注册/找回失败，实际 %s", body)
                .isEqualTo(ErrorCode.AUTH_SMS_002.getCode());

        setSmsCodeHmac(codeRowId, piiCrypto.phoneHmac(phone));
    }

    // ------------------------------------------------------------ 定价链 C1 / C2 / C3

    @Test
    @DisplayName("切读 C1（议价沉淀 upsert）：hmac 命中同一行 → 改价复用原行，不新插")
    void c1_settle_hmacAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-CUT-C1-A", 1L);
        Long rowId = soleCustomerPrice(f).getId();

        cutOver(PiiModule.PRICING);
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("6.60"), "PII-W5-CUT-C1-B", 1L);

        CustomerPrice after = soleCustomerPrice(f);
        assertThat(after.getId()).as("切读后 upsert 仍须命中同一物理行").isEqualTo(rowId);
        assertThat(after.getUnitPrice()).isEqualByComparingTo("6.60");
    }

    @Test
    @DisplayName("切读 C1：hmac 漏填 → 真未命中 → 走 insert 撞唯一键（爆炸半径：连累 confirmByWa 整单回滚）")
    void c1_settle_missingHmacBecomesTrueMiss() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-CUT-C1-M1", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        cutOver(PiiModule.PRICING);

        assertThatThrownBy(() -> pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("5.50"), "PII-W5-CUT-C1-M2", 1L))
                .as("hmac 查不到 → 当成新价行 insert → 撞 uk_custprice_wh_phone_sku。"
                        + "settleFromInquiry 跑在 confirmByWa 的事务里，这一下会连累整单（含扣库存）回滚")
                .isInstanceOf(DuplicateKeyException.class);

        CustomerPrice after = soleCustomerPrice(f);
        assertThat(after.getId()).isEqualTo(rowId);
        assertThat(after.getUnitPrice())
                .as("事务已回滚，原价一分未动")
                .isEqualByComparingTo("7.70");

        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切读 C2（价格解析）：hmac 命中 → 专属价照常，不回退公开价")
    void c2_resolve_hmacAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-CUT-C2-A", 1L);

        cutOver(PiiModule.PRICING);
        BigDecimal price = pricingService.resolvePrice(f.wholesalerId(), f.skuId(), f.rtPhone(), 1);

        assertThat(price)
                .as("切读后成交价必须与明文口径一分不差")
                .isEqualByComparingTo("7.70");
    }

    @Test
    @DisplayName("切读 C2：hmac 漏填 → 真未命中 → 回退公开价 9.90（一笔资损，故闸门要 pricing 全量绿）")
    void c2_resolve_missingHmacBecomesTrueMiss() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-CUT-C2-M", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        cutOver(PiiModule.PRICING);
        BigDecimal price = pricingService.resolvePrice(f.wholesalerId(), f.skuId(), f.rtPhone(), 1);

        assertThat(price)
                .as("切读后查不到专属价就是没有专属价，直接按公开价成交——每一单都是实打实的资损")
                .isEqualByComparingTo("9.90");

        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切读 C3（批量调价按 rtPhone 圈选）：hmac 圈到同一批行，affected 不变")
    void c3_batch_hmacAgreesWithLegacy() {
        PriceFixture f = seedPriceFixture();
        long skuB = seedSku(f.tenantId(), f.wholesalerId());
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-CUT-C3-A", 1L);
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), skuB,
                new BigDecimal("20.00"), "PII-W5-CUT-C3-B", 1L);

        cutOver(PiiModule.PRICING);
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(
                batchByPhone(f.wholesalerId(), f.rtPhone()), 1L);

        assertThat(result.getAffectedCount())
                .as("切读后圈选结果必须与明文口径相同")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("切读 C3：两行里一行 hmac 漏填 → 真的少圈一行（漏调价，明文口径下本该是 2）")
    void c3_batch_missingHmacBecomesTrueMiss() {
        PriceFixture f = seedPriceFixture();
        long skuB = seedSku(f.tenantId(), f.wholesalerId());
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-CUT-C3-M1", 1L);
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), skuB,
                new BigDecimal("20.00"), "PII-W5-CUT-C3-M2", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        cutOver(PiiModule.PRICING);
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(
                batchByPhone(f.wholesalerId(), f.rtPhone()), 1L);

        assertThat(result.getAffectedCount())
                .as("影子期这条记 MISSING 而主路照圈 2 行；切读后是真的漏掉一行，那行的价没被调到")
                .isEqualTo(1);
        assertThat(customerPriceMapper.selectById(rowId).getUnitPrice())
                .as("漏圈的那行原封不动")
                .isEqualByComparingTo("10.00");

        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    @Test
    @DisplayName("切读 C3：显式 ids 分支没读明文手机号列，切读期照走原查询，不受影响")
    void c3_batch_explicitIdsBranchIsUnaffectedByCutOver() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("10.00"), "PII-W5-CUT-C3-IDS", 1L);
        Long rowId = soleCustomerPrice(f).getId();
        setCustomerPriceHmac(rowId, null);

        BatchCustomerPriceDto dto = batchByPhone(f.wholesalerId(), null);
        dto.setIds(List.of(rowId));

        cutOver(PiiModule.PRICING);
        BatchPriceResultVo result = pricingService.doBatchCustomerInTx(dto, 1L);

        assertThat(result.getAffectedCount())
                .as("按 id 圈选与手机号哪一列无关，hmac 漏填也照调不误")
                .isEqualTo(1);

        setCustomerPriceHmac(rowId, piiCrypto.phoneHmac(f.rtPhone()));
    }

    // ------------------------------------------------------------ 回滚与模块隔离

    @Test
    @DisplayName("回滚：模块拨回 shadow，同一份漏填数据立刻又按明文列命中，影子探针同时复活")
    void rollback_moduleBackToShadowRestoresLegacyRead() {
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(registerOps(), blacklistDto("PHONE", phone));
        clearBlacklistHmac(entry.getId());

        cutOver(PiiModule.BLACKLIST);
        long probesDuringCut = shadowCount("B1-blacklist-hit");
        assertThat(blacklistService.isBlacklisted(phone, null)).isFalse();
        assertThat(shadowCount("B1-blacklist-hit") - probesDuringCut)
                .as("切读期该模块的影子探针应停摆——已经没有「旧列的答案」可比了")
                .isZero();

        rollback(PiiModule.BLACKLIST);
        long probesBeforeRollbackRead = shadowCount("B1-blacklist-hit");

        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("拨回 shadow 即恢复明文读路径，秒级、无数据迁移")
                .isTrue();
        assertThat(shadowCount("B1-blacklist-hit") - probesBeforeRollbackRead)
                .as("影子探针随之复活，闸门重新有分母")
                .isEqualTo(1);

        blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                .set(Blacklist::getTargetValueHmac, piiCrypto.phoneHmac(phone))
                .eq(Blacklist::getId, entry.getId()));
    }

    @Test
    @DisplayName("模块隔离：pricing 切读不影响 blacklist —— 否则「单模块拨回」就是空话")
    void moduleIsolation_pricingCutDoesNotTouchBlacklist() {
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(registerOps(), blacklistDto("PHONE", phone));
        clearBlacklistHmac(entry.getId());

        cutOver(PiiModule.PRICING);

        assertThat(blacklistService.isBlacklisted(phone, null))
                .as("blacklist 未拨动，仍走明文列——hmac 漏填对它毫无影响")
                .isTrue();

        blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                .set(Blacklist::getTargetValueHmac, piiCrypto.phoneHmac(phone))
                .eq(Blacklist::getId, entry.getId()));
    }

    // ------------------------------------------------------------ C4：Redis 键 HMAC 化

    @Test
    @DisplayName("切读 C4：专属价匹配缓存键改由 hmac 派生，明文手机号不再进 Redis keyspace")
    void c4_matchKey_dropsPlaintextPhoneFromRedisKey() {
        PriceFixture f = seedPriceFixture();
        pricingService.settleFromInquiry(f.wholesalerId(), f.rtPhone(), f.skuId(),
                new BigDecimal("7.70"), "PII-W5-CUT-C4-MATCH", 1L);

        cutOver(PiiModule.REDIS_KEY);
        BigDecimal price = pricingService.resolvePrice(f.wholesalerId(), f.skuId(), f.rtPhone(), 1);

        assertThat(price).as("换键不改语义，成交价照旧").isEqualByComparingTo("7.70");
        assertThat(redissonClient.getBucket(matchKey(f, piiCrypto.phoneHmac(f.rtPhone()))).isExists())
                .as("缓存应落在 hmac 派生的键上")
                .isTrue();
        assertThat(redissonClient.getBucket(matchKey(f, f.rtPhone())).isExists())
                .as("明文手机号不得再出现在 Redis key 里——这正是 C4 要堵的口子")
                .isFalse();
    }

    @Test
    @DisplayName("切读 C4：短信冷却/日限键改由 hmac 派生，限流语义不变")
    void c4_smsRateLimitKeys_derivedFromHmac() {
        String phone = nextPhone();
        cutOver(PiiModule.REDIS_KEY);
        sendSmsCode(phone);

        String hmac = piiCrypto.phoneHmac(phone);
        assertThat(redissonClient.getBucket("sms:cd:" + hmac + ":REGISTER").isExists())
                .as("60s 冷却键应落在 hmac 派生名下")
                .isTrue();
        assertThat(redissonClient.getAtomicLong("sms:daily:" + hmac + ":REGISTER").isExists())
                .as("单日上限键同样 hmac 化")
                .isTrue();
        assertThat(redissonClient.getBucket("sms:cd:" + DigestUtil.sha256Hex(phone) + ":REGISTER").isExists())
                .as("旧 sha256 键不应再被写入（旧键不清洗，但也不再产生新的）")
                .isFalse();

        R<Void> again = sendSmsCodeRaw(phone);
        assertThat(again.getCode())
                .as("限流语义不得因换键而失效：60s 内重发仍须被冷却拦下（41204），实际 %s", again)
                .isEqualTo(ErrorCode.AUTH_SMS_004.getCode());
    }

    @Test
    @DisplayName("切读 C4：登录失败计数键改由 hmac 派生，锁定语义不变（A2 的 phone_hash 查询未动）")
    void c4_loginFailKey_derivedFromHmac() {
        String phone = nextPhone();
        cutOver(PiiModule.REDIS_KEY);

        R<LoginVo> body = login(phone, "WrongPass123");
        assertThat(body.getCode())
                .as("未注册手机号统一走「账号或密码错误」，防枚举语义不变，实际 %s", body)
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_001.getCode());

        assertThat(redissonClient.getAtomicLong("login:fail:" + piiCrypto.phoneHmac(phone)).isExists())
                .as("失败计数应落在 hmac 派生的键上")
                .isTrue();
        assertThat(redissonClient.getAtomicLong("login:fail:" + DigestUtil.sha256Hex(phone)).isExists())
                .as("旧 sha256 键不应再被写入")
                .isFalse();

        redissonClient.getAtomicLong("login:fail:" + piiCrypto.phoneHmac(phone)).delete();
    }

    // ------------------------------------------------------------ helpers

    private long shadowCount(String pointcut) {
        return shadowReader.count(pointcut, Verdict.MATCHED)
                + shadowReader.count(pointcut, Verdict.MISSING)
                + shadowReader.count(pointcut, Verdict.EXTRA)
                + shadowReader.count(pointcut, Verdict.DIVERGED)
                + shadowReader.count(pointcut, Verdict.ERROR);
    }

    private String matchKey(PriceFixture f, String phonePart) {
        return "price:match:" + f.wholesalerId() + ":" + phonePart + ":" + f.skuId();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

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

    private Long registerOps() {
        R<LoginVo> body = register(nextPhone(), "PiiHmacOps123", "OPS", MOCK_SMS_CODE);
        assertThat(body.getCode()).as("OPS 脚手架注册应成功，实际 %s", body).isZero();
        return body.getData().getUserId();
    }

    private BlacklistAddDto blacklistDto(String type, String value) {
        BlacklistAddDto dto = new BlacklistAddDto();
        dto.setTargetType(type);
        dto.setTargetValue(value);
        dto.setReason("PII-W5 切读关卡测试造数");
        return dto;
    }

    private static String nextPhone() {
        return String.valueOf(PHONE_SEQ.incrementAndGet());
    }

    private void clearBlacklistHmac(Long id) {
        blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                .set(Blacklist::getTargetValueHmac, null)
                .eq(Blacklist::getId, id));
    }

    /** 造「已解除」条目：加黑接口对 REMOVED 行走复活分支，与 insert 分支的差异肉眼可辨。 */
    private void markRemoved(Long id) {
        blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                .set(Blacklist::getStatus, "REMOVED")
                .set(Blacklist::getRemovedAt, LocalDateTime.now())
                .eq(Blacklist::getId, id));
    }

    // ---- 定价脚手架（与影子关卡类同一套口径，切点一律真调）----

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
        t.setName("PIIW5切读仓-" + t.getId());
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
        w.setName("PIIW5切读商户-" + w.getId());
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
        s.setName("PIIW5切读品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

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

    // ---- sms_codes 脚手架 ----

    private R<Void> sendSmsCodeRaw(String phone) {
        SmsCodeSendDto dto = new SmsCodeSendDto();
        dto.setPhone(phone);
        dto.setScene("REGISTER");
        R<Void> body = restTemplate.exchange(url("/api/v1/account/sms-code"), HttpMethod.POST,
                new HttpEntity<>(dto), VOID_BODY).getBody();
        assertThat(body).as("sendSmsCode %s 无响应体", phone).isNotNull();
        return body;
    }

    private void sendSmsCode(String phone) {
        R<Void> body = sendSmsCodeRaw(phone);
        assertThat(body.getCode()).as("sendSmsCode %s 应成功，实际 %s", phone, body).isZero();
    }

    /** 经真端点发一次码，再把 code 改成非万能码并返回行 id（口径同影子关卡类）。 */
    private Long seedRealSmsCode(String phone) {
        sendSmsCode(phone);
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
