package com.cangchu.common.pii;

import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * PII 阶段 1 Step 1 · 影子双查（15-pii-hardening-v2 §4 阶段1 / 波次 PII-W4）。
 *
 * <p><b>零行为变化是本类的第一红线</b>：出结果的永远是调用方已经拿到的旧列查询结果，本类只是
 * 拿 hmac 列<b>再查一遍</b>，比对两边是否指向同一行，然后计数。返回 void——调用方拿不到影子
 * 结果，也就不可能误用它做判定。
 *
 * <p><b>第二红线：绝不把主路带崩</b>。影子查询的任何异常都在本类内吞掉（计 {@link Verdict#ERROR}
 * 并 error 日志），不外抛。故意在业务 {@code @Transactional} 方法内部 catch——异常不越出方法，
 * 就不会把事务标脏（rollback-only）。
 *
 * <p><b>第三红线：日志不落 PII</b>（F7）。不一致告警只打切点名/结论/行 id，绝不打手机号或 hmac
 * ——沿用 {@link PiiBackfillService} 的对账日志口径。
 *
 * <h3>为什么要这一步</h3>
 * 回填是否真的填全、规范化口径是否漂移（R1），单测证明不了——只有生产真实流量能证明。影子期
 * 用真实读请求把两列的答案逐次对齐，代价是每请求多一次索引查询。<b>闸门：mismatch 连续 ≥7 天
 * 为 0</b>，才允许进 Step 2 切读。
 *
 * <h3>覆盖范围</h3>
 * 覆盖<b>已有 hmac 列且已双写+回填</b>的四张表，按闸门分成两组：
 * <ul>
 *   <li><b>Step 1 闸门组（W4，8 个切点）</b>：users（A1–A6 登录链）、blacklist PHONE 行
 *       （B1 命中检查 / B2 加黑查重）。7 天 mismatch=0 是 Step 3 登录切读的准入线。
 *       B1/B2 已随 Step 2 切读退出该分母，登录切读后 A1–A6 亦然——见下条。</li>
 *   <li><b>Step 2 观察组（W5，5 个切点）</b>：customer_prices（C1 upsert 唯一键匹配 ×2 处、
 *       C2 价格解析、C3 批量调价按 rtPhone 圈选）、sms_codes（SMS 验证码校验）。
 *       口径与上一组逐条相同，但<b>不进 Step 1 的 7 天分母</b>——它服务的是 15 §4 Step 2
 *       自己的「pricing 全量 + 黑名单用例 + E2E 全绿，观察 ≥3 天」闸门。</li>
 * </ul>
 *
 * <p><b>切读后本类自动让位</b>（Step 2 起）：各组的闸门一律按 {@link PiiModule} 分模块判定——某模块
 * 一旦拨到 {@code hmac}，{@link PiiReadRouter} 直接由 hmac 列出结果、明文查询不再执行，本类对该模块的
 * 探针随之停摆。这是对的：已经没有"旧列的答案"可比，再计数就是拿 hmac 跟自己比。B1/B2 因此会退出
 * Step 1 的 7 天分母——切读发生在闸门达标<b>之后</b>，不影响准入判定。
 *
 * <p><b>登录链（{@link #checkUser}）从 Step 3 起同理</b>，但多一层要交代：它切读后走的是<b>双读兜底</b>
 * 而非硬切，明文列在 hmac 未命中时仍会被查一次。那一次回落是主路救场，不是影子比对，因此<b>不</b>记进
 * 本类——它由 {@link PiiFallbackHealer} 以 {@code pii.fallback} 单独计数。两个指标的分工：本类回答
 * 「切之前两列答案是否一致」，那个类回答「切之后有没有人靠旧列被救回来」。后者恒为 0 才是 7 天闸门
 * 在切读后的延续；不单独计，兜底会让「回填有洞」永远沉默（登录照常成功，用户无感）。
 *
 * <p><b>hmac 侧查询不写在本类</b>：谓词统一由 {@link PiiHmacQueries} 构造，与切读后真正出结果的
 * 那条查询共用一份——否则影子期证明的是 A 查询、上线跑的是 B 查询，闸门便失去意义。
 *
 * <p><b>inquiry_requests 没有影子切点，这不是遗漏</b>：V30 给它加了 {@code rt_phone_hmac} 并已双写+
 * 回填，但主代码里对该表的读<b>没有一处按 rt_phone 圈选</b>（全部按 id / tenant / wholesaler / status
 * 查），15 §1.2-C6 也只把它列为「落库 + 确认转 settle 透传」的写触点、§4 Step 2 的切读清单同样只有
 * blacklist / sms_codes / pricing。没有明文读路径就没有「两列答案对不对得上」可比，硬造一个探针只会
 * 往分母里灌永远 MATCHED 的噪音。该列的正确性由 {@link PiiBackfillService#reconcile()} 的对账兜底；
 * 将来若真出现按手机号查询询价单的入口，接切点时一并补进本类。
 *
 * <h3>读数</h3>
 * Micrometer 指标 {@code pii.shadow{pointcut,verdict}}（prod 经 actuator 观测 7 天闸门）；
 * 同时留一份进程内快照 {@link #snapshot()} 供关卡测试与无监控环境排障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiShadowReader {

    /** Micrometer 指标名（tag：pointcut / verdict）。 */
    public static final String METRIC = "pii.shadow";

    /** 多行圈选切点不一致时，单条日志最多打几个行 id。 */
    private static final int MAX_LOGGED_IDS = 10;

    /** 影子比对结论。除 {@link #MATCHED}/{@link #SKIPPED} 外都算 mismatch，都要拦在闸门外。 */
    public enum Verdict {
        /** 两边指向同一行（或都未命中）——正常。 */
        MATCHED,
        /** 旧列命中、hmac 未命中——回填遗漏或写入口漏网。Step 3 切读后这类人会被误锁，故必须清零。 */
        MISSING,
        /** 旧列未命中、hmac 命中——不该发生；疑似脏数据或旧列被改而 hmac 没跟上。 */
        EXTRA,
        /** 两边都命中但不是同一行——最危险：hmac 撞键或规范化漂移（R1）。 */
        DIVERGED,
        /** 影子查询自身抛异常（已吞下，主路不受影响）。 */
        ERROR,
        /** 入参算不出 hmac（明文空白），本就无影子可比，不计入分母。 */
        SKIPPED
    }

    private final PiiProperties properties;
    private final PiiCrypto piiCrypto;
    private final UserMapper userMapper;
    private final BlacklistMapper blacklistMapper;
    private final CustomerPriceMapper customerPriceMapper;
    private final SmsCodeMapper smsCodeMapper;
    /** 无监控环境（含测试上下文）可能没有 MeterRegistry，故用 ObjectProvider 软依赖。 */
    private final ObjectProvider<MeterRegistry> meterRegistry;

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** 是否处于影子期（调用方可用它跳过组装影子入参的开销；不调用也不会有副作用）。 */
    public boolean isShadowRead() {
        return properties.isShadowRead();
    }

    /**
     * A1–A6 登录链影子双查：旧列 {@code phone_hash} 已查得 {@code legacy}，再用
     * {@code phone_hmac} 查一遍比对。
     *
     * @param pointcut 切点名（如 {@code A2-login}），进指标 tag，须为常量
     * @param phone    明文手机号（只用于算 hmac，不入日志）
     * @param legacy   旧列查询结果，未命中传 null
     */
    public void checkUser(String pointcut, String phone, User legacy) {
        // Step 3 起登录链有了自己的灰度模块（PiiModule.LOGIN），未登记时回落全局 read-mode
        // ——也就是 Step 1「7 天 mismatch=0」闸门组的口径。一旦 login 拨到 hmac，本探针停摆
        // （已无「旧列的答案」可比），闸门的延续改看 pii.fallback，见 PiiFallbackHealer。
        if (!properties.isShadowRead(PiiModule.LOGIN)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(phone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            User shadow = userMapper.selectOne(PiiHmacQueries.user(hmac).select(User::getId));
            return compare(pointcut, legacy == null ? null : legacy.getId(),
                    shadow == null ? null : shadow.getId());
        });
    }

    /**
     * B2 加黑查重/复活影子双查：旧列 {@code target_value} 已查得 {@code legacy}，再用
     * {@code target_value_hmac} 查一遍比对。
     *
     * <p>LICENSE_NO 行的 hmac 恒 NULL（15 §2-1 保留明文分支），直接跳过——不是缺口。
     */
    public void checkBlacklistEntry(String pointcut, String targetType, String targetValue, Blacklist legacy) {
        if (!properties.isShadowRead(PiiModule.BLACKLIST) || !"PHONE".equals(targetType)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(targetValue);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            Blacklist shadow = blacklistMapper.selectOne(PiiHmacQueries.blacklistEntry(hmac)
                    .select(Blacklist::getId));
            return compare(pointcut, legacy == null ? null : legacy.getId(),
                    shadow == null ? null : shadow.getId());
        });
    }

    /**
     * B1 入驻命中检查影子双查：命中判定是布尔（ACTIVE 行数 &gt; 0），故比的是"命不命中"而非行 id。
     *
     * @param legacyHit 旧列口径的命中结论（调用方已算出，本类不改它）
     */
    public void checkBlacklistHit(String pointcut, String phone, boolean legacyHit) {
        if (!properties.isShadowRead(PiiModule.BLACKLIST)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(phone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            boolean shadowHit = blacklistMapper.selectCount(PiiHmacQueries.blacklistActiveHit(hmac)) > 0;
            if (legacyHit == shadowHit) {
                return Verdict.MATCHED;
            }
            Verdict verdict = legacyHit ? Verdict.MISSING : Verdict.EXTRA;
            log.warn("[PII-shadow] 切点 {} 影子双查不一致：verdict={} legacyHit={} shadowHit={}",
                    pointcut, verdict, legacyHit, shadowHit);
            return verdict;
        });
    }

    /**
     * C1/C2 定价链单行影子双查（PII-W5）：旧列 {@code rt_phone} 已查得 {@code legacy}，
     * 再用 {@code rt_phone_hmac} 查一遍比对。
     *
     * <p>影子查询必须逐条镜像主路的<b>其余</b>谓词，否则比的是两个不同问题：唯一键 upsert 探测
     * （C1）不带 status，价格解析（C2）带 {@code status=ACTIVE}——故 status 由调用方传入，
     * 传 null 表示主路本就没按状态过滤。逻辑删除行两边都由 MP 自动排除。
     *
     * @param pointcut     切点名（如 {@code C1-price-settle}），进指标 tag，须为常量
     * @param wholesalerId 商户（唯一键首列，两边同传）
     * @param rtPhone      客户明文手机号（只用于算 hmac，不入日志）
     * @param skuId        商品（唯一键末列，两边同传）
     * @param status       主路若按状态过滤则传该状态，否则传 null
     * @param legacy       旧列查询结果，未命中传 null
     */
    public void checkCustomerPrice(String pointcut, Long wholesalerId, String rtPhone, Long skuId,
                                   String status, CustomerPrice legacy) {
        if (!properties.isShadowRead(PiiModule.PRICING)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(rtPhone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            CustomerPrice shadow = customerPriceMapper.selectOne(
                    PiiHmacQueries.customerPrice(wholesalerId, hmac, skuId, status)
                            .select(CustomerPrice::getId));
            return compare(pointcut, legacy == null ? null : legacy.getId(),
                    shadow == null ? null : shadow.getId());
        });
    }

    /**
     * C3 批量调价「按 rtPhone 圈选」影子双查（PII-W5）：主路一次圈出<b>多行</b>，故比的是行 id 集合
     * 而非单行——少一行就是一笔改不到的价，多一行就是一笔改错的价，两者都必须在切读前清零。
     *
     * <p>{@code rtPhone} 为空表示本次走的是「显式 ids」分支或没带手机号过滤，主路<b>根本没读</b>
     * 明文手机号列，此时直接不探测（同 {@link #checkBlacklistEntry} 对 LICENSE_NO 的处理）——
     * 不是 SKIPPED，是压根不该进这个切点的分母。
     *
     * @param skuId      主路若按 SKU 过滤则传该 SKU，否则传 null（须与主路条件一致）
     * @param legacyRows 旧列圈选结果，空列表表示未命中
     */
    public void checkCustomerPriceRows(String pointcut, Long wholesalerId, Long skuId,
                                       String rtPhone, List<CustomerPrice> legacyRows) {
        if (!properties.isShadowRead(PiiModule.PRICING) || rtPhone == null || rtPhone.isBlank()) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(rtPhone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            List<CustomerPrice> shadow = customerPriceMapper.selectList(
                    PiiHmacQueries.customerPriceRows(wholesalerId, skuId, hmac)
                            .select(CustomerPrice::getId));
            return compareIds(pointcut, idsOf(legacyRows), idsOf(shadow));
        });
    }

    /**
     * SMS 验证码校验影子双查（PII-W5）：旧列 {@code phone} 已查得 {@code legacy}，再用
     * {@code phone_hmac} 查一遍比对。
     *
     * <p>scene / code / 未核销 / 取最新一条这几个谓词逐条照抄主路——只换手机号那一列，才比得出
     * 「换成 hmac 后还能不能捞到同一条码」。测试态的 mock 万能码在主路里先于本查询短路，
     * 那条路径没有 DB 读，自然也不进分母。
     */
    public void checkSmsCode(String pointcut, String phone, String scene, String code, SmsCode legacy) {
        if (!properties.isShadowRead(PiiModule.SMS)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(phone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            SmsCode shadow = smsCodeMapper.selectOne(PiiHmacQueries.smsCode(hmac, scene, code)
                    .select(SmsCode::getId));
            return compare(pointcut, legacy == null ? null : legacy.getId(),
                    shadow == null ? null : shadow.getId());
        });
    }

    /**
     * 计数快照，key = {@code 切点/结论}（如 {@code A2-login/MATCHED}）。
     * 供关卡测试做前后差值断言、以及无 Micrometer 环境排障；返回不可变副本。
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.sum()));
        return Collections.unmodifiableMap(out);
    }

    /** 单项读数（缺省 0）。 */
    public long count(String pointcut, Verdict verdict) {
        LongAdder adder = counters.get(key(pointcut, verdict));
        return adder == null ? 0L : adder.sum();
    }

    /** 跑一次影子探测并计数；异常一律吞在这里，主路永远不受影响。 */
    private void probe(String pointcut, Supplier<Verdict> probe) {
        Verdict verdict;
        try {
            verdict = probe.get();
        } catch (Exception e) {
            verdict = Verdict.ERROR;
            log.error("[PII-shadow] 切点 {} 影子双查异常（已吞下，主路结果不受影响）", pointcut, e);
        }
        record(pointcut, verdict);
    }

    /** 按行 id 比对两列答案；不一致时告警（只打 id，不打手机号/hmac）。 */
    private Verdict compare(String pointcut, Long legacyId, Long shadowId) {
        Verdict verdict;
        if (legacyId == null && shadowId == null) {
            verdict = Verdict.MATCHED;
        } else if (legacyId != null && legacyId.equals(shadowId)) {
            verdict = Verdict.MATCHED;
        } else if (shadowId == null) {
            verdict = Verdict.MISSING;
        } else if (legacyId == null) {
            verdict = Verdict.EXTRA;
        } else {
            verdict = Verdict.DIVERGED;
        }
        if (verdict != Verdict.MATCHED) {
            log.warn("[PII-shadow] 切点 {} 影子双查不一致：verdict={} legacyId={} shadowId={}",
                    pointcut, verdict, legacyId, shadowId);
        }
        return verdict;
    }

    /**
     * 按行 id 集合比对两列答案（多行圈选切点）；语义与 {@link #compare} 逐条对齐：
     * 影子少捞 = MISSING，多捞 = EXTRA，两头都对不上 = DIVERGED。
     */
    private Verdict compareIds(String pointcut, Set<Long> legacyIds, Set<Long> shadowIds) {
        if (legacyIds.equals(shadowIds)) {
            return Verdict.MATCHED;
        }
        Set<Long> onlyLegacy = new LinkedHashSet<>(legacyIds);
        onlyLegacy.removeAll(shadowIds);
        Set<Long> onlyShadow = new LinkedHashSet<>(shadowIds);
        onlyShadow.removeAll(legacyIds);
        Verdict verdict;
        if (!onlyLegacy.isEmpty() && !onlyShadow.isEmpty()) {
            verdict = Verdict.DIVERGED;
        } else if (!onlyLegacy.isEmpty()) {
            verdict = Verdict.MISSING;
        } else {
            verdict = Verdict.EXTRA;
        }
        log.warn("[PII-shadow] 切点 {} 影子双查不一致：verdict={} legacyCount={} shadowCount={}"
                        + " onlyLegacyIds={} onlyShadowIds={}",
                pointcut, verdict, legacyIds.size(), shadowIds.size(),
                boundedIds(onlyLegacy), boundedIds(onlyShadow));
        return verdict;
    }

    private static Set<Long> idsOf(List<CustomerPrice> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CustomerPrice row : rows) {
            ids.add(row.getId());
        }
        return ids;
    }

    /** 圈选口径的差集可能很大；日志只留前 {@value #MAX_LOGGED_IDS} 个 id + 总数，避免刷爆日志。 */
    private static String boundedIds(Collection<Long> ids) {
        if (ids.size() <= MAX_LOGGED_IDS) {
            return ids.toString();
        }
        return new ArrayList<>(ids).subList(0, MAX_LOGGED_IDS) + "...(共" + ids.size() + ")";
    }

    private void record(String pointcut, Verdict verdict) {
        counters.computeIfAbsent(key(pointcut, verdict), k -> new LongAdder()).increment();
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(METRIC, "pointcut", pointcut, "verdict", verdict.name()).increment();
        }
    }

    private static String key(String pointcut, Verdict verdict) {
        return pointcut + "/" + verdict;
    }
}
