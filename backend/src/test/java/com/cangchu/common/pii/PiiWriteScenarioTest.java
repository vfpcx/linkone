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
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8 写侧关卡测试（16-pii-w8-shrink-plan §4.2.3）：V34 明文列下线后，所有 PII 写切点必须
 * <b>无条件</b>落 hmac 盲索引 + cipher 密文（展示列 last4/摘要），且 {@code decrypt(phoneCipher)==phone}
 * 必须成立。由 {@code PiiDualWriteBackfillScenarioTest} 改造而来——双写开关（write-mode=dual）
 * 与回填/对账（PiiBackfillService）已随 W8 删除，回填用例转成「写切点无条件写」断言。
 *
 * <p><b>为什么必须有这一类</b>：全量用例都走过 hmac/cipher 写路径——但没有一条断言 hmac/cipher
 * <b>写对了</b>。全绿只证明「不炸」，不证明「对」。本类把「对」这件事钉住。
 *
 * <p><b>期望值独立实现</b>：{@link #expectHmac} 用 JDK 原生 Mac 自己算一遍，<b>不复用
 * {@link PiiCrypto}</b>。若复用，PiiCrypto 算错时测试会跟着一起错，等于没测。
 *
 * <p><b>切点覆盖</b>：A1 注册、A4 换绑、A6 代建开号、B2 加黑/复活（PHONE 摘要 + last4 冲突消歧）、
 * C1 议价沉淀（新建与命中既有行）、SMS 发码、C2 RT 提交询价。A1/A4/SMS 经 HTTP 走完整流程
 * （mock 短信码 {@value #MOCK_SMS_CODE}），B2/C1/C2 真调服务。
 */
@SpringBootTest(classes = CangchuApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("W8 写侧关卡：hmac + cipher 无条件落库 / 解密还原 / 摘要消歧 / JSON 红线")
class PiiWriteScenarioTest {

    /** 测试态固定密钥，与 src/test/resources/application.yml 的 cangchu.pii.hmac-key 一致。 */
    private static final String TEST_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 与 application.yml 登记的 cangchu.pii.hmac-kat 一致（HMAC 的已知答案）。 */
    private static final String REGISTERED_KAT =
            "2aff2a1ede191cf2fd4af900d10f48d1e55f31e69dae59d7938c1ac349641534";

    /** 与 application.yml 登记的 cangchu.pii.cipher-kat 一致（AES-GCM 的已知答案）。 */
    private static final String REGISTERED_CIPHER_KAT = "AAAAAAAAAAAAAAAA/0CZMSIybGDfHbECcMUWmD/fMuCvrrY7Ab6B";

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
    /** 用容器里那个 ObjectMapper——它才是真正序列化响应的那个，裸 new 测不出真实形状。 */
    @Autowired
    private ObjectMapper objectMapper;

    // ---- 定价链 / 短信校验 / 询价的切点与脚手架依赖 ----
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
    @DisplayName("cipher KAT：decrypt(登记的 cipher-kat) 还原已知向量，算法/密钥被锚定")
    void cipherKat_decryptsBackToKnownVector() {
        assertThat(piiCrypto.decrypt(REGISTERED_CIPHER_KAT)).isEqualTo(PiiCrypto.KAT_VECTOR);
    }

    @Test
    @DisplayName("规范化单入口：首尾空白等价、空白视为无值")
    void normalize_trimsAndTreatsBlankAsAbsent() {
        String phone = nextPhone();
        assertThat(piiCrypto.phoneHmac("  " + phone + "  ")).isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.phoneHmac("   ")).isNull();
        assertThat(piiCrypto.phoneHmac(null)).isNull();
    }

    @Test
    @DisplayName("cipher 往返：decrypt(encrypt(phone))==phone；last4 取尾4；同号两次密文不同（随机 IV）")
    void cipher_roundTrip_decryptRestoresFullPhone() {
        String phone = nextPhone();
        String cipher = piiCrypto.encrypt(phone);
        assertThat(cipher).isNotEqualTo(phone);
        assertThat(cipher).doesNotContain(phone.substring(0, 3));
        assertThat(piiCrypto.decrypt(cipher)).isEqualTo(phone);
        assertThat(piiCrypto.last4(phone)).isEqualTo(phone.substring(7));
        // 同一手机号两次加密产出不同密文（随机 IV），但都能解回原号
        assertThat(piiCrypto.encrypt(phone)).isNotEqualTo(cipher);
        assertThat(piiCrypto.decrypt(piiCrypto.encrypt(phone))).isEqualTo(phone);
    }

    // ------------------------------------------------------------ 写切点：无条件 hmac + cipher

    @Test
    @DisplayName("切点 A6（代建开号）：ensureUserByPhone 写入 hmac + cipher，解密还原全号")
    void dualWrite_a6_ensureUserByPhone_writesHmacAndCipher() {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhoneHmac())
                .as("A6 切点必须写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.decrypt(saved.getPhoneCipher()))
                .as("A6 切点必须写 cipher，且必须能还原全号")
                .isEqualTo(phone);
    }

    @Test
    @DisplayName("切点 A6 幂等复用：同号第二次 ensure 不产生新行，hmac/cipher 保持正确")
    void dualWrite_a6_idempotentReuseKeepsHmacAndCipher() {
        String phone = nextPhone();
        Long first = userService.ensureUserByPhone(phone, "WA_PROVISION").userId();
        Long second = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        assertThat(second).isEqualTo(first);
        User saved = userMapper.selectById(first);
        assertThat(saved.getPhoneHmac()).isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.decrypt(saved.getPhoneCipher())).isEqualTo(phone);
    }

    @Test
    @DisplayName("切点 A1（注册）：注册落库 hmac 等于独立重算值，cipher 解密还原全号")
    void dualWrite_a1_register_writesHmacAndCipher() {
        String phone = nextPhone();
        Long userId = register(phone, "PiiA1Pass123", "TA").getData().getUserId();

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhoneHmac())
                .as("A1 切点必须写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.decrypt(saved.getPhoneCipher()))
                .as("A1 切点必须写 cipher，且必须能还原全号")
                .isEqualTo(phone);
    }

    @Test
    @DisplayName("切点 A4（换绑）：hmac/cipher 跟着新号重算，旧号 hmac 不得残留")
    void dualWrite_a4_changePhone_hmacAndCipherFollowNewPhone() {
        String oldPhone = nextPhone();
        String newPhone = nextPhone();
        String password = "PiiA4Pass123";
        R<LoginVo> registered = register(oldPhone, password, "TA");
        Long userId = registered.getData().getUserId();
        assertThat(userMapper.selectById(userId).getPhoneHmac()).isEqualTo(expectHmac(oldPhone));

        changePhone(registered.getData().getToken(), password, newPhone);

        User saved = userMapper.selectById(userId);
        assertThat(saved.getPhoneHmac())
                .as("换绑后 hmac 必须重算为新号；仍等于旧号值即说明盲索引没跟着更新")
                .isEqualTo(expectHmac(newPhone))
                .isNotEqualTo(expectHmac(oldPhone));
        assertThat(piiCrypto.decrypt(saved.getPhoneCipher()))
                .as("换绑后 cipher 必须换为新号密文，解密须还原新号")
                .isEqualTo(newPhone);
    }

    @Test
    @DisplayName("切点 B2（加黑）：PHONE 行落 hmac + cipher + V34 摘要展示值")
    void dualWrite_b2_blacklistAdd_phoneRowWritesHmacCipherAndSummary() {
        Long opsUserId = registerOps();
        String phone = nextPhone();

        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));

        Blacklist saved = blacklistMapper.selectById(entry.getId());
        assertThat(saved.getTargetValueHmac())
                .as("B2 加黑切点必须写 hmac")
                .isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.decrypt(saved.getTargetValueCipher()))
                .as("B2 加黑切点必须写 cipher，且必须能还原全号")
                .isEqualTo(phone);
        assertThat(saved.getTargetValue())
                .as("V34 后 PHONE 行 target_value 是摘要 PHONE_****{last4}（16 §1.5）")
                .isEqualTo("PHONE_****" + phone.substring(7));
    }

    @Test
    @DisplayName("切点 B2（加黑）：LICENSE_NO 行不进手机号盲索引，hmac/cipher 恒 NULL")
    void dualWrite_b2_blacklistAdd_licenseRowKeepsHmacAndCipherNull() {
        Long opsUserId = registerOps();
        String license = "TESTLIC" + nextPhone();

        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("LICENSE_NO", license));

        Blacklist saved = blacklistMapper.selectById(entry.getId());
        assertThat(saved.getTargetValueHmac())
                .as("LICENSE_NO 不是手机号，不得写入手机号盲索引（15 §2-1）")
                .isNull();
        assertThat(saved.getTargetValueCipher())
                .as("LICENSE_NO 非 PII 行不写 cipher")
                .isNull();
        assertThat(saved.getTargetValue()).isEqualTo(license);
    }

    @Test
    @DisplayName("切点 B2（加黑）：last4 冲突消歧——同尾号第二行追加 hmac 尾4，不撞唯一键")
    void dualWrite_b2_blacklistAdd_phoneLast4ConflictDisambiguated() {
        Long opsUserId = registerOps();
        String phoneA = "17700001111";
        String phoneB = "17800001111"; // last4 与 A 相同（1111）
        assertThat(phoneA.substring(7)).isEqualTo(phoneB.substring(7));

        Blacklist a = blacklistService.add(opsUserId, blacklistDto("PHONE", phoneA));
        Blacklist b = blacklistService.add(opsUserId, blacklistDto("PHONE", phoneB));

        assertThat(a.getTargetValue()).isEqualTo("PHONE_****1111");
        // 第二行追加 :hmac 尾4 消歧（与 V34 迁移 8.2 口径一致），uk_blacklist_type_value 不再撞
        assertThat(b.getTargetValue())
                .isEqualTo("PHONE_****1111:" + expectHmac(phoneB).substring(expectHmac(phoneB).length() - 4));
        // 两行全号均能解密还原
        assertThat(piiCrypto.decrypt(a.getTargetValueCipher())).isEqualTo(phoneA);
        assertThat(piiCrypto.decrypt(b.getTargetValueCipher())).isEqualTo(phoneB);
    }

    @Test
    @DisplayName("切点 B2（复活）：REMOVED 行复活时无条件刷新 hmac + cipher + 摘要，复用原行不新插")
    void dualWrite_b2_reviveUnconditionallyRefreshesHmacAndCipher() {
        Long opsUserId = registerOps();
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));
        blacklistService.remove(opsUserId, entry.getId());

        Blacklist revived = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));

        assertThat(revived.getId())
                .as("uk_blacklist_type_value 在，复活必须复用原行而非新插")
                .isEqualTo(entry.getId());
        Blacklist saved = blacklistMapper.selectById(entry.getId());
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getRemovedAt())
                .as("复活后 removed_at 必须清空，不得残留旧的解除时间")
                .isNull();
        assertThat(saved.getTargetValueHmac())
                .as("复活分支必须无条件刷新 hmac，漏写即留一个盲索引空洞")
                .isEqualTo(expectHmac(phone));
        assertThat(piiCrypto.decrypt(saved.getTargetValueCipher()))
                .as("复活分支必须无条件刷新 cipher")
                .isEqualTo(phone);
        assertThat(saved.getTargetValue()).isEqualTo("PHONE_****" + phone.substring(7));
    }

    @Test
    @DisplayName("切点 B2（加黑）：hmac 缺失的存量 REMOVED 行按 hmac 探测不到，落 uk 撞车兜底 50310")
    void dualWrite_b2_blacklistAdd_hmacMissingRowFallsBackToUkCollision() {
        Long opsUserId = registerOps();
        String phone = nextPhone();
        Blacklist entry = blacklistService.add(opsUserId, blacklistDto("PHONE", phone));
        blacklistService.remove(opsUserId, entry.getId());
        // 抹掉 hmac 模拟 V27 之前的存量行——W8 hmac-only 下该行已无法被 hmac 探测到
        clearBlacklistHmac(entry.getId());

        try {
            blacklistService.add(opsUserId, blacklistDto("PHONE", phone));
            org.assertj.core.api.Assertions.fail("hmac 缺失的存量行应撞 uk 抛 50310");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.BLACKLIST_ENTRY_EXISTS.getCode());
        }
    }

    // ---- 定价链 / 短信校验 / 询价 ----

    @Test
    @DisplayName("切点 C1（议价沉淀·新建）：settleFromInquiry 插入的行带正确 rt_phone_hmac + last4")
    void dualWrite_c1_settleFromInquiry_writesHmacAndLast4() {
        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();

        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("7.70"), "PII-C1-NEW", 1L);

        CustomerPrice row = soleCustomerPrice(wid, rtPhone, skuId);
        assertThat(row.getRtPhoneHmac())
                .as("C1 切点必须写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(rtPhone));
        assertThat(row.getRtPhoneLast4())
                .as("C1 切点必须写 last4（V34 明文列删除后的展示列）")
                .isEqualTo(rtPhone.substring(7));
    }

    @Test
    @DisplayName("切点 C1（议价沉淀·命中既有行）：存量行无条件补写 last4，不新插行")
    void dualWrite_c1_settleFromInquiry_refreshesLast4OnExistingRow() {
        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();
        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("7.70"), "PII-C1-A", 1L);
        CustomerPrice first = soleCustomerPrice(wid, rtPhone, skuId);
        // 抹掉 last4，模拟存量行缺失展示列——命中既有行分支必须无条件补写
        clearCustomerPriceLast4(first.getId());

        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("6.60"), "PII-C1-B", 1L);

        CustomerPrice revised = soleCustomerPrice(wid, rtPhone, skuId);
        assertThat(revised.getId())
                .as("唯一键 (wholesaler, rt_phone_hmac, sku) 在，第二次沉淀必须复用原行而非新插")
                .isEqualTo(first.getId());
        assertThat(revised.getUnitPrice()).isEqualByComparingTo("6.60");
        assertThat(revised.getRtPhoneHmac()).isEqualTo(expectHmac(rtPhone));
        assertThat(revised.getRtPhoneLast4())
                .as("命中既有行必须无条件补写 last4，漏写即留展示空洞")
                .isEqualTo(rtPhone.substring(7));
    }

    @Test
    @DisplayName("切点 SMS（发码）：sms-code 端点落库的行带正确 phone_hmac + last4")
    void dualWrite_sms_sendSmsCode_writesHmacAndLast4() {
        String phone = nextPhone();

        sendSmsCode(phone, "REGISTER");

        SmsCode row = soleSmsCode(phone);
        assertThat(row.getPhoneHmac())
                .as("SMS 切点必须写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(phone));
        assertThat(row.getPhoneLast4())
                .as("SMS 切点必须写 last4")
                .isEqualTo(phone.substring(7));
    }

    @Test
    @DisplayName("切点 C2（RT 提交询价）：submitByRt 落库的行带正确 rt_phone_hmac + cipher")
    void dualWrite_c2_submitByRt_writesHmacAndCipher() {
        long tenantId = seedTenant();
        long storeId = seedStore(tenantId);
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        seedStock(tenantId, wid, skuId, 100);
        String rtPhone = nextPhone();

        inquiryService.submitByRt(submitDto(storeId, wid, skuId, rtPhone, 20));

        InquiryRequest row = soleInquiry(wid, rtPhone);
        assertThat(row.getRtPhoneHmac())
                .as("C2 切点必须写 hmac，且值须等于独立实现重算结果")
                .isEqualTo(expectHmac(rtPhone));
        assertThat(piiCrypto.decrypt(row.getRtPhoneCipher()))
                .as("C2 切点必须写 cipher，且必须能还原全号")
                .isEqualTo(rtPhone);
    }

    @Test
    @DisplayName("红线：hmac/cipher/last4 影子列不得出现在实体直出的 JSON（响应形状零变化）")
    void hmacAndCipherColumns_areJsonIgnored() throws Exception {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();
        String userJson = objectMapper.writeValueAsString(userMapper.selectById(userId));
        assertThat(userJson)
                .doesNotContain("phoneHmac").doesNotContain("phone_hmac")
                .doesNotContain("phoneCipher").doesNotContain("phone_cipher");

        Blacklist entry = insertBlacklistRow("PHONE", nextPhone());
        String blJson = objectMapper.writeValueAsString(blacklistMapper.selectById(entry.getId()));
        assertThat(blJson)
                .doesNotContain("targetValueHmac").doesNotContain("target_value_hmac")
                .doesNotContain("targetValueCipher").doesNotContain("target_value_cipher");

        long tenantId = seedTenant();
        long wid = seedWholesaler(tenantId);
        long skuId = seedSku(tenantId, wid);
        String rtPhone = nextPhone();
        pricingService.settleFromInquiry(wid, rtPhone, skuId, new BigDecimal("5.50"), "PII-JSON", 1L);
        assertThat(objectMapper.writeValueAsString(soleCustomerPrice(wid, rtPhone, skuId)))
                .doesNotContain("rtPhoneHmac").doesNotContain("rt_phone_hmac")
                .doesNotContain("rtPhoneLast4").doesNotContain("rt_phone_last4");

        String smsPhone = nextPhone();
        sendSmsCode(smsPhone, "REGISTER");
        assertThat(objectMapper.writeValueAsString(soleSmsCode(smsPhone)))
                .doesNotContain("phoneHmac").doesNotContain("phone_hmac")
                .doesNotContain("phoneLast4").doesNotContain("phone_last4");

        long storeId = seedStore(tenantId);
        seedStock(tenantId, wid, skuId, 100);
        String inquiryPhone = nextPhone();
        inquiryService.submitByRt(submitDto(storeId, wid, skuId, inquiryPhone, 5));
        assertThat(objectMapper.writeValueAsString(soleInquiry(wid, inquiryPhone)))
                .doesNotContain("rtPhoneHmac").doesNotContain("rt_phone_hmac")
                .doesNotContain("rtPhoneCipher").doesNotContain("rt_phone_cipher");
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
        dto.setReason("W8 写侧关卡测试造数");
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

    /**
     * 直接落库造行——仅供 JSON 红线用例造数据用。走业务语义的加黑请用 {@link #blacklistService}。
     */
    private Blacklist insertBlacklistRow(String type, String value) {
        Blacklist entry = new Blacklist();
        entry.setTargetType(type);
        if ("PHONE".equals(type)) {
            entry.setTargetValue("PHONE_****" + value.substring(7));
            entry.setTargetValueHmac(piiCrypto.phoneHmac(value));
            entry.setTargetValueCipher(piiCrypto.encrypt(value));
        } else {
            entry.setTargetValue(value);
        }
        entry.setReason("W8 写侧关卡测试造数");
        entry.setOperatorUserId(1L);
        entry.setStatus("ACTIVE");
        blacklistMapper.insert(entry);
        return entry;
    }

    // ---- 定价链 / 短信校验 / 询价：脚手架与读数 ----

    private long seedTenant() {
        Tenant t = new Tenant();
        t.setId(snowflakeIdUtil.nextId());
        t.setTenantSimpleCode(TestUniq.tenantSimpleCode());
        t.setName("PII仓-" + t.getId());
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
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
                .eq(CustomerPrice::getRtPhoneHmac, piiCrypto.phoneHmac(rtPhone))
                .eq(CustomerPrice::getSkuId, skuId));
        assertThat(rows).as("(wholesaler=%s, phone=%s, sku=%s) 应恰好一行", wholesalerId, rtPhone, skuId)
                .hasSize(1);
        return rows.get(0);
    }

    private SmsCode soleSmsCode(String phone) {
        List<SmsCode> rows = smsCodeMapper.selectList(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhoneHmac, piiCrypto.phoneHmac(phone)));
        assertThat(rows).as("sms_codes 中 %s 应恰好一行", phone).hasSize(1);
        return rows.get(0);
    }

    private InquiryRequest soleInquiry(long wholesalerId, String rtPhone) {
        List<InquiryRequest> rows = inquiryRequestMapper.selectList(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getWholesalerId, wholesalerId)
                .eq(InquiryRequest::getRtPhoneHmac, piiCrypto.phoneHmac(rtPhone)));
        assertThat(rows).as("inquiry_requests 中 (wholesaler=%s, phone=%s) 应恰好一行", wholesalerId, rtPhone)
                .hasSize(1);
        return rows.get(0);
    }

    private void clearCustomerPriceLast4(Long id) {
        customerPriceMapper.update(null, new LambdaUpdateWrapper<CustomerPrice>()
                .set(CustomerPrice::getRtPhoneLast4, null)
                .eq(CustomerPrice::getId, id));
    }
}
