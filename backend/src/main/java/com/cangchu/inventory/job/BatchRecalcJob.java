package com.cangchu.inventory.job;

import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.vo.BatchRecalcResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批次 FIFO 推算 + 临期扫描定时任务（P3b T4-W2，13 §3.2/§3.3）。
 *
 * <p>每日 02:00（A5 实测分钟位空闲，与 5/10 分位入库自动确认、03:40 归档错开）：
 * {@code BatchService.recalcAll()} 全量重算各租户批次推算剩余（纯读流水幂等）→
 * 逐「新进入 EXPIRING」批次发 BATCH_EXPIRING 站内信（库管+商户管理员各一条，D-12：
 * expiring_notified_at 锚点去重，状态不变不重发）。任务体在
 * {@link BatchService#runDailyRecalcAndNotify()}，测试可直接驱动（WholesalerArchiveJob 先例）。
 * 仅扫 batch_enabled=1 租户；单租户/单批次失败吞异常记日志不阻断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchRecalcJob {

    private final BatchService batchService;

    /** 每日 02:00 执行（单实例假设沿 12 §8.4，多副本前须 ShedLock）。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void recalcAndNotify() {
        try {
            List<BatchRecalcResultVo> results = batchService.runDailyRecalcAndNotify();
            int notified = results.stream().mapToInt(r -> r.getNewlyExpiringBatchIds().size()).sum();
            log.info("[P3b][T4][推算任务] 租户 {} 个，新进入临期批次 {} 个", results.size(), notified);
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志，次日重试（未落锚点的首发通知自动补发）
            log.error("[P3b][T4][推算任务] 执行失败", e);
        }
    }
}
