package com.cangchu.tenant.job;

import com.cangchu.tenant.service.WholesalerLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退驻商户归档定时任务（P2 Wave2 R13）。
 *
 * <p>每日凌晨扫描：WITHDRAWN 且 withdrawn_at（审批通过时刻）距今 >=60 天整 → ARCHIVED。
 * 时间比较全部在 SQL 内用数据库时间完成（BND-S3-01：避免应用时钟/时区口径漂移在边界日爆发）。
 * 任务体独立在 {@link WholesalerLifecycleService#archiveExpiredWithdrawn()}，测试可直接驱动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WholesalerArchiveJob {

    private final WholesalerLifecycleService lifecycleService;

    /** 每日 03:40 执行（错开 04:17 索引任务等运维窗口）。 */
    @Scheduled(cron = "0 40 3 * * ?")
    public void archiveExpiredWithdrawn() {
        try {
            int archived = lifecycleService.archiveExpiredWithdrawn();
            log.info("[P2][R13][归档任务] 本次归档 {} 家退驻商户", archived);
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志，次日重试
            log.error("[P2][R13][归档任务] 执行失败", e);
        }
    }
}
