package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundCorrectionCreateDto;
import com.cangchu.document.dto.InboundCorrectionDecideDto;
import com.cangchu.document.entity.InboundCorrection;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.mapper.InboundCorrectionMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.InboundCorrectionService;
import com.cangchu.document.vo.InboundCorrectionVo;
import com.cangchu.inventory.dto.InboundCorrectionContext;
import com.cangchu.inventory.dto.InboundCorrectionResult;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * R3 登记纠错服务实现（P3b T1-BE，13 §1.3）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4：发起须为该租户 WK、审批须为该租户 TA——user_roles 登录态推导（AuthService）。</li>
 *   <li>租户隔离：入库单/纠错单经 selectById 受 TenantLine 兜底（跨租户按不存在拒绝）。</li>
 *   <li>S6 防重：先查后写 + pending_flag 部分唯一索引兜底（V13 先例），并发双建由
 *       DuplicateKeyException 捕获转 50353；并发双裁由 status=PENDING 条件 CAS 决出唯一赢家。</li>
 *   <li>24h 窗口在 SQL 内比数据库时间（BND-S3-01 先例；INTERVAL '24' HOUR 两库兼容写法）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundCorrectionServiceImpl implements InboundCorrectionService {

    private final InboundCorrectionMapper inboundCorrectionMapper;
    private final InboundRequestMapper inboundRequestMapper;
    private final InventoryService inventoryService;
    private final AuthService authService;
    private final TenantService tenantService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundCorrectionVo create(Long inboundId, InboundCorrectionCreateDto dto, Long wkUserId) {
        // S2 防御性双层
        if (dto == null || dto.getNewQty() == null || dto.getNewQty() < 0) {
            throw new BizException(ErrorCode.INBOUND_CORRECTION_INVALID);
        }
        if (dto.getReason() == null || dto.getReason().isBlank() || dto.getReason().length() > 512) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少纠错理由");
        }
        InboundRequest req = inboundId != null ? inboundRequestMapper.selectById(inboundId) : null;
        if (req == null) {
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND);
        }
        requireWkRole(req.getTenantId(), wkUserId);
        // 适用范围（13 §1.3）：仅正向链已入库单据（代建链错误走异议/盘点）；与实登相同 → 50354
        if (!InboundRequest.SOURCE_WA_SUBMIT.equals(req.getSource())
                || !InboundRequest.STATUS_CONFIRMED.equals(req.getStatus())
                || dto.getNewQty().equals(req.getQty())) {
            throw new BizException(ErrorCode.INBOUND_CORRECTION_INVALID);
        }
        // 24h 窗口：SQL 内比数据库时间（超窗 50352；INTERVAL '24' HOUR MySQL/H2 双方言兼容）
        Long inWindow = inboundRequestMapper.selectCount(new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .isNotNull(InboundRequest::getRegisteredAt)
                .apply("registered_at > NOW() - INTERVAL '24' HOUR"));
        if (inWindow == null || inWindow != 1) {
            throw new BizException(ErrorCode.INBOUND_CORRECTION_WINDOW_CLOSED);
        }
        // 同单在途防重：先查后写（友好报错）+ 部分唯一索引兜底（V13 先例）
        Long pending = inboundCorrectionMapper.selectCount(new LambdaQueryWrapper<InboundCorrection>()
                .eq(InboundCorrection::getInboundRequestId, inboundId)
                .eq(InboundCorrection::getStatus, InboundCorrection.STATUS_PENDING));
        if (pending != null && pending > 0) {
            throw new BizException(ErrorCode.INBOUND_CORRECTION_PENDING_EXISTS);
        }

        InboundCorrection corr = new InboundCorrection();
        corr.setId(snowflakeIdUtil.nextId());
        corr.setTenantId(req.getTenantId());
        corr.setWholesalerId(req.getWholesalerId());
        corr.setSkuId(req.getSkuId());
        corr.setInboundRequestId(inboundId);
        corr.setRefDocNo(req.getDocNo());
        corr.setOldQty(req.getQty());
        corr.setNewQty(dto.getNewQty());
        corr.setReason(dto.getReason().trim());
        corr.setStatus(InboundCorrection.STATUS_PENDING);
        corr.setPendingFlag(1);
        corr.setWkUserId(wkUserId);
        try {
            inboundCorrectionMapper.insert(corr);
        } catch (DuplicateKeyException e) {
            // uk_corr_req_pending 兜底（并发窗口内双建）
            throw new BizException(ErrorCode.INBOUND_CORRECTION_PENDING_EXISTS);
        }

        // 同事务通知租户管理员（审批中心角标；文案零角色码）
        notificationService.send(req.getTenantId(), tenantService.getContactUserId(req.getTenantId()),
                Notification.TYPE_CORRECTION_PENDING, "新的登记纠错待审批",
                "入库单 " + req.getDocNo() + " 申请纠错：" + corr.getOldQty() + " 件 → " + corr.getNewQty()
                        + " 件，请在审批中心处理。",
                Notification.REF_INBOUND, req.getId());

        log.info("[P3b][R3] WK {} 发起纠错 doc={} {}->{}", wkUserId, req.getDocNo(), corr.getOldQty(), corr.getNewQty());
        return toVo(corr);
    }

    @Override
    public Page<InboundCorrectionVo> listByTenant(Long tenantId, Long userId, String status, int page, int size) {
        // 作业列表：WK 或 TA 可见（OutboundRequestServiceImpl.requireWkOrTa 先例）
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或租户管理员可查看纠错单");
        }
        Page<InboundCorrection> p = inboundCorrectionMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<InboundCorrection>()
                        .eq(InboundCorrection::getTenantId, tenantId)
                        .eq(status != null && !status.isBlank(), InboundCorrection::getStatus, status)
                        .orderByDesc(InboundCorrection::getCreatedAt));
        Page<InboundCorrectionVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(this::toVo).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundCorrectionVo decide(Long correctionId, InboundCorrectionDecideDto dto, Long taUserId) {
        if (dto == null || dto.getConclusion() == null
                || !(InboundCorrection.STATUS_APPROVED.equals(dto.getConclusion())
                        || InboundCorrection.STATUS_REJECTED.equals(dto.getConclusion()))) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审批结论非法");
        }
        boolean approved = InboundCorrection.STATUS_APPROVED.equals(dto.getConclusion());
        String decideRemark = dto.getRemark() != null ? dto.getRemark().trim() : null;
        if (!approved && (decideRemark == null || decideRemark.isEmpty())) {
            // REJECTED：decide_remark 必填（13 §1.3）
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "驳回须填写结论备注");
        }
        InboundCorrection corr = correctionId != null ? inboundCorrectionMapper.selectById(correctionId) : null;
        if (corr == null) {
            throw new BizException(ErrorCode.INBOUND_CORRECTION_INVALID, "纠错单不存在");
        }
        requireTaRole(corr.getTenantId(), taUserId);

        // 并发双裁 CAS：status=PENDING 条件更新决出唯一赢家（50331）；pending_flag 置 NULL 释放唯一位
        LocalDateTime now = LocalDateTime.now();
        int affected = inboundCorrectionMapper.update(null, new LambdaUpdateWrapper<InboundCorrection>()
                .eq(InboundCorrection::getId, correctionId)
                .eq(InboundCorrection::getStatus, InboundCorrection.STATUS_PENDING)
                .set(InboundCorrection::getStatus, dto.getConclusion())
                .set(InboundCorrection::getPendingFlag, null)
                .set(InboundCorrection::getTaUserId, taUserId)
                .set(InboundCorrection::getDecidedAt, now)
                .set(decideRemark != null && !decideRemark.isEmpty(),
                        InboundCorrection::getDecideRemark, decideRemark)
                .set(InboundCorrection::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        String resultBrief;
        if (approved) {
            // 13 §1.3 APPROVED 单事务：锁内 CORRECTION_IN/OUT（封顶）→ qty 覆写 new_qty →
            // shortfall 写纠错单备注（差额线下定责，禁止打负）
            InboundRequest req = inboundRequestMapper.selectById(corr.getInboundRequestId());
            if (req == null) {
                throw new BizException(ErrorCode.INBOUND_NOT_FOUND);
            }
            int delta = corr.getNewQty() - req.getQty();
            int applied = 0;
            int shortfall = 0;
            if (delta != 0) {
                InboundCorrectionResult result = inventoryService.applyInboundCorrection(
                        InboundCorrectionContext.builder()
                                .wholesalerId(req.getWholesalerId())
                                .tenantId(req.getTenantId())
                                .skuId(req.getSkuId())
                                .delta(delta)
                                .refDocNo(req.getDocNo())
                                .originalPalletQty(req.getPalletQty())
                                .operatorUserId(taUserId)
                                .build());
                applied = result.getAppliedQty();
                shortfall = result.getShortfallQty();
            }
            LambdaUpdateWrapper<InboundCorrection> uw = new LambdaUpdateWrapper<InboundCorrection>()
                    .eq(InboundCorrection::getId, correctionId)
                    .set(InboundCorrection::getAppliedQty, applied)
                    .set(InboundCorrection::getShortfallQty, shortfall)
                    .set(InboundCorrection::getUpdatedAt, LocalDateTime.now());
            if (shortfall > 0) {
                // 改小遇已售：applied 封顶，差额写纠错单备注定责（12 §2.4 同构）
                uw.set(InboundCorrection::getRemark,
                        "改小遇已售：实际冲销 " + applied + " 件，差额 " + shortfall + " 件线下定责");
            }
            inboundCorrectionMapper.update(null, uw);
            // 单据可见值与库存一致（原值在纠错单 old_qty 留痕）
            inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                    .eq(InboundRequest::getId, req.getId())
                    .set(InboundRequest::getQty, corr.getNewQty())
                    .set(InboundRequest::getUpdatedAt, LocalDateTime.now()));
            resultBrief = "已通过：" + corr.getOldQty() + " 件 → " + corr.getNewQty() + " 件"
                    + (shortfall > 0 ? "（差额 " + shortfall + " 件线下定责）" : "");
        } else {
            resultBrief = "已驳回：" + decideRemark;
        }

        // 同事务通知发起库管（结论）
        notificationService.send(corr.getTenantId(), corr.getWkUserId(),
                Notification.TYPE_CORRECTION_DECIDED, "登记纠错已有结论",
                "入库单 " + corr.getRefDocNo() + " 的纠错申请" + resultBrief + "。",
                Notification.REF_INBOUND, corr.getInboundRequestId());

        log.info("[P3b][R3] TA {} 审批纠错 id={} conclusion={}", taUserId, correctionId, dto.getConclusion());
        return toVo(inboundCorrectionMapper.selectById(correctionId));
    }

    // ==================== 私有 ====================

    /** S4：发起人须为该租户 WK（InboundRequestServiceImpl.requireWkRole 同构）。 */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.INBOUND_OPERATOR_NOT_WK);
        }
    }

    /** S4：审批人须为该租户 TA（01 §4.3：R3 审批=TA）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅租户管理员可审批纠错申请");
        }
    }

    private InboundCorrectionVo toVo(InboundCorrection c) {
        return InboundCorrectionVo.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .wholesalerId(c.getWholesalerId())
                .skuId(c.getSkuId())
                .inboundRequestId(c.getInboundRequestId())
                .refDocNo(c.getRefDocNo())
                .oldQty(c.getOldQty())
                .newQty(c.getNewQty())
                .reason(c.getReason())
                .status(c.getStatus())
                .appliedQty(c.getAppliedQty())
                .shortfallQty(c.getShortfallQty())
                .remark(c.getRemark())
                .decideRemark(c.getDecideRemark())
                .wkUserId(c.getWkUserId())
                .taUserId(c.getTaUserId())
                .decidedAt(c.getDecidedAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
