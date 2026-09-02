package com.cangchu.document.job;

import com.cangchu.document.service.CustomerFollowupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * C3 客户跟进提醒 Job（24-p5-c-c3 §5，document 域）。
 *
 * <p>每 5 分钟扫 {@code remind_at <= now AND reminded_at IS NULL} 到点提醒：
 * 站内信送达提醒创建人（WE）+ 同事务 CAS 置 reminded_at 防重（并发/重跑不重发）。
 * 系统态无 TenantContext → TenantLine 不注入，按行内 tenant 显式入通知。
 * cron 错峰一览登记于 {@code SchedulingConfig} 注释（单实例假设，多副本前须 ShedLock）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupReminderJob {

    private final CustomerFollowupService customerFollowupService;

    /** 每 5 分钟错峰整点触发（0 5/5）。 */
    @Scheduled(cron = "0 5/5 * * * ?")
    public void fireDueReminders() {
        try {
            int fired = customerFollowupService.fireDueReminders();
            if (fired > 0) {
                log.info("[C3] 客户跟进提醒触发站内信 {} 条", fired);
            }
        } catch (Exception e) {
            log.error("[C3] 客户跟进提醒 Job 执行失败", e);
        }
    }
}
