package com.cangchu.billing.job;

import com.cangchu.billing.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账单满 1 日自动确认定时任务（P4 W3，14 §3.3）。
 *
 * <p>每日 00:50（14 §0-B3 实测空闲分钟位）：扫 status=DISPATCHED ∧ dispatch_at 满 1 日
 * （SQL 数据库时间，TIMESTAMPADD 双方言先例）批量同迁移 → PENDING_PAYMENT + confirmed_at=NOW()。
 * 任务体在 {@link BillingService#autoConfirmDispatched}，测试可直驱；批量条件更新天然幂等
 * （重跑 affected=0），与 WA 手工确认同一 CAS 语义（先到先得）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillAutoConfirmJob {

    private final BillingService billingService;

    /** 每日 00:50 执行（单实例假设沿 12 §8.4，多副本前须 ShedLock）。 */
    @Scheduled(cron = "0 50 0 * * ?")
    public void autoConfirm() {
        try {
            int affected = billingService.autoConfirmDispatched();
            if (affected > 0) {
                log.info("[P4][账单Job] 满 1 日自动确认 {} 张 → 待回款", affected);
            }
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志，次日重扫兜底
            log.error("[P4][账单Job] 自动确认执行失败", e);
        }
    }
}
