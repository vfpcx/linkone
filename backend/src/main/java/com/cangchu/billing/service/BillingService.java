package com.cangchu.billing.service;

import com.cangchu.billing.dto.BillAdjustDto;
import com.cangchu.billing.dto.BillDisputeResolveDto;
import com.cangchu.billing.dto.BillDisputeSubmitDto;
import com.cangchu.billing.dto.BillReverseItemDto;
import com.cangchu.billing.dto.PaymentRegisterDto;
import com.cangchu.billing.dto.PaymentReverseDto;
import com.cangchu.billing.entity.Bill;
import com.cangchu.billing.vo.BillsOverviewVo;
import com.cangchu.billing.vo.BillDetailVo;
import com.cangchu.billing.vo.BillDisputeVo;
import com.cangchu.billing.vo.BillGenerateResultVo;
import com.cangchu.billing.vo.BillListVo;
import com.cangchu.billing.vo.BillVo;
import com.cangchu.billing.vo.DailyBreakdownRowVo;

import java.time.YearMonth;
import java.util.List;

/**
 * 账单生命周期服务（P4 W3，14 §3）。
 *
 * <p>生成（§3.2）：MonthlyBillJob 每月 1 日 01:20 出上月账；单 (t,ws) 单事务、
 * 幂等键 bill:{t}:{ws}:{yyyyMM} 先查后写 + uk 兜底；ST 可手动补跑（50380 无规则）。
 * 操作集（§3.3）：全部经 DocKind.BILL CAS；DISPUTED 位冻结全部写操作（50381）。
 * R13/R14 联动（§3.5）：assertAllSettled(50323) / markDisputedByWholesaler 供 tenant 域
 * WholesalerLifecycleServiceImpl 调用（O-5/forceOffline TODO 兑现）。
 */
public interface BillingService {

    // ==================== 生成（§3.2） ====================

    /** Job 体：全租户出 month 月账（无规则租户跳过记 WARN；单 (t,ws) 失败吞异常继续，recalcAll 先例）。 */
    BillGenerateResultVo generateMonthlyBills(YearMonth month);

    /** 手动补跑（requireStOrTa，幂等）：本租户出 month 月账；无生效规则抛 50380；月份须已结束。 */
    BillGenerateResultVo generateForTenant(Long userId, String month);

    /**
     * 单 (t,ws) 单事务生成（Job/手动共用核心；自代理保证事务边界）。
     *
     * @return 新生成的账单；已存在（幂等）或不满足出账对象条件返回 null
     */
    Bill generateForPair(Long tenantId, Long wholesalerId, YearMonth month);

    /** 00:50 Job 体：DISPATCHED ∧ dispatch_at 满 1 日（SQL 数据库时间）批量转 PENDING_PAYMENT。 */
    int autoConfirmDispatched();

    // ==================== ST 查询（§6.2） ====================

    BillListVo listBills(Long userId, String month, Long wholesalerId, String status, int page, int size);

    BillDetailVo getBill(Long userId, Long billId);

    /** 按日下钻（读 daily_snapshots 聚合，当段单价现算；不暴露实时库存 05 §5.4）。 */
    List<DailyBreakdownRowVo> dailyBreakdown(Long userId, Long billId);

    // ==================== ST 操作（§3.3） ====================

    /** 调整（US-ST-02）：仅 DRAFT（50371）；DISCOUNT/RELIEF 存负值；调整后 total<0 → 40204。 */
    BillDetailVo adjust(Long userId, Long billId, BillAdjustDto dto);

    /** 冲销（R10）：仅 DRAFT；仅 STORAGE/ADJUSTMENT 且未被冲销过（50383）；不删原条目。 */
    BillDetailVo reverseItem(Long userId, Long billId, BillReverseItemDto dto);

    /** 下发（US-ST-03）：DRAFT→DISPATCHED + dispatch_at；通知商户管理员。 */
    BillVo dispatch(Long userId, Long billId);

    /** 撤回（R11）：DISPATCHED ∧ paid=0 ∧ 未确认 → DRAFT（否则 50372）；通知商户管理员。 */
    BillVo withdraw(Long userId, Long billId);

    /** 回款登记（US-ST-04）：仅 PENDING_PAYMENT/PARTIAL_PAID；不允许超收 50373；多次部分回款。 */
    BillDetailVo registerPayment(Long userId, Long billId, PaymentRegisterDto dto);

    /** 回款冲销（R12）：EFFECTIVE（50375）+ reason + confirmed；paid 回减 + 状态回退。 */
    BillDetailVo reversePayment(Long userId, Long paymentId, PaymentReverseDto dto);

    /** 申诉队列（PENDING 升序先到先处理）。 */
    List<BillDisputeVo> listDisputes(Long userId, String status);

    /** 申诉处理：仅 PENDING（50376）；RESOLVED/REJECTED + resolution 留痕；通知商户管理员。 */
    BillDisputeVo resolveDispute(Long userId, Long disputeId, BillDisputeResolveDto dto);

    // ==================== TA 总览（§6.3 W5 补口） ====================

    /**
     * TA 账单总览（US-TA-08，requireTa——ST 42001/WE 42004）：按月（可缺省=全部）
     * 应收/已收/未收/账单数/状态分布 + 逐商户汇总行。
     */
    BillsOverviewVo billsOverview(Long userId, String month);

    // ==================== WA 侧（§6.3；WE 整域拒绝 42004） ====================

    /** 收到的账单（仅已下发过的可见；DRAFT 按不存在 50370 不泄漏）。 */
    BillListVo listWaBills(Long userId, String month, String status);

    BillDetailVo getWaBill(Long userId, Long billId);

    /**
     * WA 按日下钻（P4-L2 收口）：账单视角只读 daily_snapshots 聚合——行语义与 ST 侧
     * {@link #dailyBreakdown} 完全一致（同一快照内核，不暴露实时库存 05 §5.4）。
     * 可见性同 getWaBill：本人商户 ∧ 已下发过（DRAFT/未下发/跨商户按不存在 50370）；WE 42004。
     */
    List<DailyBreakdownRowVo> waDailyBreakdown(Long userId, Long billId);

    /** 对账确认（D-P4-6）：DISPATCHED→PENDING_PAYMENT + confirmed_at。 */
    BillVo confirmByWa(Long userId, Long billId);

    /** 申诉（US-WA-08）：下发后 7 天窗（50378）；同账单至多一张 PENDING（50382）；不冻结账单。 */
    BillDisputeVo disputeByWa(Long userId, Long billId, BillDisputeSubmitDto dto);

    // ==================== R13/R14 联动（§3.5） ====================

    /** R13：存在 status != PAID 账单（含 DISPUTED）→ 抛 50323（发起与审批双检）。 */
    void assertAllSettled(Long wholesalerId);

    /** R13 precheck 真值：未结清账单数（precheck billing {cleared, count}）。 */
    long countUnsettled(Long wholesalerId);

    /**
     * R14：该商户 DISPATCHED/PENDING_PAYMENT/PARTIAL_PAID 账单批量 CAS→DISPUTED
     * （与 forceOffline 同事务），通知本仓库结算员。
     *
     * @return 转入争议中的账单数
     */
    int markDisputedByWholesaler(Long wholesalerId);
}
