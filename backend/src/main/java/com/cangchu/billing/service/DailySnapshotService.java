package com.cangchu.billing.service;

import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.billing.vo.MonthlyReplayVo;
import com.cangchu.billing.vo.SnapshotRecalcResultVo;
import com.cangchu.billing.vo.SnapshotRunResultVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日快照服务（P4 W2，14 §1.2：缓存/下钻/对账三用途，非记账真源）。
 *
 * <p>幂等语义：快照=回放的逐日物化（纯函数），同日重跑/recalc 以回放结果删插覆写不重复；
 * 快照与回放不一致时<b>以回放为准</b>并记差异日志（D-P4-2=A）。
 */
public interface DailySnapshotService {

    /**
     * Job 体（00:10 DailySnapshotJob 直调，测试可直驱——BatchService.runDailyRecalcAndNotify 先例）：
     * ① 回补缺口 ≤7 日（窗口内整日无任何快照行才回补）；② 昨日全量快照（重跑覆写）；
     * ③ 全量对账哨兵：回放(至今) vs inventories.qty/pallet_qty，不一致记 ERROR（含 ws/sku 与两侧值）。
     * 单 (tenant, wholesaler) 失败吞异常记日志继续（recalcAll 先例）。
     */
    SnapshotRunResultVo runDailySnapshot();

    /**
     * 快照重放覆写端点体（ST/TA；快照仅缓存语义）：对本租户（或指定商户）重算 [from, to] 快照。
     * 校验：from/to 必填（40003）；from ≤ to ≤ 昨日、跨度 ≤92 天（40001）；商户须属本租户（50230）。
     */
    SnapshotRecalcResultVo recalcSnapshots(Long userId, Long wholesalerId, LocalDate from, LocalDate to);

    /**
     * 按日下钻（ST/TA，13-p4-prd §2.3）：某商户某月逐日 qty/pallet（快照聚合，缺行即 0）
     * + 当日金额（按段现算；无规则段 null）。skuId 可空=全 SKU 聚合。
     * 返回天区间 = [月初, min(月末, 昨日)]；未来月返回空列表。
     */
    List<DailyBreakdownRowVo> dailyBreakdown(Long userId, Long wholesalerId, String month, Long skuId);

    /**
     * 按日下钻内核（P4-L2 收口）：无鉴权的 (tenant, wholesaler) 直取版，行语义与
     * {@link #dailyBreakdown} 完全一致（同一实现）。<b>调用方必须已自行完成鉴权与归属校验</b>
     * （WA 侧账单视角经 BillingService requireWaVisibleBill 后复用；ST/TA 侧走上面的 gate 版）。
     */
    List<DailyBreakdownRowVo> dailyBreakdownForPair(Long tenantId, Long wholesalerId, String month, Long skuId);

    /**
     * 按 SKU 下钻（ST/TA）：某商户某月 SKU×规则段行 + 小计——直接复用回放月汇总
     * （与 W3 出账同源，天然以回放为准）。
     */
    MonthlyReplayVo skuBreakdown(Long userId, Long wholesalerId, String month);

    /**
     * 单 (tenant, wholesaler, 日) 覆写（内部/Job/recalc 共用）：回放该日计费量，删插覆写快照行
     * （双 0 不落行）；既有行与回放不一致记 WARN 差异日志后以回放为准。
     *
     * @return 覆写后该日落行数
     */
    int rebuildSnapshotForDate(Long tenantId, Long wholesalerId, LocalDate date);

    /**
     * 对账哨兵（14 §1.2 用途③）：全量 (tenant, wholesaler)——回放净值 vs inventories 现值，
     * 不一致记 ERROR 日志（流水被绕改/时钟漂移哨兵）。
     *
     * @return 不一致条数（(ws, sku) 粒度）
     */
    int reconcileWithInventory();
}
