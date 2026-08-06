package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.ForceOfflineDto;
import com.cangchu.tenant.dto.WithdrawApplyDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.vo.WithdrawApplicationVo;

import java.util.Map;

/**
 * 批发商生命周期服务（P2 入驻 Wave2：R13 退驻 + R14 强制下架）。
 *
 * <p>状态机统一经 {@link WholesalerStateMachine} 收口；所有 wholesalers.status 翻转
 * 用数据库条件更新（CAS，WHERE status=期望态）抢占，不依赖内存判断。
 *
 * <p>时间口径（BND-S3-01）：60 天恢复窗口与归档判定统一以 <b>审批通过时刻</b>
 * （withdraw 申请单 audited_at，快照至 wholesalers.withdrawn_at）为起点，
 * 且一律用<b>数据库时间</b>比较（SQL TIMESTAMPADD(DAY,-60,NOW())），边界取 >=60 天整归档、
 * &lt;60 天可恢复——两口径互补无缝隙，不用应用时钟（防时区/口径漂移只在边界日爆发）。
 */
public interface WholesalerLifecycleService {

    /**
     * R13：WA 发起退驻申请。
     * 前置校验：商户 ACTIVE（WITHDRAWN→50202 / 其它→50318）；库存清零（否则 50312）；
     * 无未结单据（询价/出库非终态，否则 50314）；账单结清（P4 W3 兑现 O-5：存在 status != PAID 账单含争议中 → 50323，发起/审批双检）；
     * 无未决退驻申请（否则 50316）。
     *
     * @return { applicationId, wholesalerId, status }
     */
    Map<String, Object> applyWithdraw(Long userId, WithdrawApplyDto dto);

    /**
     * R13：退驻前置自查（只读，供前端三态清单展示；与 {@link #applyWithdraw} 提交校验同一份逻辑）。
     *
     * @return { wholesalerId, status, stockCleared: boolean,
     *           openDocs: { cleared: boolean, count: long },
     *           billing: { cleared: boolean, count: long } —— P4 W3 真值（O-5 兑现） }
     */
    Map<String, Object> precheckWithdraw(Long userId);

    /**
     * R13：WA 撤回本人 PENDING 退驻申请（CAS：UPDATE WHERE status='PENDING'，
     * 已被审批/已撤回 → 50315）。撤回后可重新发起。
     *
     * @return { applicationId, status: CANCELLED }
     */
    Map<String, Object> cancelWithdraw(Long userId);

    /**
     * R13：登录 WA 查询本人最近一次退驻申请（含 status/auditRemark/auditedAt，
     * 前端 60 天倒计时用）。仅按 applicant_user_id=登录用户过滤，不泄漏他人申请。
     *
     * @return 最近一次申请视图；从未申请返回 null
     */
    WithdrawApplicationVo myWithdraw(Long userId);

    /**
     * R13：TA 分页退驻申请列表（TenantLine 兜底 + 显式 tenant_id 双保险）。
     *
     * @return { records: List<WithdrawApplicationVo>, total, page, size }
     */
    Map<String, Object> pageWithdrawForTenant(Long tenantId, Long taUserId, int page, int size, String status);

    /**
     * R13：TA 审批退驻（CAS：UPDATE WHERE status='PENDING'，并发仅一方成功，败者 50315）。
     * 通过后同事务副作用链：wholesaler ACTIVE→WITHDRAWN（CAS）+ 全部 SKU 下架 +
     * 店铺页隐藏（storefront 仅列 ACTIVE，状态翻转即隐藏）+ CustomerPrice 全部失效（含 Redis）+
     * 提交后踢 token（该商户 WA 与全部 WE 一并踢，WDR-S1-02）。
     *
     * @return { applicationId, status, wholesalerId }
     */
    Map<String, Object> auditWithdraw(Long tenantId, Long taUserId, Long applicationId,
                                      WholesalerApplicationAuditDto dto);

    /**
     * R13：WA 60 天内恢复（WITHDRAWN→ACTIVE，CAS 附带数据库时间窗口条件）。
     * SKU 保持下架需手动重新上架；已失效专属价不复活。
     * 已归档/超窗 → 50317；非退驻态 → 50318。
     *
     * @return { wholesalerId, status }
     */
    Map<String, Object> restoreWithdraw(Long userId);

    /**
     * R14：TA 单方即时强制下架（reason 必填；ACTIVE→OFFLINE，CAS）。
     * 副作用：店铺隐藏（同上）+ 踢 token（WA+WE）；新业务拒绝老业务放行的分界在 document 域
     * 校验点落地（新询价 50313、未确认询价不可确认；已确认/已出库允许走完）。
     * 不可原地恢复：无任何端点提供 OFFLINE→ACTIVE。未结账单同事务批量转"争议中"（P4 W3 兑现，14 §3.5-2）。
     *
     * @return { wholesalerId, status }
     */
    Map<String, Object> forceOffline(Long tenantId, Long taUserId, Long wholesalerId, ForceOfflineDto dto);

    /**
     * 归档定时任务体：退驻通过（withdrawn_at）距今 >=60 天整（数据库时间比较）→ ARCHIVED。
     * 独立方法便于测试直接驱动（59/60/61 天边界用例）。
     *
     * @return 本次归档的商户行数
     */
    int archiveExpiredWithdrawn();
}
