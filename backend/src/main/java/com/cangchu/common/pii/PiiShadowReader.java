package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * 只覆盖<b>已有 hmac 列且已双写+回填</b>的两张表：users（A1–A6 登录链）、blacklist PHONE 行
 * （B1 命中检查 / B2 加黑查重）。
 *
 * <p>customer_prices / sms_codes / inquiry_requests（§1.2-C 定价链、sms 校验）的加列+双写+回填+对账
 * 已由 V30 补齐，但<b>影子切点尚未接入</b>——那几条是读路径改造，随 Step 2（PII-W5）一起做，
 * 不在 Step 1 的闸门分母内。
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
        if (!properties.isShadowRead()) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(phone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            User shadow = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .select(User::getId)
                    .eq(User::getPhoneHmac, hmac)
                    .last("LIMIT 1"));
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
        if (!properties.isShadowRead() || !"PHONE".equals(targetType)) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(targetValue);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            Blacklist shadow = blacklistMapper.selectOne(new LambdaQueryWrapper<Blacklist>()
                    .select(Blacklist::getId)
                    .eq(Blacklist::getTargetType, "PHONE")
                    .eq(Blacklist::getTargetValueHmac, hmac)
                    .last("LIMIT 1"));
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
        if (!properties.isShadowRead()) {
            return;
        }
        probe(pointcut, () -> {
            String hmac = piiCrypto.phoneHmac(phone);
            if (hmac == null) {
                return Verdict.SKIPPED;
            }
            boolean shadowHit = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                    .eq(Blacklist::getTargetType, "PHONE")
                    .eq(Blacklist::getTargetValueHmac, hmac)
                    .eq(Blacklist::getStatus, "ACTIVE")) > 0;
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
