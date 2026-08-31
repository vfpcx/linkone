package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.SmsCodeMapper;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.pricing.mapper.CustomerPriceMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PII 阶段 0 存量回填 + 对账（15-pii-hardening-v2 §4 / task_plan「存量回填幂等+对账」）。
 *
 * <p>V27/V30 只加空列，双写切点只覆盖 <b>新写入</b>；上线前的历史行 hmac 恒 NULL。不回填则
 * 阶段 1 影子灰度没有对账基准（覆盖率永远算不满），阶段 2 更无法收缩明文列。本类补这一环。
 *
 * <p><b>幂等</b>由两层保证，可无限次重跑：
 * <ol>
 *   <li>选行条件 {@code hmac IS NULL}——已填行不再进入候选集；</li>
 *   <li>更新条件同样带 {@code hmac IS NULL}（CAS 语义）——与并发双写抢同一行时，
 *       后到者影响 0 行而非覆盖，杜绝回填把双写刚写好的值改坏。</li>
 * </ol>
 *
 * <p><b>回滚口径</b>：{@code write-mode=legacy} 时拒绝回填（与双写切点同一分叉判断）。
 * 拨回 legacy 是止血动作，此时不应再有任何进程往新列写数据。
 *
 * <p><b>读路径一律不动</b>（阶段 0 红线）：本类只写 hmac 列、只读明文列做校验，
 * 不参与任何登录/查重/命中判定。
 *
 * <p>逻辑删除行（{@code deleted_at IS NOT NULL}）由 MyBatis-Plus 全局逻辑删除自动排除，
 * 与读路径口径一致——已删账号不进盲索引，也不计入对账分母。
 *
 * <p><b>覆盖范围</b>：V27 两表（users / blacklist PHONE 行）+ V30 三表（customer_prices /
 * sms_codes / inquiry_requests）。五张表只有「明文列在哪、hmac 列在哪、哪些行参与」三项不同，
 * 算法完全一致，故收敛到 {@link HmacColumn} 一处描述 + 一份 {@link #backfill}/{@link #reconcile}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PiiBackfillService {

    /** 单批默认行数；批内逐行 CAS 更新，不长事务持锁。 */
    private static final int DEFAULT_BATCH = 500;

    /** 黑名单只有 PHONE 行参与盲索引，LICENSE_NO 行恒 NULL（15 §2-1）。 */
    private static final String TARGET_TYPE_PHONE = "PHONE";

    private final UserMapper userMapper;
    private final BlacklistMapper blacklistMapper;
    private final CustomerPriceMapper customerPriceMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final InquiryRequestMapper inquiryRequestMapper;
    private final PiiCrypto piiCrypto;

    /**
     * 回填结果。
     *
     * @param refused  是否因 legacy 模式被拒（此时其余计数恒 0）
     * @param scanned  扫描到的待填候选行数
     * @param filled   实际写入 hmac 的行数
     * @param skipped  明文为空白、算不出 hmac 而跳过的行数
     */
    public record BackfillResult(boolean refused, int scanned, int filled, int skipped) {
        /** 命名不可与组件访问器 {@code refused()} 同名，否则编译器按访问器解析。 */
        static BackfillResult ofRefused() {
            return new BackfillResult(true, 0, 0, 0);
        }
    }

    /**
     * 对账结果：把明文重算一遍与库里的 hmac 逐行比对。
     *
     * @param table      表名
     * @param total      参与对账的行数（明文非空且未逻辑删除）
     * @param matched    hmac 已填且与重算值一致
     * @param missing    hmac 仍为 NULL（回填未覆盖）
     * @param mismatched hmac 已填但与重算值不一致（<b>最危险</b>：密钥漂移或写入口漏网）
     */
    public record ReconcileResult(String table, long total, long matched, long missing, long mismatched) {
        /** 零差异即可进入阶段 1 影子灰度。 */
        public boolean clean() {
            return missing == 0 && mismatched == 0;
        }
    }

    // ---------------------------------------------------------------- 表口径

    /**
     * 一张表参与盲索引的口径：主键（keyset 游标用）、明文列、hmac 列、附加行过滤。
     *
     * <p>回填与对账的算法对五张表完全一致，差异只在这四项——把差异收成数据，
     * 就不会出现「某张表的 CAS 条件抄漏了」这类只在并发下才现形的复制粘贴事故。
     *
     * @param rowFilter 附加行过滤（blacklist 只认 PHONE 行）；无附加条件传 {@code qw -> { }}
     */
    private record HmacColumn<T>(String table,
                                 BaseMapper<T> mapper,
                                 SFunction<T, Long> id,
                                 SFunction<T, String> plain,
                                 SFunction<T, String> hmac,
                                 Consumer<LambdaQueryWrapper<T>> rowFilter) {

        /** 全表行都参与的表（users / customer_prices / sms_codes / inquiry_requests）。 */
        static <T> HmacColumn<T> of(String table, BaseMapper<T> mapper,
                                    SFunction<T, Long> id,
                                    SFunction<T, String> plain,
                                    SFunction<T, String> hmac) {
            return new HmacColumn<>(table, mapper, id, plain, hmac, qw -> { });
        }
    }

    /** users.phone_hmac（登录命门，V27）。 */
    private HmacColumn<User> usersColumn() {
        return HmacColumn.of("users", userMapper, User::getId, User::getPhone, User::getPhoneHmac);
    }

    /** blacklist.target_value_hmac（V27）：仅 PHONE 行，LICENSE_NO 行恒 NULL（15 §2-1）。 */
    private HmacColumn<Blacklist> blacklistColumn() {
        return new HmacColumn<>("blacklist", blacklistMapper, Blacklist::getId,
                Blacklist::getTargetValue, Blacklist::getTargetValueHmac,
                qw -> qw.eq(Blacklist::getTargetType, TARGET_TYPE_PHONE));
    }

    /** customer_prices.rt_phone_hmac（V30，§1.2-C 定价链）。逻辑删除行由 MP 自动排除。 */
    private HmacColumn<CustomerPrice> customerPricesColumn() {
        return HmacColumn.of("customer_prices", customerPriceMapper, CustomerPrice::getId,
                CustomerPrice::getRtPhone, CustomerPrice::getRtPhoneHmac);
    }

    /** sms_codes.phone_hmac（V30，短信校验链）。 */
    private HmacColumn<SmsCode> smsCodesColumn() {
        return HmacColumn.of("sms_codes", smsCodeMapper, SmsCode::getId,
                SmsCode::getPhone, SmsCode::getPhoneHmac);
    }

    /** inquiry_requests.rt_phone_hmac（V30，与 customer_prices.rt_phone 同一身份口径）。 */
    private HmacColumn<InquiryRequest> inquiryRequestsColumn() {
        return HmacColumn.of("inquiry_requests", inquiryRequestMapper, InquiryRequest::getId,
                InquiryRequest::getRtPhone, InquiryRequest::getRtPhoneHmac);
    }

    // ---------------------------------------------------------------- 回填

    /** 回填 users.phone_hmac。默认批大小。 */
    public BackfillResult backfillUsers() {
        return backfillUsers(DEFAULT_BATCH);
    }

    /** 回填 users.phone_hmac；幂等，可重跑。 */
    public BackfillResult backfillUsers(int batchSize) {
        return backfill(usersColumn(), batchSize);
    }

    /** 回填 blacklist.target_value_hmac（仅 PHONE 行）。默认批大小。 */
    public BackfillResult backfillBlacklist() {
        return backfillBlacklist(DEFAULT_BATCH);
    }

    /** 回填 blacklist.target_value_hmac（仅 PHONE 行）；幂等，可重跑。 */
    public BackfillResult backfillBlacklist(int batchSize) {
        return backfill(blacklistColumn(), batchSize);
    }

    /** 回填 customer_prices.rt_phone_hmac（V30）。默认批大小。 */
    public BackfillResult backfillCustomerPrices() {
        return backfillCustomerPrices(DEFAULT_BATCH);
    }

    /** 回填 customer_prices.rt_phone_hmac；幂等，可重跑。 */
    public BackfillResult backfillCustomerPrices(int batchSize) {
        return backfill(customerPricesColumn(), batchSize);
    }

    /** 回填 sms_codes.phone_hmac（V30）。默认批大小。 */
    public BackfillResult backfillSmsCodes() {
        return backfillSmsCodes(DEFAULT_BATCH);
    }

    /**
     * 回填 sms_codes.phone_hmac；幂等，可重跑。
     *
     * <p>本表行数随发码量线性增长且历史行多已过期。此处仍<b>全表</b>回填而不只填未过期行——
     * 对账分母若按「未过期」滑动，闸门口径会随时间漂移，无法证明「回填填全了」。
     * 生产首跑成本由批大小（{@code cangchu.pii.backfill-batch-size}）控制。
     */
    public BackfillResult backfillSmsCodes(int batchSize) {
        return backfill(smsCodesColumn(), batchSize);
    }

    /** 回填 inquiry_requests.rt_phone_hmac（V30）。默认批大小。 */
    public BackfillResult backfillInquiryRequests() {
        return backfillInquiryRequests(DEFAULT_BATCH);
    }

    /** 回填 inquiry_requests.rt_phone_hmac；幂等，可重跑。 */
    public BackfillResult backfillInquiryRequests(int batchSize) {
        return backfill(inquiryRequestsColumn(), batchSize);
    }

    /**
     * 回填单表：{@code hmac IS NULL} 选行 + 同条件 CAS 更新，可无限次重跑。
     *
     * <p>{@code write-mode != dual} 直接拒填——拨回 legacy 是止血动作，此时不应再有进程往新列写。
     */
    private <T> BackfillResult backfill(HmacColumn<T> col, int batchSize) {
        if (!piiCrypto.isDualWrite()) {
            log.warn("[PII] 回填被拒：write-mode 非 dual（回滚口径下不得再往 hmac 列写入）");
            return BackfillResult.ofRefused();
        }
        int size = batchSize > 0 ? batchSize : DEFAULT_BATCH;
        int scanned = 0, filled = 0, skipped = 0;

        while (true) {
            LambdaQueryWrapper<T> qw = new LambdaQueryWrapper<T>()
                    .select(col.id(), col.plain())
                    .isNull(col.hmac())
                    .isNotNull(col.plain());
            col.rowFilter().accept(qw);
            List<T> rows = col.mapper().selectList(qw.last("LIMIT " + size));
            if (rows.isEmpty()) {
                break;
            }
            scanned += rows.size();
            int filledThisBatch = 0;
            for (T row : rows) {
                String hmac = piiCrypto.phoneHmac(col.plain().apply(row));
                if (hmac == null) {
                    skipped++;
                    continue;
                }
                filledThisBatch += col.mapper().update(null, new LambdaUpdateWrapper<T>()
                        .set(col.hmac(), hmac)
                        .eq(col.id(), col.id().apply(row))
                        .isNull(col.hmac()));
            }
            filled += filledThisBatch;
            // 整批一行都没填成（明文全空白 / 全被并发双写抢先），再循环就是死循环
            if (filledThisBatch == 0) {
                break;
            }
        }
        log.info("[PII] {} 回填完成：扫描 {} 填充 {} 跳过 {}", col.table(), scanned, filled, skipped);
        return new BackfillResult(false, scanned, filled, skipped);
    }

    // ---------------------------------------------------------------- 按需补填（单行）

    /**
     * 单行按需补填的结果（15 §4 Step 3 / 波次 PII-W6 的兜底自愈）。
     *
     * @see #healUserHmac
     */
    public enum HealResult {
        /** {@code write-mode} 非 dual，拒填——拨回 legacy 是止血动作，此时不应再有进程往新列写。 */
        REFUSED,
        /** 明文空白，算不出 hmac，本就不该有值。 */
        SKIPPED,
        /** CAS 成功写入。 */
        FILLED,
        /** CAS 影响 0 行——该行 hmac 已被并发双写/批量回填抢先填上，按幂等口径不覆盖。 */
        NOOP
    }

    /**
     * 按需补填单行 {@code users.phone_hmac}（Step 3 登录双读兜底触发）。
     *
     * <p><b>为什么走这里而不是各自写一条 update</b>：hmac 的<b>写入</b>口径必须和批量回填是同一份
     * ——CAS 条件（{@code hmac IS NULL}）、拒填条件（{@code write-mode != dual}）、hmac 产生点
     * （{@link PiiCrypto#phoneHmac}）三件事任一处抄漏，都只在并发或回滚态下才现形。这与
     * {@link PiiHmacQueries} 收敛<b>读</b>谓词是同一个理由。
     *
     * <p>调用方是 {@link PiiFallbackHealer} 的异步线程，<b>不在主路事务内</b>：本方法只把一个
     * 本来就该有值的列补上，与主路事务成败无关，也绝不能因为它失败而影响登录。
     */
    public HealResult healUserHmac(Long userId, String phone) {
        return healOne(usersColumn(), userId, phone);
    }

    /** 单行 CAS 补填；语义与 {@link #backfill} 的批内逐行更新逐条相同，只是不扫表。 */
    private <T> HealResult healOne(HmacColumn<T> col, Long id, String plain) {
        if (!piiCrypto.isDualWrite()) {
            log.warn("[PII] {}.id={} 按需补填被拒：write-mode 非 dual（回滚口径下不得再往 hmac 列写入）",
                    col.table(), id);
            return HealResult.REFUSED;
        }
        String hmac = piiCrypto.phoneHmac(plain);
        if (hmac == null) {
            return HealResult.SKIPPED;
        }
        int affected = col.mapper().update(null, new LambdaUpdateWrapper<T>()
                .set(col.hmac(), hmac)
                .eq(col.id(), id)
                .isNull(col.hmac()));
        return affected > 0 ? HealResult.FILLED : HealResult.NOOP;
    }

    // ---------------------------------------------------------------- 对账

    /** 五张表一起对账（users / blacklist PHONE 行 / customer_prices / sms_codes / inquiry_requests）。 */
    public List<ReconcileResult> reconcile() {
        return List.of(reconcileUsers(), reconcileBlacklist(),
                reconcileCustomerPrices(), reconcileSmsCodes(), reconcileInquiryRequests());
    }

    /** users 对账：明文重算 HMAC 与库中值逐行比对。 */
    public ReconcileResult reconcileUsers() {
        return reconcile(usersColumn());
    }

    /** blacklist 对账：仅 PHONE 行参与；LICENSE_NO 行恒 NULL 属预期，不计入。 */
    public ReconcileResult reconcileBlacklist() {
        return reconcile(blacklistColumn());
    }

    /** customer_prices 对账（V30）。 */
    public ReconcileResult reconcileCustomerPrices() {
        return reconcile(customerPricesColumn());
    }

    /** sms_codes 对账（V30）。 */
    public ReconcileResult reconcileSmsCodes() {
        return reconcile(smsCodesColumn());
    }

    /** inquiry_requests 对账（V30）。 */
    public ReconcileResult reconcileInquiryRequests() {
        return reconcile(inquiryRequestsColumn());
    }

    /**
     * 单表对账：把明文重算一遍与库里的 hmac 逐行比对。
     *
     * <p>keyset 分页（按 id 递增游标）而非 OFFSET——回填/双写并发进行时 OFFSET 会漏行。
     */
    private <T> ReconcileResult reconcile(HmacColumn<T> col) {
        long total = 0, matched = 0, missing = 0, mismatched = 0;
        long cursor = Long.MIN_VALUE;

        while (true) {
            LambdaQueryWrapper<T> qw = new LambdaQueryWrapper<T>()
                    .select(col.id(), col.plain(), col.hmac())
                    .gt(col.id(), cursor)
                    .isNotNull(col.plain());
            col.rowFilter().accept(qw);
            List<T> rows = col.mapper().selectList(
                    qw.orderByAsc(col.id()).last("LIMIT " + DEFAULT_BATCH));
            if (rows.isEmpty()) {
                break;
            }
            for (T row : rows) {
                cursor = col.id().apply(row);
                String expected = piiCrypto.phoneHmac(col.plain().apply(row));
                if (expected == null) {
                    continue; // 明文空白，本就不该有 hmac，不计入分母
                }
                total++;
                String actual = col.hmac().apply(row);
                if (actual == null) {
                    missing++;
                } else if (expected.equalsIgnoreCase(actual)) {
                    matched++;
                } else {
                    mismatched++;
                    // 只打表名/id，绝不打明文/hmac（F7 日志不落 PII）
                    log.error("[PII] 对账不一致：{}.id={} 的 hmac 列与明文重算值不符", col.table(), cursor);
                }
            }
            if (rows.size() < DEFAULT_BATCH) {
                break;
            }
        }
        ReconcileResult result = new ReconcileResult(col.table(), total, matched, missing, mismatched);
        log.info("[PII] {} 对账：{}", col.table(), result);
        return result;
    }

    /** 找出仍未回填的表（供上线检查单/阶段 1 准入判定）。 */
    public List<String> unreadyTables() {
        List<String> unready = new ArrayList<>();
        for (ReconcileResult r : reconcile()) {
            if (!r.clean()) {
                unready.add(r.table());
            }
        }
        return unready;
    }
}
