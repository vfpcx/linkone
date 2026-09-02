package com.cangchu.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（P2 Wave2 引入：退驻归档 WholesalerArchiveJob）。
 * 独立配置类而非加在启动类上，便于测试上下文按需排除。
 *
 * <p>全仓 cron 占位一览（12 §2.5：新任务先查此表错峰）：
 * <ul>
 *   <li>0 10 0 * * ?   — DailySnapshotJob（P4 W2：每日计费快照 + 回补 ≤7 日 + 对账哨兵）</li>
 *   <li>0 50 0 * * ?   — BillAutoConfirmJob（P4 W3：下发满 1 日自动转待回款）</li>
 *   <li>0 20 1 1 * ?   — MonthlyBillJob（P4 W3：每月 1 日生成上月账单）</li>
 *   <li>0 5/10 * * * ?  — InboundAutoConfirmJob（72h 超时自动确认）</li>
 *   <li>0 5/5 * * * ?   — FollowupReminderJob（P5-D C3：客户跟进提醒到点站内信，24-p5-c-c3 §5）</li>
 *   <li>0 0 2 * * ?    — BatchRecalcJob（P3b T4：批次 FIFO 推算 + 临期首发通知 D-12）</li>
 *   <li>0 30 2 * * ?   — BatchExpiryMarkJob（P3b T4：到期归零标记「待清理」）</li>
 *   <li>0 40 3 * * ?   — WholesalerArchiveJob（退驻 60 天归档）</li>
 * </ul>
 * 单实例假设（12 §8.4）：当前单副本部署，@Scheduled 无分布式互斥；
 * 多副本上线前须为上述任务统一引入 ShedLock（或等价分布式锁）再扩容。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
