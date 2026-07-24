package com.cangchu.document.job;

import com.cangchu.document.service.InboundRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 代建入库 72h 自动确认定时任务（P3 BE-W1，12 §2.5，复刻 WholesalerArchiveJob 先例）。
 *
 * <p>扫描 PENDING_WA_CONFIRM 且 wa_confirm_deadline ≤ 数据库 NOW() 的入库单，逐行 CAS 迁
 * CONFIRMED（auto_accepted=1）并通知 WA。时间比较在 SQL 内用数据库时间（BND-S3-01）。
 * 任务体独立在 {@link InboundRequestService#autoConfirmExpired()}，测试可直接驱动。
 *
 * <p>单实例部署假设（12 §8.4）：现部署单副本，无需分布式锁；多副本前须加 ShedLock
 * （P4 账单 Job 一并评估，本波不做）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboundAutoConfirmJob {

    private final InboundRequestService inboundRequestService;

    /**
     * 每 10 分钟（分钟位 5/15/…/55，12 §2.5 错峰口径）：72h 截止精度 ≤10min 足够；
     * 避开整点，与 03:40 归档、04:17 全量索引不同分钟位；02:00/02:30 分钟位预留给 T4 临期扫描。
     */
    @Scheduled(cron = "0 5/10 * * * ?")
    public void autoConfirmExpired() {
        try {
            int confirmed = inboundRequestService.autoConfirmExpired();
            if (confirmed > 0) {
                log.info("[P3][72h Job] 本次自动确认 {} 单代建入库", confirmed);
            }
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志，下轮重试（先例）
            log.error("[P3][72h Job] 执行失败", e);
        }
    }
}
