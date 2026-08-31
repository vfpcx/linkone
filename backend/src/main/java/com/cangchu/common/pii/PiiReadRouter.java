package com.cangchu.common.pii;

import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * PII 阶段 1 Step 2/3 · 切读开关（15-pii-hardening-v2 §4 / 波次 PII-W5、PII-W6）。
 *
 * <p>影子期（Step 1）出结果的是明文列、hmac 只在旁边比对；本类负责把这件事<b>反过来</b>：模块一旦
 * 拨到 {@code hmac}，hmac 列直接出结果。Step 2 覆盖黑名单 B1/B2、短信码校验、定价 C1/C2/C3、
 * 手机号派生的 Redis 键（C4）；Step 3 追加<b>登录链 A1–A6</b>（{@link #user}）。
 *
 * <h3>三条口径</h3>
 * <ol>
 *   <li><b>硬切，无旧列兜底</b>：hmac 未命中 = 真未命中，主路照未命中处理（加黑走 insert、专属价回退
 *       公开价、验证码校验不通过）。回填有没有填全，是切读<b>之前</b>由影子期闸门证明的事；用运行时
 *       兜底去掩盖，等于把「回填有洞」这个事实永久藏起来，闸门也就再没有归零的一天。
 *       <p><b>唯一的例外是登录链</b>（{@link #user}，Step 3）：它走<b>双读兜底 + 异步补写</b>。
 *       这不是把上面那句话打个折，而是失败模式不同——上面几块漏填的代价是「一次判定错了」，可观察、
 *       可追溯、可补救；登录漏填的代价是「那个人从此登不上、找不回密码、重新注册还撞唯一键」，
 *       而且他没有任何自助手段。所以登录用兜底换可用性，<b>并把因此被掩盖的那笔账单独记出来</b>
 *       （{@link PiiFallbackHealer}），闸门归零的路径依然存在，只是换了个指标承载。</li>
 *   <li><b>回滚分支永远在</b>：非 hmac 模式走的就是原来那条明文查询（由调用方以 {@code legacyRead}
 *       传入，一行没改），外加原来那次影子比对。拨回 {@code shadow}/{@code plain} 即秒级恢复。</li>
 *   <li><b>比对过的查询才敢拿来出结果</b>：hmac 侧查询一律经 {@link PiiHmacQueries} 构造，与
 *       {@link PiiShadowReader} 影子期比对用的是同一条谓词，不另写一份。</li>
 * </ol>
 *
 * <h3>算不出 hmac 时怎么办</h3>
 * 明文为空白（算不出 hmac）、或黑名单的 LICENSE_NO 行（hmac 恒 NULL，15 §2-1 保留明文分支）——
 * 这两种情况下 hmac 列本就<b>不承载</b>该行的可查性，切读与否都轮不到它，一律走明文分支。这不是
 * 兜底回退：兜底是"hmac 该有答案却没查到"，这里是"hmac 从来就不负责这行"。
 *
 * <h3>异常不吞</h3>
 * 与 {@link PiiShadowReader} 相反：影子查询抛异常可以吞（主路已有答案），切读后的查询抛异常必须
 * 外抛——此时没有第二个答案可用，吞掉就等于凭空编一个"未命中"。密钥缺失/配错已由
 * {@link PiiCrypto} 的启动 KAT 拦在启动期，运行期能抛的基本只剩 DB 故障，而 DB 故障下明文查询
 * 同样会抛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiReadRouter {

    private static final Set<String> VALID_MODES = Set.of("plain", "shadow", "hmac");

    private final PiiProperties properties;
    private final PiiCrypto piiCrypto;
    private final PiiShadowReader shadowReader;
    private final PiiFallbackHealer fallbackHealer;
    private final UserMapper userMapper;
    private final BlacklistMapper blacklistMapper;
    private final CustomerPriceMapper customerPriceMapper;
    private final SmsCodeMapper smsCodeMapper;

    /**
     * 启动期校验模块名与模式取值：写错模块名会静默回落全局模式——想切的没切、想拨回的没拨回，
     * 且毫无征兆。这类"配置写错但看起来正常"的事故必须拦在启动期（同 {@link PiiCrypto} 的 KAT）。
     */
    @PostConstruct
    void validateReadModes() {
        requireValidMode("cangchu.pii.read-mode", properties.getReadMode());
        properties.getReadModes().forEach((module, mode) -> {
            if (!PiiModule.ALL.contains(module)) {
                throw new IllegalStateException("[PII] cangchu.pii.read-modes 出现未知模块 '" + module
                        + "'——合法模块 " + PiiModule.ALL + "（拒绝启动）");
            }
            // 空值 = 未登记（application.yml 里四个模块都以空占位符登记，好让运维用标准环境变量拨动），
            // 回落全局 read-mode，不是配置错误。
            if (mode != null && !mode.isBlank()) {
                requireValidMode("cangchu.pii.read-modes." + module, mode);
            }
        });
        log.info("[PII] 读模式装载完成：全局 read-mode={}，分模块覆写={}",
                properties.getReadMode(), properties.getReadModes());
    }

    private static void requireValidMode(String key, String mode) {
        if (mode == null || !VALID_MODES.contains(mode.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("[PII] " + key + " 取值非法：'" + mode
                    + "'——合法取值 " + VALID_MODES + "（拒绝启动）");
        }
    }

    /** 该模块是否已切读（供调用方在需要时跳过明文入参的组装开销；不调用也不影响正确性）。 */
    public boolean isHmacRead(String module) {
        return properties.isHmacRead(module);
    }

    // ------------------------------------------------------------ 登录链（A1–A6，Step 3）

    /**
     * A1–A6 登录链按手机号取用户：<b>双读兜底自愈</b>（15 §4 Step 3 / 波次 PII-W6）。
     *
     * <p>与本类其余方法的硬切不同，切读后的判定链是：
     * <pre>
     * hmac 命中          → 直接出结果                                  记 HMAC_HIT
     * hmac 未命中 → 回落 phone_hash 再查一次
     *      ├ 旧列也未命中 → 返回 null（真未命中：新号注册、找回不存在的账号） 记 CONFIRMED_MISS
     *      └ 旧列命中     → 用旧列结果出结果 + 异步补写该行 hmac 列        记 FALLBACK → HEALED
     * </pre>
     *
     * <p><b>为什么登录可以兜底、别的不可以</b>：见类注释口径 1。一句话——别处漏填是「一次判定错了」，
     * 登录漏填是「这个人再也进不来且无法自助」。代价换过来之后，被兜底掩盖的那笔账由
     * {@link PiiFallbackHealer} 单独记账（{@code pii.fallback}），Step 1 的七天闸门在切读后由它延续。
     *
     * <p><b>兜底的盲区，说清楚</b>：hmac 命中即出结果，明文列根本不查，所以「hmac 命中的是不是同一行」
     * （撞键 / 规范化漂移 R1）双读救不了也测不出——那是影子期七天闸门的职责，不是本方法的。
     *
     * <p><b>算不出 hmac</b>（明文空白）走明文分支且不计数：与 B2 的 LICENSE_NO 同一口径——hmac 列本就
     * 不承载这一行的可查性，不是兜底。
     *
     * @param pointcut   切点名（如 {@code A2-login}），进指标 tag，须为常量
     * @param phone      明文手机号（只用于算 hmac 与补写，不入 key、不入日志）
     * @param legacyRead 原 {@code phone_hash} 查询（回滚分支；切读后仅在 hmac 未命中时执行）
     */
    public User user(String pointcut, String phone, Supplier<User> legacyRead) {
        if (!properties.isHmacRead(PiiModule.LOGIN)) {
            User legacy = legacyRead.get();
            shadowReader.checkUser(pointcut, phone, legacy);
            return legacy;
        }
        String hmac = piiCrypto.phoneHmac(phone);
        if (hmac == null) {
            return legacyRead.get();
        }
        User hit = userMapper.selectOne(PiiHmacQueries.user(hmac));
        if (hit != null) {
            fallbackHealer.recordHmacHit(pointcut);
            return hit;
        }
        User legacy = legacyRead.get();
        if (legacy == null) {
            fallbackHealer.recordConfirmedMiss(pointcut);
            return null;
        }
        fallbackHealer.recordFallback(pointcut, legacy.getId(), phone);
        return legacy;
    }

    // ------------------------------------------------------------ 黑名单（B1 / B2）

    /**
     * B2 加黑查重/复活的按值查行。
     *
     * <p>LICENSE_NO 行的 hmac 恒 NULL，无论开关如何都走明文分支——切读切的是"手机号这一列",
     * 不是"这张表"。
     *
     * @param legacyRead 原明文查询（回滚分支，切读期不执行）
     */
    public Blacklist blacklistEntry(String pointcut, String targetType, String targetValue,
                                    Supplier<Blacklist> legacyRead) {
        String hmac = "PHONE".equals(targetType) ? piiCrypto.phoneHmac(targetValue) : null;
        if (hmac != null && properties.isHmacRead(PiiModule.BLACKLIST)) {
            return blacklistMapper.selectOne(PiiHmacQueries.blacklistEntry(hmac));
        }
        Blacklist legacy = legacyRead.get();
        shadowReader.checkBlacklistEntry(pointcut, targetType, targetValue, legacy);
        return legacy;
    }

    /** B1 入驻命中检查：判定是布尔（ACTIVE 行数 &gt; 0），切读后同样只数行。 */
    public boolean blacklistHit(String pointcut, String phone, BooleanSupplier legacyRead) {
        String hmac = piiCrypto.phoneHmac(phone);
        if (hmac != null && properties.isHmacRead(PiiModule.BLACKLIST)) {
            return blacklistMapper.selectCount(PiiHmacQueries.blacklistActiveHit(hmac)) > 0;
        }
        boolean legacyHit = legacyRead.getAsBoolean();
        shadowReader.checkBlacklistHit(pointcut, phone, legacyHit);
        return legacyHit;
    }

    // ------------------------------------------------------------ 定价链（C1 / C2 / C3）

    /**
     * C1 upsert 唯一键匹配 / C2 价格解析的单行查询。
     *
     * @param status 主路若按状态过滤则传该状态（C2 传 ACTIVE），否则 null（C1 不按 status 过滤）
     */
    public CustomerPrice customerPrice(String pointcut, Long wholesalerId, String rtPhone, Long skuId,
                                       String status, Supplier<CustomerPrice> legacyRead) {
        String hmac = piiCrypto.phoneHmac(rtPhone);
        if (hmac != null && properties.isHmacRead(PiiModule.PRICING)) {
            return customerPriceMapper.selectOne(
                    PiiHmacQueries.customerPrice(wholesalerId, hmac, skuId, status));
        }
        CustomerPrice legacy = legacyRead.get();
        shadowReader.checkCustomerPrice(pointcut, wholesalerId, rtPhone, skuId, status, legacy);
        return legacy;
    }

    /**
     * C3 批量调价按 rtPhone 圈选（多行）。
     *
     * <p>{@code rtPhone} 为空 = 本次走「显式 ids」分支，主路压根没读明文手机号列，没有可切的读，
     * 直接走 {@code legacyRead}（该分支下影子也不进分母，语义与 Step 1 一致）。
     */
    public List<CustomerPrice> customerPriceRows(String pointcut, Long wholesalerId, Long skuId,
                                                 String rtPhone, Supplier<List<CustomerPrice>> legacyRead) {
        String hmac = piiCrypto.phoneHmac(rtPhone);
        if (hmac != null && properties.isHmacRead(PiiModule.PRICING)) {
            return customerPriceMapper.selectList(
                    PiiHmacQueries.customerPriceRows(wholesalerId, skuId, hmac));
        }
        List<CustomerPrice> legacy = legacyRead.get();
        shadowReader.checkCustomerPriceRows(pointcut, wholesalerId, skuId, rtPhone, legacy);
        return legacy;
    }

    // ------------------------------------------------------------ 短信码校验

    /** SMS 验证码校验的取码查询（scene/code/未核销/取最新逐条照抄明文口径，只换手机号那一列）。 */
    public SmsCode smsCode(String pointcut, String phone, String scene, String code,
                           Supplier<SmsCode> legacyRead) {
        String hmac = piiCrypto.phoneHmac(phone);
        if (hmac != null && properties.isHmacRead(PiiModule.SMS)) {
            return smsCodeMapper.selectOne(PiiHmacQueries.smsCode(hmac, scene, code));
        }
        SmsCode legacy = legacyRead.get();
        shadowReader.checkSmsCode(pointcut, phone, scene, code, legacy);
        return legacy;
    }

    // ------------------------------------------------------------ Redis 键（C4）

    /**
     * C4：手机号在 Redis key 里的派生片段。切读后返回 HMAC，否则原样返回旧派生物（回滚分支）。
     *
     * <p><b>旧键不清洗</b>：换了派生方式，旧键就再没人读得到，靠各自的 TTL 自然消亡
     * （专属价匹配缓存 60s、短信冷却 60s、短信日限当日、登录失败锁定 15min）。清洗反而要按明文
     * 手机号去扫 keyspace，把刚藏起来的东西又摊开一遍。
     *
     * <p><b>代价说清楚</b>：拨动本开关等于把这几个窗口重开一次——冷却中的手机号可以立刻再发一条码、
     * 锁定中的账号立刻解锁、专属价缓存全体重算。都是有界且自愈的，但别在遭爆破时拨。
     *
     * @param phone      明文手机号（只用于算 hmac，不入 key、不入日志）
     * @param legacyPart 旧派生片段（sha256 hash 或——{@code price:match} 那处——明文本身）
     */
    public String redisKeyPart(String phone, String legacyPart) {
        if (!properties.isHmacRead(PiiModule.REDIS_KEY)) {
            return legacyPart;
        }
        String hmac = piiCrypto.phoneHmac(phone);
        return hmac != null ? hmac : legacyPart;
    }
}
