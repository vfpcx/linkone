package com.cangchu.inventory.job;

import com.cangchu.inventory.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批次到期归零标记定时任务（P3b T4-W2，13 §3.3）。
 *
 * <p>每日 02:30（02:00 推算之后半小时，先算剩余再判归零）：扫
 * {@code expiry_date < CURDATE() ∧ status ∈ (IN_STOCK, EXPIRING) ∧ remaining_qty > 0}
 * → 标 PENDING_CLEARANCE 并通知库管发起清库（BATCH_EXPIRED）。SQL 内比数据库时间
 * （BND-S3-01：当日到期不标、昨日标）；推算剩余=0 者由 02:00 落 SOLD_OUT 不清库。
 * 任务体在 {@link BatchService#markExpiredBatches()}，测试可直接驱动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchExpiryMarkJob {

    private final BatchService batchService;

    /** 每日 02:30 执行（单实例假设沿 12 §8.4，多副本前须 ShedLock）。 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void markExpired() {
        try {
            int marked = batchService.markExpiredBatches();
            log.info("[P3b][T4][归零标记任务] 本次标记 {} 个批次为待清理", marked);
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志，次日重试
            log.error("[P3b][T4][归零标记任务] 执行失败", e);
        }
    }
}
