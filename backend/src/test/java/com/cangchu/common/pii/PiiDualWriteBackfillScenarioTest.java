package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.CangchuApplication;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BlacklistMapper blacklistMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private PiiProperties piiProperties;
    @Autowired
    private PiiBackfillService backfillService;
    /** 用容器里那个 ObjectMapper——它才是真正序列化响应的那个，裸 new 测不出真实形状。 */
    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("红线：hmac 影子列不得出现在实体直出的 JSON（响应形状零变化）")
    void hmacColumn_isJsonIgnored() throws Exception {
        String phone = nextPhone();
        Long userId = userService.ensureUserByPhone(phone, "OPS_PROXY").userId();

        String json = objectMapper.writeValueAsString(userMapper.selectById(userId));
        assertThat(json).doesNotContain("phoneHmac").doesNotContain("phone_hmac");

        Blacklist entry = insertBlacklistRow("PHONE", nextPhone());
        assertThat(objectMapper.writeValueAsString(blacklistMapper.selectById(entry.getId())))
                .doesNotContain("targetValueHmac").doesNotContain("target_value_hmac");
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
        // 先把基线拉平（兄弟测试类可能留下未回填的行）
        backfillService.backfillUsers(500);
        backfillService.backfillBlacklist(500);

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
            byte[] out = mac.doFinal(phone.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /** 直接落库造行——{@code BlacklistService.add} 需 OPS 角色，本类只验 PII 语义，不搭权限脚手架。 */
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

    /** 兜底自检：本类造的行不该在 users 里留下重复手机号。 */
    @SuppressWarnings("unused")
    private long countByPhone(String phone) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }
}
