package com.cangchu.billing.job;

import com.cangchu.billing.service.DailySnapshotService;
import com.cangchu.billing.vo.SnapshotRunResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日计费快照定时任务（P4 W2，14 §1.2/§6）。
 *
 * <p>每日 00:10（14 §0-B3 实测空闲分钟位，与 5/10 分位入库自动确认、02:00/02:30/03:40 错峰）：
 * 对每个存在流水的 (wholesaler, sku) 以统一回放公式落昨日快照（双 0 不落行，缺行即 0），
 * 回补 ≤7 日缺口，末步全量对账哨兵（回放(至今) == inventories.qty/pallet_qty，不一致 ERROR）。
 * 任务体在 {@link DailySnapshotService#runDailySnapshot()}，测试可直驱（BatchRecalcJob 先例）。
 * 快照=回放的逐日物化，纯函数天然幂等，重跑覆写不重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySnapshotJob {

    private final DailySnapshotService dailySnapshotService;

    /** 每日 00:10 执行（单实例假设沿 12 §8.4，多副本前须 ShedLock）。 */
    @Scheduled(cron = "0 10 0 * * ?")
    public void snapshotDaily() {
        try {
            SnapshotRunResultVo result = dailySnapshotService.runDailySnapshot();
            log.info("[P4][快照Job] 完成：快照日={} 组合={} 落行={} 回补={} 对账不一致={}",
                    result.getSnapshotDate(), result.getPairCount(), result.getRowCount(),
                    result.getBackfilledDates(), result.getMismatchCount());
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志；次日 Job 回补窗口自动补算漏跑日
            log.error("[P4][快照Job] 执行失败", e);
        }
    }
}
