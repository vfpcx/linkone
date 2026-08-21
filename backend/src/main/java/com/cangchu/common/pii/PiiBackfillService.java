package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * PII 阶段 0 存量回填 + 对账（15-pii-hardening-v2 §4 / task_plan「存量回填幂等+对账」）。
 *
 * <p>V27 只加空列，双写切点只覆盖 <b>新写入</b>；上线前的历史行 hmac 恒 NULL。不回填则
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

    // ---------------------------------------------------------------- 回填

    /** 回填 users.phone_hmac。默认批大小。 */
    public BackfillResult backfillUsers() {
        return backfillUsers(DEFAULT_BATCH);
    }

    /** 回填 users.phone_hmac；幂等，可重跑。 */
    public BackfillResult backfillUsers(int batchSize) {
        if (!piiCrypto.isDualWrite()) {
            log.warn("[PII] 回填被拒：write-mode 非 dual（回滚口径下不得再往 hmac 列写入）");
            return BackfillResult.ofRefused();
        }
        int size = batchSize > 0 ? batchSize : DEFAULT_BATCH;
        int scanned = 0, filled = 0, skipped = 0;

        while (true) {
            List<User> rows = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .select(User::getId, User::getPhone)
                    .isNull(User::getPhoneHmac)
                    .isNotNull(User::getPhone)
                    .last("LIMIT " + size));
            if (rows.isEmpty()) {
                break;
            }
            scanned += rows.size();
            int filledThisBatch = 0;
            for (User row : rows) {
                String hmac = piiCrypto.phoneHmac(row.getPhone());
                if (hmac == null) {
                    skipped++;
                    continue;
                }
                filledThisBatch += userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .set(User::getPhoneHmac, hmac)
                        .eq(User::getId, row.getId())
                        .isNull(User::getPhoneHmac));
            }
            filled += filledThisBatch;
            // 整批一行都没填成（明文全空白 / 全被并发双写抢先），再循环就是死循环
            if (filledThisBatch == 0) {
                break;
            }
        }
        log.info("[PII] users 回填完成：扫描 {} 填充 {} 跳过 {}", scanned, filled, skipped);
        return new BackfillResult(false, scanned, filled, skipped);
    }

    /** 回填 blacklist.target_value_hmac（仅 PHONE 行）。默认批大小。 */
    public BackfillResult backfillBlacklist() {
        return backfillBlacklist(DEFAULT_BATCH);
    }

    /** 回填 blacklist.target_value_hmac（仅 PHONE 行）；幂等，可重跑。 */
    public BackfillResult backfillBlacklist(int batchSize) {
        if (!piiCrypto.isDualWrite()) {
            log.warn("[PII] 回填被拒：write-mode 非 dual（回滚口径下不得再往 hmac 列写入）");
            return BackfillResult.ofRefused();
        }
        int size = batchSize > 0 ? batchSize : DEFAULT_BATCH;
        int scanned = 0, filled = 0, skipped = 0;

        while (true) {
            List<Blacklist> rows = blacklistMapper.selectList(new LambdaQueryWrapper<Blacklist>()
                    .select(Blacklist::getId, Blacklist::getTargetValue)
                    .eq(Blacklist::getTargetType, TARGET_TYPE_PHONE)
                    .isNull(Blacklist::getTargetValueHmac)
                    .isNotNull(Blacklist::getTargetValue)
                    .last("LIMIT " + size));
            if (rows.isEmpty()) {
                break;
            }
            scanned += rows.size();
            int filledThisBatch = 0;
            for (Blacklist row : rows) {
                String hmac = piiCrypto.phoneHmac(row.getTargetValue());
                if (hmac == null) {
                    skipped++;
                    continue;
                }
                filledThisBatch += blacklistMapper.update(null, new LambdaUpdateWrapper<Blacklist>()
                        .set(Blacklist::getTargetValueHmac, hmac)
                        .eq(Blacklist::getId, row.getId())
                        .isNull(Blacklist::getTargetValueHmac));
            }
            filled += filledThisBatch;
            if (filledThisBatch == 0) {
                break;
            }
        }
        log.info("[PII] blacklist(PHONE) 回填完成：扫描 {} 填充 {} 跳过 {}", scanned, filled, skipped);
        return new BackfillResult(false, scanned, filled, skipped);
    }

    // ---------------------------------------------------------------- 对账

    /** 两表一起对账（users + blacklist PHONE 行）。 */
    public List<ReconcileResult> reconcile() {
        return List.of(reconcileUsers(), reconcileBlacklist());
    }

    /**
     * users 对账：明文重算 HMAC 与库中值逐行比对。
     *
     * <p>keyset 分页（按 id 递增游标）而非 OFFSET——回填/双写并发进行时 OFFSET 会漏行。
     */
    public ReconcileResult reconcileUsers() {
        long total = 0, matched = 0, missing = 0, mismatched = 0;
        long cursor = Long.MIN_VALUE;

        while (true) {
            List<User> rows = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .select(User::getId, User::getPhone, User::getPhoneHmac)
                    .gt(User::getId, cursor)
                    .isNotNull(User::getPhone)
                    .orderByAsc(User::getId)
                    .last("LIMIT " + DEFAULT_BATCH));
            if (rows.isEmpty()) {
                break;
            }
            for (User row : rows) {
                cursor = row.getId();
                String expected = piiCrypto.phoneHmac(row.getPhone());
                if (expected == null) {
                    continue; // 明文空白，本就不该有 hmac，不计入分母
                }
                total++;
                String actual = row.getPhoneHmac();
                if (actual == null) {
                    missing++;
                } else if (expected.equalsIgnoreCase(actual)) {
                    matched++;
                } else {
                    mismatched++;
                    // 只打 id，绝不打明文/hmac（F7 日志不落 PII）
                    log.error("[PII] 对账不一致：users.id={} 的 phone_hmac 与明文重算值不符", row.getId());
                }
            }
            if (rows.size() < DEFAULT_BATCH) {
                break;
            }
        }
        ReconcileResult result = new ReconcileResult("users", total, matched, missing, mismatched);
        log.info("[PII] users 对账：{}", result);
        return result;
    }

    /** blacklist 对账：仅 PHONE 行参与；LICENSE_NO 行恒 NULL 属预期，不计入。 */
    public ReconcileResult reconcileBlacklist() {
        long total = 0, matched = 0, missing = 0, mismatched = 0;
        long cursor = Long.MIN_VALUE;

        while (true) {
            List<Blacklist> rows = blacklistMapper.selectList(new LambdaQueryWrapper<Blacklist>()
                    .select(Blacklist::getId, Blacklist::getTargetValue, Blacklist::getTargetValueHmac)
                    .gt(Blacklist::getId, cursor)
                    .eq(Blacklist::getTargetType, TARGET_TYPE_PHONE)
                    .isNotNull(Blacklist::getTargetValue)
                    .orderByAsc(Blacklist::getId)
                    .last("LIMIT " + DEFAULT_BATCH));
            if (rows.isEmpty()) {
                break;
            }
            for (Blacklist row : rows) {
                cursor = row.getId();
                String expected = piiCrypto.phoneHmac(row.getTargetValue());
                if (expected == null) {
                    continue;
                }
                total++;
                String actual = row.getTargetValueHmac();
                if (actual == null) {
                    missing++;
                } else if (expected.equalsIgnoreCase(actual)) {
                    matched++;
                } else {
                    mismatched++;
                    log.error("[PII] 对账不一致：blacklist.id={} 的 target_value_hmac 与明文重算值不符", row.getId());
                }
            }
            if (rows.size() < DEFAULT_BATCH) {
                break;
            }
        }
        ReconcileResult result = new ReconcileResult("blacklist", total, matched, missing, mismatched);
        log.info("[PII] blacklist 对账：{}", result);
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
