package com.cangchu.tenant.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.service.InquiryService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.service.SkuService;
import com.cangchu.tenant.dto.ForceOfflineDto;
import com.cangchu.tenant.dto.WithdrawApplyDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.entity.WholesalerWithdrawApplication;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.mapper.WholesalerWithdrawApplicationMapper;
import com.cangchu.tenant.service.WholesalerLifecycleService;
import com.cangchu.tenant.service.WholesalerStateMachine;
import com.cangchu.tenant.vo.WithdrawApplicationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批发商生命周期服务实现（P2 入驻 Wave2：R13 退驻 + R14 强制下架）。
 *
 * <p>安全自检（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：WA 侧以 {@code authService.listActiveWholesalerIds(userId,"WA")} 推导本人商户
 *       （不信任客户端传 wholesalerId）；TA 侧 {@code hasRole(userId,"TA",tenantId)} 显式校验；
 *       跨租户申请/商户按「不可见即不存在」处理（50315/50230），不泄漏存在性。</li>
 *   <li>D-05 状态机：全部经 {@link WholesalerStateMachine#assertTransition} 收口 +
 *       数据库条件更新（CAS）双保险；不可达转移（已退驻→已下架、已下架→正常）无路径可走。</li>
 *   <li>并发（CON）：退驻审批 CAS（WHERE status='PENDING'）；主体状态翻转 CAS（WHERE status=期望态）；
 *       恢复窗口条件直接写进 UPDATE WHERE（数据库时间），不做读-判-写。</li>
 *   <li>副作用链跨域出口：SKU 下架经 SkuService、专属价失效经 PricingService（含 Redis 提交后失效）、
 *       库存/单据校验经 InventoryService/InquiryService——不直连他域 Mapper（G-S1/G-S2）。</li>
 *   <li>踢 token（WDR-S1-02）：注册在<b>事务提交后</b>执行（回滚不误踢）；经
 *       {@code authService.listActiveUserIdsOfWholesaler}（不限角色）把该商户 WA 与全部 WE 一并踢出。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WholesalerLifecycleServiceImpl implements WholesalerLifecycleService {

    private final WholesalerMapper wholesalerMapper;
    private final WholesalerWithdrawApplicationMapper withdrawMapper;
    private final AuthService authService;
    private final InventoryService inventoryService;
    private final InquiryService inquiryService;
    // P3 BE-W2（12 §8.2）：R13「未结单据」扩展——入库确认链 + 仲裁 PENDING（争议中/客诉中不能退驻）
    private final com.cangchu.document.service.InboundRequestService inboundRequestService;
    private final com.cangchu.document.service.ArbitrationService arbitrationService;
    // P3b T3-W1（13 §7.1）：退货 PENDING_ACCEPT/ACCEPTED 计入未结（阻退驻）
    private final com.cangchu.document.service.ReturnRequestService returnRequestService;
    private final SkuService skuService;
    private final PricingService pricingService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 退驻恢复/归档窗口（天）。>=60 天整归档；<60 天可恢复（互补无缝隙）。 */
    private static final int WITHDRAW_WINDOW_DAYS = 60;

    /** 数据库时间窗口条件（MySQL 与 H2-MySQL 模式均支持 TIMESTAMPADD）。 */
    private static final String SQL_WITHIN_WINDOW =
            "withdrawn_at > TIMESTAMPADD(DAY, -" + WITHDRAW_WINDOW_DAYS + ", NOW())";
    private static final String SQL_BEYOND_WINDOW =
            "withdrawn_at <= TIMESTAMPADD(DAY, -" + WITHDRAW_WINDOW_DAYS + ", NOW())";

    // ==================== R13 退驻 ====================

    @Override
    @Transactional
    public Map<String, Object> applyWithdraw(Long userId, WithdrawApplyDto dto) {
        Wholesaler wholesaler = requireOwnWholesaler(userId);

        // D-05 状态机收口：只有 ACTIVE 可发起退驻（WITHDRAWN→50202；OFFLINE/ARCHIVED→50318）
        WholesalerStateMachine.assertTransition(wholesaler.getStatus(), WholesalerStateMachine.WITHDRAWN);

        // 防重：已有 PENDING 退驻申请 → 50316
        long pending = withdrawMapper.selectCount(new LambdaQueryWrapper<WholesalerWithdrawApplication>()
                .eq(WholesalerWithdrawApplication::getWholesalerId, wholesaler.getId())
                .eq(WholesalerWithdrawApplication::getStatus, WholesalerWithdrawApplication.STATUS_PENDING));
        if (pending > 0) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_PENDING);
        }

        // 前置校验（PRD 05 §4.2），审批通过时同口径复检
        assertWithdrawPreconditions(wholesaler.getId());

        WholesalerWithdrawApplication app = new WholesalerWithdrawApplication();
        app.setId(snowflakeIdUtil.nextId());
        app.setTenantId(wholesaler.getTenantId());
        app.setWholesalerId(wholesaler.getId());
        app.setApplicantUserId(userId);
        app.setReason(dto != null ? dto.getReason() : null);
        app.setStatus(WholesalerWithdrawApplication.STATUS_PENDING);
        withdrawMapper.insert(app);

        // 主体记录发起时刻（申请留痕；60 天窗口起点是审批通过时刻 withdrawn_at，非此列）
        wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, wholesaler.getId())
                .set(Wholesaler::getWithdrawApplyAt, LocalDateTime.now())
                .set(Wholesaler::getUpdatedAt, LocalDateTime.now()));

        log.info("[P2][R13] WA {} 发起退驻申请 {}（商户 {}，租户 {}）",
                userId, app.getId(), wholesaler.getId(), wholesaler.getTenantId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId().toString());
        result.put("wholesalerId", wholesaler.getId().toString());
        result.put("status", app.getStatus());
        return result;
    }

    @Override
    public Map<String, Object> precheckWithdraw(Long userId) {
        Wholesaler wholesaler = requireOwnWholesaler(userId);
        Precheck pc = precheck(wholesaler.getId());

        // 与 applyWithdraw/审批复检共用 precheck() 同一份判定逻辑（只读出口，不重复实现）
        Map<String, Object> openDocs = new LinkedHashMap<>();
        openDocs.put("cleared", pc.openDocsCount() == 0);
        openDocs.put("count", pc.openDocsCount());

        Map<String, Object> billing = new LinkedHashMap<>();
        // TODO(P4 billing，决策 O-5)：BillingService 落地后返回真实 cleared；恒 null=前端灰态占位
        billing.put("cleared", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wholesalerId", wholesaler.getId().toString());
        result.put("status", wholesaler.getStatus());
        result.put("stockCleared", pc.stockCleared());
        result.put("openDocs", openDocs);
        result.put("billing", billing);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> cancelWithdraw(Long userId) {
        Wholesaler wholesaler = requireOwnWholesaler(userId);

        // 本人商户的 PENDING 申请（撤回目标）；不存在 → 50315
        WholesalerWithdrawApplication app = withdrawMapper.selectOne(
                new LambdaQueryWrapper<WholesalerWithdrawApplication>()
                        .eq(WholesalerWithdrawApplication::getWholesalerId, wholesaler.getId())
                        .eq(WholesalerWithdrawApplication::getStatus,
                                WholesalerWithdrawApplication.STATUS_PENDING)
                        .last("LIMIT 1"));
        if (app == null) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_NOT_AUDITABLE, "没有可撤回的退驻申请");
        }

        // CAS：仅 PENDING 可撤，与 TA 并发审批恰一方成功（败者 50315）
        LocalDateTime now = LocalDateTime.now();
        int affected = withdrawMapper.update(null,
                new LambdaUpdateWrapper<WholesalerWithdrawApplication>()
                        .eq(WholesalerWithdrawApplication::getId, app.getId())
                        .eq(WholesalerWithdrawApplication::getStatus,
                                WholesalerWithdrawApplication.STATUS_PENDING)
                        .set(WholesalerWithdrawApplication::getStatus,
                                WholesalerWithdrawApplication.STATUS_CANCELLED)
                        .set(WholesalerWithdrawApplication::getUpdatedAt, now));
        if (affected == 0) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_NOT_AUDITABLE,
                    "退驻申请已被审核，无法撤回");
        }

        // 清发起时刻留痕（主体保持 ACTIVE，可重新发起）
        wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, wholesaler.getId())
                .set(Wholesaler::getWithdrawApplyAt, null)
                .set(Wholesaler::getUpdatedAt, now));

        log.info("[P2][R13] WA {} 撤回退驻申请 {}（商户 {}）", userId, app.getId(), wholesaler.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId().toString());
        result.put("status", WholesalerWithdrawApplication.STATUS_CANCELLED);
        return result;
    }

    @Override
    public WithdrawApplicationVo myWithdraw(Long userId) {
        // 仅本人（applicant_user_id=登录用户），最近一次；不接受任何客户端过滤参数 → 不泄漏他人申请
        WholesalerWithdrawApplication app = withdrawMapper.selectOne(
                new LambdaQueryWrapper<WholesalerWithdrawApplication>()
                        .eq(WholesalerWithdrawApplication::getApplicantUserId, userId)
                        .orderByDesc(WholesalerWithdrawApplication::getCreatedAt)
                        .orderByDesc(WholesalerWithdrawApplication::getId)
                        .last("LIMIT 1"));
        return app == null ? null : toVo(app, null);
    }

    @Override
    public Map<String, Object> pageWithdrawForTenant(Long tenantId, Long taUserId,
                                                     int page, int size, String status) {
        requireTaRole(tenantId, taUserId);

        Page<WholesalerWithdrawApplication> p = withdrawMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<WholesalerWithdrawApplication>()
                        // 显式 eq(tenant_id)（与 TenantLine 兜底叠加，双保险）
                        .eq(WholesalerWithdrawApplication::getTenantId, tenantId)
                        .eq(status != null && !status.isBlank(),
                                WholesalerWithdrawApplication::getStatus, status)
                        .orderByDesc(WholesalerWithdrawApplication::getCreatedAt));

        List<WithdrawApplicationVo> records = p.getRecords().stream()
                .map(a -> toVo(a, resolveWholesalerName(a.getWholesalerId())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", p.getTotal());
        result.put("page", p.getCurrent());
        result.put("size", p.getSize());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> auditWithdraw(Long tenantId, Long taUserId, Long applicationId,
                                             WholesalerApplicationAuditDto dto) {
        requireTaRole(tenantId, taUserId);

        // 跨租户/不存在统一按不存在处理（不泄漏存在性）
        WholesalerWithdrawApplication app = withdrawMapper.selectById(applicationId);
        if (app == null || !tenantId.equals(app.getTenantId())) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_NOT_AUDITABLE);
        }
        if (!WholesalerWithdrawApplication.STATUS_PENDING.equals(app.getStatus())) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_NOT_AUDITABLE,
                    "退驻申请当前状态为 " + app.getStatus() + "，不可重复审核");
        }

        String action = dto.getAction();
        if (!WholesalerWithdrawApplication.STATUS_APPROVED.equals(action)
                && !WholesalerWithdrawApplication.STATUS_REJECTED.equals(action)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审核结果仅支持 APPROVED/REJECTED");
        }
        if (WholesalerWithdrawApplication.STATUS_REJECTED.equals(action)
                && (dto.getRemark() == null || dto.getRemark().isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "驳回必须填写理由");
        }

        // CON 并发防护（CAS，仿 Wave1）：WHERE status='PENDING' 抢占，败者 50315
        LocalDateTime auditedAt = LocalDateTime.now();
        int affected = withdrawMapper.update(null,
                new LambdaUpdateWrapper<WholesalerWithdrawApplication>()
                        .eq(WholesalerWithdrawApplication::getId, app.getId())
                        .eq(WholesalerWithdrawApplication::getTenantId, tenantId)
                        .eq(WholesalerWithdrawApplication::getStatus,
                                WholesalerWithdrawApplication.STATUS_PENDING)
                        .set(WholesalerWithdrawApplication::getStatus, action)
                        .set(WholesalerWithdrawApplication::getAuditUserId, taUserId)
                        .set(WholesalerWithdrawApplication::getAuditedAt, auditedAt)
                        .set(WholesalerWithdrawApplication::getAuditRemark, dto.getRemark())
                        .set(WholesalerWithdrawApplication::getUpdatedAt, auditedAt));
        if (affected == 0) {
            throw new BizException(ErrorCode.WITHDRAW_APPLICATION_NOT_AUDITABLE,
                    "退驻申请已被并发审核，请刷新后重试");
        }

        if (WholesalerWithdrawApplication.STATUS_APPROVED.equals(action)) {
            approveSideEffects(app, auditedAt);
        } else {
            // 驳回：清发起时刻留痕（主体保持 ACTIVE，可再次发起）
            wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                    .eq(Wholesaler::getId, app.getWholesalerId())
                    .set(Wholesaler::getWithdrawApplyAt, null)
                    .set(Wholesaler::getUpdatedAt, auditedAt));
        }

        log.info("[P2][R13] TA {} {} 退驻申请 {}（商户 {}，租户 {}）",
                taUserId, action, applicationId, app.getWholesalerId(), tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId().toString());
        result.put("status", action);
        result.put("wholesalerId", app.getWholesalerId().toString());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> restoreWithdraw(Long userId) {
        Wholesaler wholesaler = requireOwnWholesaler(userId);

        // 已归档/非退驻态先给语义错误码（状态机：仅 WITHDRAWN→ACTIVE 可达）
        if (WholesalerStateMachine.ARCHIVED.equals(wholesaler.getStatus())) {
            throw new BizException(ErrorCode.WITHDRAW_RESTORE_EXPIRED, "商户已归档，请重新入驻");
        }
        WholesalerStateMachine.assertTransition(wholesaler.getStatus(), WholesalerStateMachine.ACTIVE);

        // CAS + 数据库时间窗口：<60 天整才可恢复（与归档 >=60 天互补无缝隙，BND-S3-01）
        int affected = wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, wholesaler.getId())
                .eq(Wholesaler::getStatus, WholesalerStateMachine.WITHDRAWN)
                .apply(SQL_WITHIN_WINDOW)
                .set(Wholesaler::getStatus, WholesalerStateMachine.ACTIVE)
                .set(Wholesaler::getWithdrawnAt, null)
                .set(Wholesaler::getWithdrawApplyAt, null)
                .set(Wholesaler::getUpdatedAt, LocalDateTime.now()));
        if (affected == 0) {
            // 状态仍 WITHDRAWN 但窗口条件不满足 → 超 60 天（归档任务未跑到也一样拒绝）
            throw new BizException(ErrorCode.WITHDRAW_RESTORE_EXPIRED);
        }

        // 恢复语义（PRD 05 §4.2）：SKU 保持下架需手动重新上架；已失效专属价不复活（不做任何回滚）。
        log.info("[P2][R13] WA {} 恢复商户 {} 为 ACTIVE（60 天窗口内；SKU 保持下架，专属价不复活）",
                userId, wholesaler.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wholesalerId", wholesaler.getId().toString());
        result.put("status", WholesalerStateMachine.ACTIVE);
        return result;
    }

    // ==================== R14 强制下架 ====================

    @Override
    @Transactional
    public Map<String, Object> forceOffline(Long tenantId, Long taUserId, Long wholesalerId,
                                            ForceOfflineDto dto) {
        requireTaRole(tenantId, taUserId);

        Wholesaler wholesaler = wholesalerMapper.selectById(wholesalerId);
        if (wholesaler == null || !tenantId.equals(wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }

        // D-05 状态机收口：仅 ACTIVE→OFFLINE 可达；已退驻→已下架 不可达（50202）
        WholesalerStateMachine.assertTransition(wholesaler.getStatus(), WholesalerStateMachine.OFFLINE);

        LocalDateTime now = LocalDateTime.now();
        int affected = wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, wholesalerId)
                .eq(Wholesaler::getTenantId, tenantId)
                .eq(Wholesaler::getStatus, WholesalerStateMachine.ACTIVE)
                .set(Wholesaler::getStatus, WholesalerStateMachine.OFFLINE)
                .set(Wholesaler::getOfflineAt, now)
                .set(Wholesaler::getOfflineReason, dto.getReason().trim())
                .set(Wholesaler::getUpdatedAt, now));
        if (affected == 0) {
            throw new BizException(ErrorCode.WHOLESALER_STATE_TRANSITION_INVALID,
                    "商户状态已变更，请刷新后重试");
        }

        // 副作用：店铺隐藏由 storefront 仅列 ACTIVE 商户实现（状态翻转即隐藏）；
        // 新业务拒绝/老业务放行分界在 document 域校验点（submitByRt/confirmByWa → 50313）。
        // TODO(P4 billing，任务占位)：该商户未结账单转「争议中」状态（PRD 04 §1.8 已下架→争议中），
        //   待 BillingService 落地后在此调用 billingService.markDisputedByWholesaler(wholesalerId)。

        // 踢 token：WA 与全部 WE 一并踢（WDR-S1-02），提交后执行（回滚不误踢）
        kickWholesalerUsersAfterCommit(wholesalerId, "R14 强制下架");

        log.info("[P2][R14] TA {} 强制下架商户 {}（租户 {}），原因：{}",
                taUserId, wholesalerId, tenantId, dto.getReason());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wholesalerId", wholesalerId.toString());
        result.put("status", WholesalerStateMachine.OFFLINE);
        return result;
    }

    // ==================== 归档定时任务体 ====================

    @Override
    @Transactional
    public int archiveExpiredWithdrawn() {
        // BND-S3-01 时间口径：以 withdrawn_at（审批通过时刻）为起点、数据库时间比较、>=60 天整归档。
        // 定时任务无 TenantContext → TenantLine 不注入 → 全平台扫描（预期行为）。
        int affected = wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getStatus, WholesalerStateMachine.WITHDRAWN)
                .isNotNull(Wholesaler::getWithdrawnAt)
                .apply(SQL_BEYOND_WINDOW)
                .set(Wholesaler::getStatus, WholesalerStateMachine.ARCHIVED)
                // 归档时刻同样取数据库时间（口径统一）
                .setSql("archived_at = NOW()")
                .set(Wholesaler::getUpdatedAt, LocalDateTime.now()));
        if (affected > 0) {
            log.info("[P2][R13][归档] 退驻超 {} 天商户归档 {} 家", WITHDRAW_WINDOW_DAYS, affected);
        }
        return affected;
    }

    // ==================== 私有方法 ====================

    /** R13 前置自查结果（applyWithdraw 提交校验 / precheck 只读出口共用同一份判定逻辑）。 */
    private record Precheck(boolean stockCleared, long openDocsCount) {
    }

    private Precheck precheck(Long wholesalerId) {
        // 库存清零（inventory 域现有能力：listInStockSkusFor 只返回 qty>0 行）
        boolean stockCleared = inventoryService.listInStockSkusFor(wholesalerId).isEmpty();
        // 未结单据（document 域出口，P3 BE-W2 扩展 12 §8.2）：
        // 询价 PENDING/CONFIRMED ∪ 出库 PENDING_ACCEPT/PRINTED/COMPLAINED
        // ∪ 入库 PENDING_WA_CONFIRM/DISPUTED ∪ 仲裁 PENDING——争议中/客诉中商户不能退驻
        // （防"提诉即跑路"，有意设计），须等仲裁 DECIDED。
        // P3b T3-W1（13 §7.1 终版枚举）：∪ 退货 PENDING_ACCEPT/ACCEPTED（登记时扣——
        // 已受理退货是「账面含、实物即将出」，未登记完不得退驻）
        long openDocs = inquiryService.countOpenDocsForWholesaler(wholesalerId)
                + inboundRequestService.countOpenForWholesaler(wholesalerId)
                + arbitrationService.countPendingForWholesaler(wholesalerId)
                + returnRequestService.countOpenForWholesaler(wholesalerId);
        return new Precheck(stockCleared, openDocs);
    }

    /** R13 前置校验：库存清零(50312) + 无未结单据(50314) + 账单结清占位（O-5）。 */
    private void assertWithdrawPreconditions(Long wholesalerId) {
        Precheck pc = precheck(wholesalerId);
        if (!pc.stockCleared()) {
            throw new BizException(ErrorCode.WITHDRAW_STOCK_NOT_ZERO);
        }
        if (pc.openDocsCount() > 0) {
            throw new BizException(ErrorCode.WITHDRAW_OPEN_DOCS_EXIST);
        }
        // TODO(P4 billing，决策 O-5)：账单全部结清校验——BillingService 尚未落地（P4），
        //   待其就绪后在此调用 billingService.assertAllSettled(wholesalerId)，
        //   未结清抛对应错误码（预留 50319 段）。本期仅库存+单据两项硬校验。
    }

    /**
     * 退驻审批通过副作用链（与审批同一事务，任一环失败整体回滚）：
     * ①主体 ACTIVE→WITHDRAWN（CAS）②全部 SKU 下架 ③店铺隐藏（随状态生效）
     * ④CustomerPrice 全部失效（含 Redis，提交后删缓存）⑤提交后踢 WA+WE token。
     */
    private void approveSideEffects(WholesalerWithdrawApplication app, LocalDateTime auditedAt) {
        Wholesaler wholesaler = wholesalerMapper.selectById(app.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        // 审批时同口径复检前置（申请后可能又入库/开单）
        assertWithdrawPreconditions(wholesaler.getId());

        // ① 状态机收口 + CAS 翻转；withdrawn_at=审批通过时刻（60 天窗口唯一时间基准）
        WholesalerStateMachine.assertTransition(wholesaler.getStatus(), WholesalerStateMachine.WITHDRAWN);
        int flipped = wholesalerMapper.update(null, new LambdaUpdateWrapper<Wholesaler>()
                .eq(Wholesaler::getId, wholesaler.getId())
                .eq(Wholesaler::getStatus, WholesalerStateMachine.ACTIVE)
                .set(Wholesaler::getStatus, WholesalerStateMachine.WITHDRAWN)
                .set(Wholesaler::getWithdrawnAt, auditedAt)
                .set(Wholesaler::getUpdatedAt, auditedAt));
        if (flipped == 0) {
            throw new BizException(ErrorCode.WHOLESALER_STATE_TRANSITION_INVALID,
                    "商户状态已变更，退驻审批中止");
        }

        // ② 全部 SKU 下架（60 天恢复后保持下架，需手动重新上架）
        int delisted = skuService.delistAllByWholesaler(wholesaler.getId());

        // ③ 店铺页隐藏：storefront 聚合仅取 status=ACTIVE 商户，状态翻转即隐藏，无需额外动作。

        // ④ CustomerPrice 全部失效（复用定价域失效链路：DB 置 DISABLED + Redis 提交后删除）
        int disabledPrices = pricingService.disableByWholesaler(wholesaler.getId());

        // ⑤ 踢 token：该商户 WA 和全部 WE 一起踢（WDR-S1-02 高危漏点——只踢 WA 漏踢 WE）
        kickWholesalerUsersAfterCommit(wholesaler.getId(), "R13 退驻");

        log.info("[P2][R13] 商户 {} 退驻生效：SKU 下架 {} 行，专属价失效 {} 行，withdrawn_at={}",
                wholesaler.getId(), delisted, disabledPrices, auditedAt);
    }

    /**
     * 事务提交后踢出该商户全部 ACTIVE 角色用户（WA+WE，不限角色）。
     * 提交后执行：审批/下架回滚时不误踢；单个用户无会话等异常吞掉不影响其余用户。
     */
    private void kickWholesalerUsersAfterCommit(Long wholesalerId, String scene) {
        final List<Long> userIds = authService.listActiveUserIdsOfWholesaler(wholesalerId);
        if (userIds.isEmpty()) {
            return;
        }
        Runnable kick = () -> {
            for (Long uid : userIds) {
                try {
                    StpUtil.kickout(uid);
                } catch (Exception e) {
                    // 无在线会话等场景：忽略，继续踢下一个
                    log.debug("[P2][{}] 用户 {} 无在线会话或踢出失败：{}", scene, uid, e.getMessage());
                }
            }
            log.info("[P2][{}] 商户 {} 关联用户（WA+WE 共 {} 人）token 已踢出", scene, wholesalerId, userIds.size());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick.run();
                }
            });
        } else {
            kick.run();
        }
    }

    /** S4：以登录态推导本人唯一的 WA 商户（不信任客户端传 wholesalerId）。 */
    private Wholesaler requireOwnWholesaler(Long userId) {
        List<Long> ids = authService.listActiveWholesalerIds(userId, "WA");
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND, "您没有已入驻的批发商商户");
        }
        Wholesaler wholesaler = wholesalerMapper.selectById(ids.get(0));
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        return wholesaler;
    }

    /** S4：操作者必须是该租户的 TA（user_roles 登录态为唯一可信来源）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    private String resolveWholesalerName(Long wholesalerId) {
        Wholesaler w = wholesalerMapper.selectById(wholesalerId);
        return w != null ? w.getName() : null;
    }

    private WithdrawApplicationVo toVo(WholesalerWithdrawApplication app, String wholesalerName) {
        return WithdrawApplicationVo.builder()
                .id(app.getId())
                .tenantId(app.getTenantId())
                .wholesalerId(app.getWholesalerId())
                .wholesalerName(wholesalerName)
                .applicantUserId(app.getApplicantUserId())
                .reason(app.getReason())
                .status(app.getStatus())
                .auditUserId(app.getAuditUserId())
                .auditedAt(app.getAuditedAt())
                .auditRemark(app.getAuditRemark())
                .createdAt(app.getCreatedAt())
                .build();
    }
}
