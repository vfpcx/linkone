package com.cangchu.common.pii;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量回填触发器：<b>默认不装配</b>，须显式 {@code cangchu.pii.backfill-on-startup=true} 才生效。
 *
 * <p>为什么用「一次性开关 + 重启」而不是定时 Job 或 OPS 端点：
 * <ul>
 *   <li>阶段 0 的回填是<b>一次性</b>动作，跑完即完；常驻 Job 会年复一年空扫；</li>
 *   <li>开放 OPS 端点等于给全量 PII 扫描加一个线上入口，是净增攻击面，收益不抵风险；</li>
 *   <li>回填本身幂等（CAS），误开一次重启只是空跑，无副作用。</li>
 * </ul>
 *
 * <p>运维口径：改配置 → 重启一次 → 看日志确认对账 clean → 把开关拨回 false。
 * 回填失败不拖垮启动（catch 兜住并打 error）——它不是服务可用性的前置条件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cangchu.pii", name = "backfill-on-startup", havingValue = "true")
public class PiiBackfillRunner implements ApplicationRunner {

    private final PiiBackfillService backfillService;
    private final PiiProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[PII] backfill-on-startup=true，开始存量回填（批大小 {}）", properties.getBackfillBatchSize());
        try {
            int batch = properties.getBackfillBatchSize();
            PiiBackfillService.BackfillResult users = backfillService.backfillUsers(batch);
            PiiBackfillService.BackfillResult blacklist = backfillService.backfillBlacklist(batch);
            // V30 补做的三表（定价链 / 短信校验 / 询价），与 V27 两表同一次重启一起跑完
            PiiBackfillService.BackfillResult customerPrices = backfillService.backfillCustomerPrices(batch);
            PiiBackfillService.BackfillResult smsCodes = backfillService.backfillSmsCodes(batch);
            PiiBackfillService.BackfillResult inquiries = backfillService.backfillInquiryRequests(batch);
            log.info("[PII] 回填结束 users={} blacklist={} customer_prices={} sms_codes={} inquiry_requests={}",
                    users, blacklist, customerPrices, smsCodes, inquiries);

            List<PiiBackfillService.ReconcileResult> reconcile = backfillService.reconcile();
            reconcile.forEach(r -> {
                if (r.clean()) {
                    log.info("[PII] 对账通过 {}", r);
                } else {
                    log.error("[PII] 对账未通过，阶段 1 影子灰度不得放行：{}", r);
                }
            });
        } catch (Exception e) {
            log.error("[PII] 存量回填异常终止——服务照常启动，请排查后重跑（回填幂等，可直接重来）", e);
        }
    }
}
