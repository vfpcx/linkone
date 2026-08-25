package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.dto.ChangePhoneDto;
import com.cangchu.account.dto.RegisterDto;
import com.cangchu.account.dto.SmsCodeSendDto;
import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.TestUniq;
import com.cangchu.common.response.R;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.service.InquiryService;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.BlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 阶段 0 关卡测试：双写正确性 + 存量回填幂等 + 对账（task_plan「PII-S0 剩余」）。
 *
 * <p><b>为什么必须有这一类</b>：测试态 {@code write-mode=dual} 已开，全量 408 用例都走过双写
 * 代码路径——但没有一条断言 hmac <b>写对了</b>。408 绿只证明「不炸」，不证明「对」。
 * 本类把「对」这件事钉住。
 *
 * <p><b>期望值独立实现</b>：{@link #expectHmac} 用 JDK 原生 Mac 自己算一遍，<b>不复用
 * {@link PiiCrypto}</b>。若复用，PiiCrypto 算错时测试会跟着一起错，等于没测。
 *
 * <p><b>共享库自洽</b>：H2 内存库在整个 JVM 内跨测试类共享，故所有断言均针对本类自己造的行
 * （按 id 定位），凡是全局计数的断言（对账）都先跑一次回填把基线拉平，避免受兄弟测试类残留影响。
 *
 * <p><b>切点覆盖（S1 准入门槛）</b>：S1 影子双查要改读路径，切点无断言即无回归网。本类逐个钉住
 * 15 §1.2 列出的双写切点——A1 注册（经 {@code createUser} 单一建号入口）、A4 换绑、A6 代建开号、
 * B2 加黑与复活。A1/A4 经 HTTP 走完整流程（mock 短信码 {@value #MOCK_SMS_CODE}，见
 * {@code src/test/resources/application.yml} 的 {@code cangchu.sms.mock}），B2 经
 * {@link BlacklistService#add} 真调并带 OPS 角色，不再用 mapper 造行绕过业务语义。
 *
 * <p><b>V30 补做的三表</b>（task_plan「S1 缺口」）：customer_prices / sms_codes /
 * inquiry_requests 的切点同样逐个真调——C1 议价沉淀经 {@link PricingService#settleFromInquiry}
 * （新建与命中既有行两条分支都钉，后者是存量行唯一的机会性回填点）、SMS 经真端点
 * {@code POST /api/v1/account/sms-code}、C2 经 {@link InquiryService#submitByRt}。
 * 一律不用 mapper 造行代替切点，否则断言的是造数而非双写。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("PII-S0 关卡：双写正确性 / 回填幂等 / 对账")
class PiiDualWriteBackfillScenarioTest {

    /** 测试态固定密钥，与 src/test/resources/application.yml 的 cangchu.pii.hmac-key 一致。 */
    private static final String TEST_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 与 application.yml 登记的 cangchu.pii.hmac-kat 一致（HMAC 的已知答案）。 */
    private static final String REGISTERED_KAT =
            "2aff2a1ede191cf2fd4af900d10f48d1e55f31e69dae59d7938c1ac349641534";

    /** 手机号发号器，避开兄弟测试类占用的号段，防唯一键撞车。 */
    private static final AtomicLong PHONE_SEQ = new AtomicLong(17700000000L);

    /** 与 src/test/resources/application.yml 的 cangchu.sms.mock-code 一致（仅测试态短路）。 */
    static final String MOCK_SMS_CODE = "888888";

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
    private PiiCrypto piiCrypto;
    @Autowired
    private PiiProperties piiProperties;
    @Autowired
    private PiiBackfillService backfillService;
    /** 用容器里那个 ObjectMapper——它才是真正序列化响应的那个，裸 new 测不出真实形状。 */
    @Autowired
    private ObjectMapper objectMapper;

    // ---- V30 三表（定价链 / 短信校验 / 询价）的切点与脚手架依赖 ----
    @Autowired
    private CustomerPriceMapper customerPriceMapper;
    @Autowired
    private SmsCodeMapper smsCodeMapper;
    @Autowired
    private InquiryRequestMapper inquiryRequestMapper;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private WholesalerMapper wholesalerMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    // ------------------------------------------------------------ 算法锚定

    @Test
    @DisplayName("KAT：HMAC 算法与登记的已知答案一致（独立实现交叉验证）")
    void kat_algorithmAnchoredByIndependentImplementation() {
        // 三方互证：PiiCrypto、测试内独立实现、配置里登记的期望值
        assertThat(piiCrypto.phoneHmac(PiiCrypto.KAT_VECTOR)).isEqualTo(REGISTERED_KAT);
        assertThat(expectHmac(PiiCrypto.KAT_VECTOR)).isEqualTo(REGISTERED_KAT);
    }

    @Test
    @DisplayName("规范化单入口：首尾空白等价、空白视为无值")
    void normalize_trimsAndTreatsBlankAsAbsent() {
        String phone = nextPhone();
        assertThat(piiCrypto.phoneHmac("  " + phone + "  ")).isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.phoneHmac("   ")).isNull();
        assertThat(piiCrypto.phoneHmac(null)).isNull();
    }

    // ------------------------------------------------------------ 双写正确性

    @Test
    @DisplayName("切点 A6（代建开号）：ensureUserByPhone 写入的 hmac 等于独立重算值")
    void dualWrite_a6_ensureUserByPhone_writesCorrectHmac() {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhoneHmac())
                .as("A6 切点必须双写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
    }

    @Test
    @DisplayName("切点 A6 幂等复用：同号第二次 ensure 不产生新行，hmac 保持正确")
    void dualWrite_a6_idempotentReuseKeepsHmac() {
        String phone = nextPhone();
        Long first = userService.ensureUserByPhone(phone, "WA_PROVISION").userId();
        Long second = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        assertThat(second).isEqualTo(first);
        assertThat(userMapper.selectById(first).getPhoneHmac()).isEqualTo(expectHmac(phone));
    }

    @Test
    @DisplayName("切点 A1（注册）：注册落库的 hmac 等于独立重算值，legacy 索引列不受影响")
    void dualWrite_a1_register_writesCorrectHmac() {
        String phone = nextPhone();
        Long userId = register(phone, "PiiA1Pass123", "TA").getData().getUserId();

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhone()).isEqualTo(phone);
        assertThat(saved.getPhoneHmac())
                .as("A1 切点必须双写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
        assertThat(saved.getPhoneHash())
                .as("红线：S0 不动读路径，sha256 索引列必须照旧写入")
                .isEqualTo(expectSha256Hex(phone));
    }

    @Test
    @DisplayName("切点 A4（换绑）：hmac 跟着新号重算，旧号 hmac 不得残留")
    void dualWrite_a4_changePhone_hmacFollowsNewPhone() {
        String oldPhone = nextPhone();
        String newPhone = nextPhone();
        String password = "PiiA4Pass123";
        R<LoginVo> registered = register(oldPhone, password, "TA");
        Long userId = registered.getData().getUserId();
        assertThat(userMapper.selectById(userId).getPhoneHmac()).isEqualTo(expectHmac(oldPhone));

        changePhone(registered.getData().getToken(), password, newPhone);

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhone()).isEqualTo(newPhone);
        assertThat(saved.getPhoneHmac())
                .as("换绑后 hmac 必须重算为新号；仍等于旧号值即说明影子列没跟着更新")
                .isEqualTo(expectHmac(newPhone))
                .isNotEqualTo(expectHmac(oldPhone));
        assertThat(saved.getPhoneHash())
                .as("红线：sha256 索引列同样必须换成新号，两列不得脱节")
                .isEqualTo(expectSha256Hex(newPhone));
    }

    @Test
    @DisplayName("切点 B2（加黑）：OPS 经 BlacklistService.add 落库的 PHONE 行带正确 hmac")
    void dualWrite_b2_blacklistAdd_writesCorrectHmac() {
        Long opsUserId = registerOps();
        String phone = nextPhone();

        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));

        assertThat(blacklistMapper.selectById(entry.getId()).getTargetValueHmac())
                .as("B2 加黑切点必须双写 hmac")
                .isEqualTo(expectHmac(phone));
    }

    @Test
    @DisplayName("切点 B2（加黑）：LICENSE_NO 行不进手机号盲索引，hmac 恒 NULL")
    void dualWrite_b2_blacklistAdd_licenseRowKeepsHmacNull() {
        Long opsUserId = registerOps();

        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("LICENSE_NO", "TESTLIC" + nextPhone()));

        assertThat(blacklistMapper.selectById(entry.getId()).getTargetValueHmac())
                .as("LICENSE_NO 不是手机号，不得写入手机号盲索引（15 §2-1）")
                .isNull();
    }

    @Test
    @DisplayName("切点 B2（复活）：REMOVED 存量行复活时机会性补齐 hmac，复用原行不新插")
    void dualWrite_b2_reviveBackfillsHmacOnLegacyRow() {
        Long opsUserId = registerOps();
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));
        blacklistService.remove(opsUserId, entry.getId());
        // 抹掉 hmac，模拟 V27 上线前就加黑、解除过的存量行——复活分支得自己把盲索引补回来
        clearBlacklistHmac(entry.getId());

        try {
            Blacklist revived = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));

            assertThat(revived.getId())
                    .as("uk_blacklist_type_value 在，复活必须复用原行而非新插")
                    .isEqualTo(entry.getId());
            Blacklist saved = blacklistMapper.selectById(entry.getId());
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            // 注：不断言 removed_at 被清空——add 里的 setRemovedAt(null) 被 MP 的 null 跳过策略吞了，
            // 残留是既有缺陷（见 findings「B2 复活 removed_at 残留」），与 PII 双写无关，不在本类范围。
            assertThat(saved.getTargetValueHmac())
                    .as("复活分支是存量行唯一的机会性回填点，漏写即留一个盲索引空洞")
                    .isEqualTo(expectHmac(phone));
        } finally {
            // 断言失败也不把脏行留给对账用例
            backfillService.backfillBlacklist(500);
        }
    }

    // ---- V30 补做的三表：定价链 / 短信校验 / 询价 ----

    @Test
    @DisplayName("切点 C1（议价沉淀·新建）：settleFromInquiry 插入的行带正确 rt_phone_hmac")
    void dualWrite_c1_settleFromInquiry_writesCorrectHmac() {
        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();

        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("7.70"), "PII-C1-NEW", 1L);

        assertThat(soleCustomerPrice(wid, rtPhone, skuId).getRtPhoneHmac())
                .as("C1 切点必须双写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(rtPhone));
    }

    @Test
    @DisplayName("切点 C1（议价沉淀·命中既有行）：存量行 hmac 为 NULL 时机会性补齐，不新插行")
    void dualWrite_c1_settleFromInquiry_backfillsHmacOnLegacyRow() {
        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();
        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("7.70"), "PII-C1-A", 1L);
        CustomerPrice first = soleCustomerPrice(wid, rtPhone, skuId);
        // 抹掉 hmac，模拟 V30 上线前就存在的存量行——upsert 命中分支得自己把盲索引补回来
        clearCustomerPriceHmac(first.getId());

        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("6.60"), "PII-C1-B", 1L);

        CustomerPrice revised = soleCustomerPrice(wid, rtPhone, skuId);
        assertThat(revised.getId())
                .as("唯一键 (wholesaler, rt_phone, sku) 在，第二次沉淀必须复用原行而非新插")
                .isEqualTo(first.getId());
        assertThat(revised.getUnitPrice()).isEqualByComparingTo("6.60");
        assertThat(revised.getRtPhoneHmac())
                .as("命中既有行是存量行唯一的机会性回填点，漏写即留一个盲索引空洞")
                .isEqualTo(expectHmac(rtPhone));
    }

    @Test
    @DisplayName("切点 SMS（发码）：sms-code 端点落库的行带正确 phone_hmac")
    void dualWrite_sms_sendSmsCode_writesCorrectHmac() {
        String phone = nextPhone();

        sendSmsCode(phone, "REGISTER");

        assertThat(soleSmsCode(phone).getPhoneHmac())
                .as("SMS 切点必须双写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
    }

    @Test
    @DisplayName("切点 C2（RT 提交询价）：submitByRt 落库的行带正确 rt_phone_hmac")
    void dualWrite_c2_submitByRt_writesCorrectHmac() {
        long tenantId = seedTenant();
        long storeId = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        seedStock(tenantId, wid, skuId, 100);
        String rtPhone = nextPhone();

        inquiryService.submitByRt(submitDto(storeId, wid, skuId, rtPhone, 20));

        assertThat(soleInquiry(wid, rtPhone).getRtPhoneHmac())
                .as("C2 切点必须双写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(rtPhone));
    }

    @Test
    @DisplayName("红线：hmac 影子列不得出现在实体直出的 JSON（响应形状零变化）")
    void hmacColumn_isJsonIgnored() throws Exception {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        String json = objectMapper.writeValueAsString(userMapper.selectById(userId));
        assertThat(json).doesNotContain("phoneHmac").doesNotContain("phone_hmac");

        Blacklist entry = insertBlacklistRow("PHONE", nextPhone());
        assertThat(objectMapper.writeValueAsString(blacklistMapper.selectById(entry.getId())))
                .doesNotContain("targetValueHmac").doesNotContain("target_value_hmac");

        // V30 三表同一红线：新列不得渗进任何实体直出的响应
        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();
        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("5.50"), "PII-JSON", 1L);
        assertThat(objectMapper.writeValueAsString(soleCustomerPrice(wid, rtPhone, skuId)))
                .doesNotContain("rtPhoneHmac").doesNotContain("rt_phone_hmac");

        String smsPhone = nextPhone();
        sendSmsCode(smsPhone, "REGISTER");
        assertThat(objectMapper.writeValueAsString(soleSmsCode(smsPhone)))
                .doesNotContain("phoneHmac").doesNotContain("phone_hmac");

        long storeId = seedStore(tenantId);
        seedStock(tenantId, wid, skuId, 100);
        String inquiryPhone = nextPhone();
        inquiryService.submitByRt(submitDto(storeId, wid, skuId, inquiryPhone, 5));
        assertThat(objectMapper.writeValueAsString(soleInquiry(wid, inquiryPhone)))
                .doesNotContain("rtPhoneHmac").doesNotContain("rt_phone_hmac");
    }

    // ------------------------------------------------------------ 回填

    @Test
    @DisplayName("回填：填平存量 NULL 行，重跑零改动（幂等）")
    void backfill_fillsLegacyNullRowsAndIsIdempotent() {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        // 抹掉 hmac，模拟 V27 上线前就存在的存量行
        clearUserHmac(userId);
        assertThat(userMapper.selectById(userId).getPhoneHmac()).isNull();

        PiiBackfillService.BackfillResult first = backfillService.backfillUsers(100);
        assertThat(first.refused()).isFalse();
        assertThat(first.filled()).isGreaterThanOrEqualTo(1);
        assertThat(userMapper.selectById(userId).getPhoneHmac()).isEqualTo(expectHmac(phone));

        // 幂等：候选集已空，第二次一行都不该动
        PiiBackfillService.BackfillResult second = backfillService.backfillUsers(100);
        assertThat(second.filled()).isZero();
        assertThat(userMapper.selectById(userId).getPhoneHmac()).isEqualTo(expectHmac(phone));
    }

    @Test
    @DisplayName("回填 CAS：绝不覆盖已有 hmac（与并发双写抢同一行时后到者影响 0 行）")
    void backfill_neverOverwritesExistingHmac() {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        String sentinel = "0".repeat(64);
        setUserHmac(userId, sentinel);
        try {
            backfillService.backfillUsers(100);
            assertThat(userMapper.selectById(userId).getPhoneHmac())
                    .as("已填行不进候选集，且 UPDATE 带 IS NULL 条件，回填不得改写")
                    .isEqualTo(sentinel);
        } finally {
            // 复原，免得这行错值污染后续对账断言
            setUserHmac(userId, expectHmac(phone));
        }
    }

    @Test
    @DisplayName("回填：只碰 PHONE 行，LICENSE_NO 行恒 NULL（15 §2-1）")
    void backfill_touchesPhoneRowsOnlyAndLeavesLicenseNull() {
        String phone = nextPhone();
        Blacklist phoneRow = insertBlacklistRow("PHONE", phone);
        Blacklist licenseRow = insertBlacklistRow("LICENSE_NO", "TESTLIC" + phone);
        clearBlacklistHmac(phoneRow.getId());

        backfillService.backfillBlacklist(100);

        assertThat(blacklistMapper.selectById(phoneRow.getId()).getTargetValueHmac())
                .isEqualTo(expectHmac(phone));
        assertThat(blacklistMapper.selectById(licenseRow.getId()).getTargetValueHmac())
                .as("LICENSE_NO 不是手机号，不进盲索引")
                .isNull();
    }

    @Test
    @DisplayName("回填 V30 三表：填平存量 NULL 行，重跑零改动（幂等）")
    void backfill_v30Tables_fillLegacyNullRowsAndAreIdempotent() {
        long tenantId = seedTenant();
        long storeId = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        seedStock(tenantId, wid, skuId, 100);

        String pricePhone = nextPhone();
        pricingService.settleFromInquiry(wid, pricePhone, skuId, new BigDecimal("4.40"), "PII-BF", 1L);
        Long priceId = soleCustomerPrice(wid, pricePhone, skuId).getId();
        String smsPhone = nextPhone();
        sendSmsCode(smsPhone, "REGISTER");
        Long smsId = soleSmsCode(smsPhone).getId();
        String inquiryPhone = nextPhone();
        inquiryService.submitByRt(submitDto(storeId, wid, skuId, inquiryPhone, 5));
        Long inquiryId = soleInquiry(wid, inquiryPhone).getId();

        // 抹掉三处 hmac，模拟 V30 上线前就存在的存量行
        clearCustomerPriceHmac(priceId);
        clearSmsCodeHmac(smsId);
        clearInquiryHmac(inquiryId);

        assertThat(backfillService.backfillCustomerPrices(100).filled()).isGreaterThanOrEqualTo(1);
        assertThat(backfillService.backfillSmsCodes(100).filled()).isGreaterThanOrEqualTo(1);
        assertThat(backfillService.backfillInquiryRequests(100).filled()).isGreaterThanOrEqualTo(1);

        assertThat(customerPriceMapper.selectById(priceId).getRtPhoneHmac()).isEqualTo(expectHmac(pricePhone));
        assertThat(smsCodeMapper.selectById(smsId).getPhoneHmac()).isEqualTo(expectHmac(smsPhone));
        assertThat(inquiryRequestMapper.selectById(inquiryId).getRtPhoneHmac()).isEqualTo(expectHmac(inquiryPhone));

        // 幂等：候选集已空，第二轮一行都不该动
        assertThat(backfillService.backfillCustomerPrices(100).filled()).isZero();
        assertThat(backfillService.backfillSmsCodes(100).filled()).isZero();
        assertThat(backfillService.backfillInquiryRequests(100).filled()).isZero();
    }

    @Test
    @DisplayName("回滚口径：write-mode=legacy 时回填被拒，一行不写")
    void backfill_refusedUnderLegacyWriteMode() {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        clearUserHmac(userId);

        String original = piiProperties.getWriteMode();
        piiProperties.setWriteMode("legacy");
        try {
            PiiBackfillService.BackfillResult result = backfillService.backfillUsers(100);
            assertThat(result.refused()).isTrue();
            assertThat(result.filled()).isZero();
            assertThat(userMapper.selectById(userId).getPhoneHmac())
                    .as("止血模式下不得再往新列写入")
                    .isNull();
        } finally {
            piiProperties.setWriteMode(original);
            backfillService.backfillUsers(100); // 复原，保持库自洽
        }
    }

    // ------------------------------------------------------------ 对账

    @Test
    @DisplayName("对账：回填后零差异；漏填与错值都能被抓出来")
    void reconcile_reportsCleanAfterBackfillAndDetectsDrift() {
        // 先把基线拉平（兄弟测试类可能留下未回填的行——V30 三表尤其：多个场景测试
        // 直接 mapper 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然为 NULL）
        flattenBackfillBaseline();

        List<PiiBackfillService.ReconcileResult> clean = backfillService.reconcile();
        assertThat(clean).allSatisfy(r -> assertThat(r.clean())
                .as("回填后 %s 应零差异，实际 %s", r.table(), r)
                .isTrue());
        assertThat(backfillService.unreadyTables()).isEmpty();

        // 制造一条漏填 + 一条错值，对账必须分别抓到
        String missingPhone = nextPhone();
        Long missingId = userService.ensureUserByPhone(missingPhone, "OPS_PROXY").userId();
        clearUserHmac(missingId);

        String driftPhone = nextPhone();
        Long driftId = userService.ensureUserByPhone(driftPhone, "OPS_PROXY").userId();
        setUserHmac(driftId, "f".repeat(64));

        try {
            PiiBackfillService.ReconcileResult dirty = backfillService.reconcileUsers();
            assertThat(dirty.missing()).isGreaterThanOrEqualTo(1);
            assertThat(dirty.mismatched()).isGreaterThanOrEqualTo(1);
            assertThat(dirty.clean()).isFalse();
            assertThat(backfillService.unreadyTables()).contains("users");
        } finally {
            setUserHmac(driftId, expectHmac(driftPhone));
            backfillService.backfillUsers(500);
        }

        // 收尾：库恢复自洽，不留给兄弟测试类
        assertThat(backfillService.reconcileUsers().clean()).isTrue();
    }

    // ------------------------------------------------------------ helpers

    /** 期望值独立实现——刻意不走 {@link PiiCrypto}，否则算法改错测试会跟着错。 */
    private static String expectHmac(String phone) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(TEST_KEY_B64), "HmacSHA256"));
            return toHex(mac.doFinal(phone.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** legacy 索引列的期望值，同样独立实现（对齐 hutool {@code DigestUtil.sha256Hex}）。 */
    private static String expectSha256Hex(String phone) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---- 流程驱动脚手架：短信码走 mock 短路，OPS 角色由真注册产生 user_roles 行 ----

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

    /** B2 的 OPS 脚手架：真注册一个 OPS 账号，requireOpsRole 查的就是它落下的 user_roles 行。 */
    private Long registerOps() {
        return register(nextPhone(), "PiiOpsPass123", "OPS").getData().getUserId();
    }

    private void changePhone(String token, String password, String newPhone) {
        ChangePhoneDto dto = new ChangePhoneDto();
        dto.setPassword(password);
        dto.setNewPhone(newPhone);
        dto.setOldSmsCode(MOCK_SMS_CODE);
        dto.setNewSmsCode(MOCK_SMS_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        R<Void> body = restTemplate.exchange(url("/api/v1/account/phone"), HttpMethod.PUT,
                new HttpEntity<>(dto, headers), VOID_BODY).getBody();
        assertThat(body).as("changePhone -> %s 无响应体", newPhone).isNotNull();
        assertThat(body.getCode()).as("changePhone -> %s 应成功，实际 %s", newPhone, body).isZero();
    }

    private BlacklistAddDto blacklistDto(String type, String value) {
        BlacklistAddDto dto = new BlacklistAddDto();
        dto.setTargetType(type);
        dto.setTargetValue(value);
        dto.setReason("PII-S0 切点关卡测试造数");
        return dto;
    }

    private static String nextPhone() {
        return String.valueOf(PHONE_SEQ.incrementAndGet());
    }

    private void clearUserHmac(Long userId) {
        setUserHmac(userId, null);
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

    /**
     * 直接落库造行——仅供回填/对账用例造「存量」数据用。走业务语义的加黑请用
     * {@link #blacklistService}（见 B2 切点用例），别再拿这个绕过 {@code requireOpsRole}。
     */
    private Blacklist insertBlacklistRow(String type, String value) {
        Blacklist entry = new Blacklist();
        entry.setTargetType(type);
        entry.setTargetValue(value);
        entry.setTargetValueHmac("PHONE".equals(type) ? piiCrypto.phoneHmac(value) : null);
        entry.setReason("PII-S0 关卡测试造数");
        entry.setOperatorUserId(1L);
        entry.setStatus("ACTIVE");
        blacklistMapper.insert(entry);
        return entry;
    }

    // ---- V30 三表：脚手架与读数 ----

    /**
     * 把五张表的回填基线拉平。
     *
     * <p>对账断言是全局计数，会受兄弟测试类残留影响；V30 三表尤其——多个场景测试直接
     * {@code mapper.insert} 造 customer_prices / inquiry_requests，绕过双写切点，hmac 天然为 NULL。
     */
    private void flattenBackfillBaseline() {
        backfillService.backfillUsers(500);
        backfillService.backfillBlacklist(500);
        backfillService.backfillCustomerPrices(500);
        backfillService.backfillSmsCodes(500);
        backfillService.backfillInquiryRequests(500);
    }

    private long seedTenant() {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("PII仓-" + t.getId());
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhone("13800000000");
        t.setStatus("ACTIVE");
        tenantMapper.insert(t);
        return t.getId();
    }

    private long seedStore(long tenantId) {
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(tenantId);
        s.setName("PII店-" + s.getId());
        s.setStatus("ACTIVE");
        storeMapper.insert(s);
        return s.getId();
    }

    private long seedWholesaler(long tenantId) {
        Wholesaler w = new Wholesaler();
        w.setId(snowflakeIdUtil.nextId());
        w.setTenantId(tenantId);
        w.setName("PII商户-" + w.getId());
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
        s.setName("PII品-" + s.getId());
        s.setUnitPrice(new BigDecimal("9.90"));
        s.setMoqPrice(new BigDecimal("8.50"));
        s.setMoqQty(10);
        s.setListed(true);
        skuMapper.insert(s);
        return s.getId();
    }

    private void seedStock(long tenantId, long wholesalerId, long skuId, int qty) {
        inventoryService.addStock(InboundContext.builder()
                .wholesalerId(wholesalerId)
                .tenantId(tenantId)
                .skuId(skuId)
                .qty(qty)
                .refDocNo("IN-PII-SEED")
                .operatorUserId(1L)
                .build());
    }

    private SubmitInquiryDto submitDto(long storeId, long wholesalerId, long skuId, String rtPhone, int qty) {
        SubmitInquiryDto d = new SubmitInquiryDto();
        d.setStoreId(storeId);
        d.setWholesalerId(wholesalerId);
        d.setRtPhone(rtPhone);
        SubmitInquiryDto.InquiryItemDto it = new SubmitInquiryDto.InquiryItemDto();
        it.setSkuId(skuId);
        it.setQty(qty);
        d.setItems(List.of(it));
        return d;
    }

    /** 走真端点发码——sms_codes 的落库切点只有这一处，绕过它等于没测。 */
    private void sendSmsCode(String phone, String scene) {
        SmsCodeSendDto dto = new SmsCodeSendDto();
        dto.setPhone(phone);
        dto.setScene(scene);
        R<Void> body = restTemplate.exchange(url("/api/v1/account/sms-code"), HttpMethod.POST,
                new HttpEntity<>(dto), VOID_BODY).getBody();
        assertThat(body).as("sendSmsCode %s 无响应体", phone).isNotNull();
        assertThat(body.getCode()).as("sendSmsCode %s 应成功，实际 %s", phone, body).isZero();
    }

    private CustomerPrice soleCustomerPrice(long wholesalerId, String rtPhone, long skuId) {
        List<CustomerPrice> rows = customerPriceMapper.selectList(new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhone, rtPhone)
                .eq(CustomerPrice::getSkuId, skuId));
        assertThat(rows).as("(wholesaler=%s, phone=%s, sku=%s) 应恰好一行", wholesalerId, rtPhone, skuId)
                .hasSize(1);
        return rows.get(0);
    }

    private SmsCode soleSmsCode(String phone) {
        List<SmsCode> rows = smsCodeMapper.selectList(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone));
        assertThat(rows).as("sms_codes 中 %s 应恰好一行", phone).hasSize(1);
        return rows.get(0);
    }

    private InquiryRequest soleInquiry(long wholesalerId, String rtPhone) {
        List<InquiryRequest> rows = inquiryRequestMapper.selectList(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getWholesalerId, wholesalerId)
                .eq(InquiryRequest::getRtPhone, rtPhone));
        assertThat(rows).as("inquiry_requests 中 (wholesaler=%s, phone=%s) 应恰好一行", wholesalerId, rtPhone)
                .hasSize(1);
        return rows.get(0);
    }

    private void clearCustomerPriceHmac(Long id) {
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .set(CustomerPrice::getRtPhoneHmac, null)
                .eq(CustomerPrice::getId, id));
    }

    private void clearSmsCodeHmac(Long id) {
        smsCodeMapper.update(null, new LambdaUpdateWrapper<SmsCode>()
                .set(SmsCode::getPhoneHmac, null)
                .eq(SmsCode::getId, id));
    }

    private void clearInquiryHmac(Long id) {
        inquiryRequestMapper.update(null, new LambdaUpdateWrapper<InquiryRequest>()
                .set(InquiryRequest::getRtPhoneHmac, null)
                .eq(InquiryRequest::getId, id));
    }

    /** 兜底自检：本类造的行不该在 users 里留下重复手机号。 */
    @SuppressWarnings("unused")
    private long countByPhone(String phone) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }
}
