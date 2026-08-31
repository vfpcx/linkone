package com.cangchu.common.pii;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * PII 阶段 1 Step 3 · 登录链双读兜底的<b>计数</b>与<b>异步补写</b>
 * （15-pii-hardening-v2 §4 Step 3 / 波次 PII-W6）。
 *
 * <h3>为什么必须有这个类</h3>
 * Step 2 的四块是<b>硬切</b>：hmac 未命中就是真未命中，代价立刻可见（该拦的人被放行、专属价回退
 * 公开价、验证码不通过）。登录不能这么切——一行 hmac 漏填就是那个人登不上、找不回密码、重复注册
 * 撞唯一键，所以 {@link PiiReadRouter#user} 给它加了旧列兜底。
 *
 * <p>但兜底本身有个致命副作用：<b>它让缺陷永远沉默</b>。回填漏了一行，用户照样登进来，日志没有
 * 异常，监控没有毛刺，运维永远不会知道那一行是靠明文列救回来的——而 Step 2 的类注释里写死过一句
 * 话：「用运行时兜底掩盖，等于把『回填有洞』这个事实永久藏起来，闸门也就再没有归零的一天」。
 * 登录链选了兜底，就必须自己把这条口子堵上：<b>每一次兜底都记一笔</b>，
 * 指标 {@code pii.fallback{pointcut,verdict}}。
 *
 * <h3>与 pii.shadow 的分工，以及和 7 天闸门的关系</h3>
 * <ul>
 *   <li>{@link PiiShadowReader} 的 {@code pii.shadow} 回答的是<b>切之前</b>「两列答案是否一致」。
 *       Step 1 的准入线就是它：{@code mismatch 连续 ≥7 天为 0}。</li>
 *   <li>登录一旦拨到 {@code hmac}，{@code checkUser} 探针停摆（同 Step 2 对 B1/B2 的处理）——
 *       此时 {@code pii.shadow} 的 A1–A6 分母归零，「零 mismatch」会从<b>确实没有不一致</b>
 *       悄悄变成<b>压根没在观测</b>。这两件事在监控面板上长得一模一样，这正是最危险的地方。</li>
 *   <li>{@link Verdict#FALLBACK} 就是那道闸门在切读后的<b>延续</b>：它的语义与影子期的
 *       {@code MISSING}（旧列命中、hmac 未命中）逐字节相同，只是从旁观计数变成了兜底救场。
 *       <b>准入线因此不变：切读后 FALLBACK 必须仍恒为 0。</b>一旦 &gt; 0，说明 7 天闸门放行时
 *       就已经有洞（或切读后有新写入路径漏了 hmac），该重跑回填、并视规模决定是否拨回
 *       {@code shadow}。</li>
 *   <li>{@link Verdict#HEALED} <b>不抵消</b> FALLBACK 这笔账。自愈说明这一行以后不会再出事，
 *       不说明当初的闸门是对的。把 HEALED 当成「已解决」去消警报，等于又把洞藏回去了。</li>
 * </ul>
 *
 * <h3>本类不做的事</h3>
 * 不比对「hmac 命中的是不是同一行」。切读后 hmac 命中即直接出结果，明文列根本不查，没有第二个
 * 答案可比——hmac 撞键 / 规范化漂移（R1）这类 {@code DIVERGED} 风险由影子期的 7 天闸门负责，
 * 双读兜底救不了也测不出。这是「先影子再切读」这个顺序本身在承担的责任。
 *
 * <h3>异步补写</h3>
 * 单线程 + 有界队列的自带执行器（<b>不引入 {@code @EnableAsync}</b>，避免为一个补写动作给全应用
 * 装上代理式异步语义）。补写走 {@link PiiBackfillService#healUserHmac}，与批量回填共用同一份 CAS
 * 写入口径。三条红线：
 * <ol>
 *   <li><b>不在主路事务里</b>——另起线程即另一个连接，登录事务的成败与补写互不牵连
 *       （A1 查重命中后主路会抛 40004 回滚，补写仍应落库：那一行的 hmac 本来就该有值）。</li>
 *   <li><b>异常一律吞</b>——主路早已返回，此时抛出去也没人接；吞掉但计 {@link Verdict#HEAL_FAILED}。</li>
 *   <li><b>队列满就丢</b>并计 {@link Verdict#HEAL_DROPPED}，绝不阻塞调用方。真到了要丢的量级，
 *       该做的是重跑批量回填，不是让登录排队等补写。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiFallbackHealer {

    /** Micrometer 指标名（tag：pointcut / verdict）。刻意区别于 {@code pii.shadow}。 */
    public static final String METRIC = "pii.fallback";

    /** 补写队列上限；满了就丢并计数，不阻塞登录。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 双读结论。{@link #FALLBACK} 是唯一的闸门指标，其余三个是配套读数。 */
    public enum Verdict {
        /** hmac 列直接出结果——切读后的正常态，也是兜底率的分母。 */
        HMAC_HIT,
        /** hmac 未命中且旧列也未命中：真未命中（新号注册、找回不存在的账号），<b>不是洞</b>。 */
        CONFIRMED_MISS,
        /**
         * hmac 未命中但旧列命中——<b>回填有洞的直接证据</b>。本次已由旧列兜底出结果，
         * 主路行为与切读前完全一致。切读后此值必须恒为 0，见类注释。
         */
        FALLBACK,
        /** 兜底触发的异步补写已落库（或已被并发写入抢先填上）。不抵消 FALLBACK。 */
        HEALED,
        /** 异步补写未成功：{@code write-mode} 非 dual 拒填、明文空白、或写入抛异常（已吞）。 */
        HEAL_FAILED,
        /** 补写队列已满被丢弃——补写没发生，该行下次登录还会再走一次兜底。 */
        HEAL_DROPPED
    }

    private final PiiBackfillService backfillService;
    /** 无监控环境（含测试上下文）可能没有 MeterRegistry，故用 ObjectProvider 软依赖（同影子类）。 */
    private final ObjectProvider<MeterRegistry> meterRegistry;

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** 在途补写数（提交即 +1，跑完即 -1）；仅供 {@link #awaitQuiescence} 判定静默。 */
    private final AtomicLong pending = new AtomicLong();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "pii-hmac-healer");
                // 守护线程：补写是尽力而为的修复动作，绝不该拖住 JVM 退出
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** hmac 列直接出结果。 */
    public void recordHmacHit(String pointcut) {
        record(pointcut, Verdict.HMAC_HIT);
    }

    /** 两列都未命中——真未命中。新号注册每次都会走到这里，故它绝不能计入 FALLBACK。 */
    public void recordConfirmedMiss(String pointcut) {
        record(pointcut, Verdict.CONFIRMED_MISS);
    }

    /**
     * hmac 未命中而旧列命中：记一笔 {@link Verdict#FALLBACK} 并异步补写该行的 hmac 列。
     *
     * <p>日志只打切点 / 行 id，不打手机号或 hmac（F7，同影子类与对账口径）。
     *
     * @param userId 旧列查到的行 id（补写目标）
     * @param phone  明文手机号，只传给补写算 hmac，不入日志、不入指标 tag
     */
    public void recordFallback(String pointcut, Long userId, String phone) {
        record(pointcut, Verdict.FALLBACK);
        log.warn("[PII-fallback] 切点 {} 走了双读兜底：hmac 未命中而旧列命中，userId={}。"
                        + "主路已由旧列照常出结果（登录不受影响），但这一笔就是回填有洞的证据"
                        + "——{} 的 FALLBACK 是 Step 1 七天闸门在切读后的延续，须恒为 0",
                pointcut, userId, METRIC);

        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    heal(pointcut, userId, phone);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            record(pointcut, Verdict.HEAL_DROPPED);
            log.error("[PII-fallback] 切点 {} 的补写被丢弃（队列已满 {}）userId={}"
                            + "——这一行下次仍会走兜底，请改跑批量回填",
                    pointcut, QUEUE_CAPACITY, userId);
        }
    }

    /** 异步补写；任何异常都吞在这里——主路早已返回，抛出去没人接。 */
    private void heal(String pointcut, Long userId, String phone) {
        try {
            PiiBackfillService.HealResult result = backfillService.healUserHmac(userId, phone);
            switch (result) {
                // NOOP = CAS 影响 0 行，说明并发双写/批量回填已经把它填上了，结果同样是「这行已自愈」
                case FILLED, NOOP -> record(pointcut, Verdict.HEALED);
                case REFUSED, SKIPPED -> {
                    record(pointcut, Verdict.HEAL_FAILED);
                    log.warn("[PII-fallback] 切点 {} 的补写未生效（{}）userId={}", pointcut, result, userId);
                }
            }
        } catch (Exception e) {
            record(pointcut, Verdict.HEAL_FAILED);
            log.error("[PII-fallback] 切点 {} 的补写异常（已吞下，主路早已返回）userId={}", pointcut, userId, e);
        }
    }

    /**
     * 计数快照，key = {@code 切点/结论}（如 {@code A2-login/FALLBACK}）。
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

    /**
     * 等待在途补写跑完。<b>仅供关卡测试</b>断言「事后 hmac 已补齐」——生产没有任何路径需要等它，
     * 补写是尽力而为的后台修复。
     *
     * @return 是否在超时前静默
     */
    public boolean awaitQuiescence(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() - deadline > 0) {
                return false;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
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
