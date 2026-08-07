package com.cangchu.billing.job;

import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.vo.BillGenerateResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 月度账单生成定时任务（P4 W3，14 §3.2/§6）。
 *
 * <p>每月 1 日 01:20（14 §0-B3 实测空闲分钟位，与 00:10 快照/00:50 自动确认/02:00 起批次
 * 任务错峰）：逐 (tenant, wholesaler) 出上月账（UTC+8 自然月，05 §12）。任务体在
 * {@link BillingService#generateMonthlyBills}，测试可直驱（BatchRecalcJob 先例）。
 * 幂等键 bill:{t}:{ws}:{yyyyMM}（先查后写 + uk 兜底）：重跑/漏跑次日经
 * POST /api/v1/tenant/st/bills/generate 手动补跑均安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyBillJob {

    private final BillingService billingService;

    /** 账期为 UTC+8 自然月（DocumentNumberServiceImpl ZONE 先例） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每月 1 日 01:20 执行（单实例假设沿 12 §8.4，多副本前须 ShedLock）。 */
    @Scheduled(cron = "0 20 1 1 * ?")
    public void generateMonthly() {
        try {
            YearMonth lastMonth = YearMonth.now(ZONE).minusMonths(1);
            BillGenerateResultVo result = billingService.generateMonthlyBills(lastMonth);
            log.info("[P4][账单Job] {} 月出账：新生成={} 已存在={} 跳过={}",
                    result.getMonth(), result.getGenerated(), result.getExisting(), result.getSkipped());
        } catch (Exception e) {
            // 定时任务不中断调度：吞异常记日志；重试=次日 ST 手动补跑端点（幂等）
            log.error("[P4][账单Job] 月度出账执行失败", e);
        }
    }
}
