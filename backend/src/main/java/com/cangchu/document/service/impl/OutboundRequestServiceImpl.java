package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.OutboundComplainDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.statemachine.DocPreconditions;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.OutboundRequestVo;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.dto.OutboundReversalContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出库单服务实现（P3 BE-W2，12 §1/§3）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权（G-1.3）：WA 侧操作校验操作人为该 wholesaler 的 ACTIVE WA；WK 侧校验该租户 WK
 *       （user_roles 登录态推导唯一可信来源，写法对齐 InquiryServiceImpl/InboundRequestServiceImpl 先例）。</li>
 *   <li>租户隔离（G-2.1）：selectById 受 TenantLine 兜底（outbound_requests 白名单），跨租户按不存在拒绝；
 *       tenantId 由 wholesaler/单据真实归属推导，不取客户端。</li>
 *   <li>S5 状态机 + CAS（G-4.1）：全部迁移走 {@link DocStateMachine}（不可达 50330 / 并发被抢占 50331），
 *       副作用与迁移同事务（回补流水、仲裁单、通知同生共死）。</li>
 *   <li>库存不变量（12 §0/§1.3）：WITHDRAWN/CANCELLED 必配 OUTBOUND_REVERSAL（reversal_of_id 配对，
 *       锁内按 refDocNo 解析原 OUTBOUND 流水）；出库链不动托盘账（P1 OUTBOUND 即不扣托盘，回补对称传 0）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundRequestServiceImpl implements OutboundRequestService {

    private final OutboundRequestMapper outboundRequestMapper;
    private final InquiryRequestMapper inquiryRequestMapper;
    // 他域数据只走对方 Service（G-S1/G-S2 先例）
    private final SkuService skuService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final ArbitrationService arbitrationService;
    private final NotificationService notificationService;
    private final DocPreconditions docPreconditions;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 30 天客诉窗口（PRD 09 §3.1：自实际出库时间起 720h） */
    private static final int COMPLAINT_WINDOW_DAYS = 30;

    /** 未结出库状态（R13，12 §8.2） */
    private static final List<String> OPEN_STATUSES = List.of(
            OutboundRequest.STATUS_PENDING_ACCEPT,
            OutboundRequest.STATUS_PRINTED,
            OutboundRequest.STATUS_COMPLAINED);

    // ==================== WA：手动出库申请（12 §1.3 WA_SUBMIT） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo submitByWa(OutboundSubmitDto dto, Long userId) {
        // S2 防御性双层（DTO @Valid 之外）
        if (dto.getWholesalerId() == null || dto.getSkuId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (dto.getQty() == null || dto.getQty() <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        int palletQty = dto.getPalletQty() != null ? dto.getPalletQty() : 0;
        if (palletQty < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }

        // R14 前置钩子（12 §1.1）+ WA 归属鉴权
        WholesalerVo wholesaler = docPreconditions.requireWholesalerActive(dto.getWholesalerId());
        requireWaRole(dto.getWholesalerId(), userId);
        Long tenantId = wholesaler.getTenantId();

        // sku 属该 wholesaler（同租户，经 SkuService，TenantLine 兜底）
        SkuVo sku = skuService.getById(dto.getSkuId());
        if (sku == null || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }

        String docNo = documentNumberService.generate(DocType.OUTBOUND, resolveSimpleCode(tenantId));
        OutboundRequest out = new OutboundRequest();
        out.setId(snowflakeIdUtil.nextId());
        out.setDocNo(docNo);
        out.setTenantId(tenantId);
        out.setWholesalerId(dto.getWholesalerId());
        out.setSkuId(dto.getSkuId());
        out.setQty(dto.getQty());
        out.setPalletQty(palletQty);
        out.setStatus(OutboundRequest.STATUS_PENDING_ACCEPT);
        out.setSource(OutboundRequest.SOURCE_WA_SUBMIT);
        out.setPrintCount(0);
        out.setWithdrawRequested(0);
        try {
            outboundRequestMapper.insert(out);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OUTBOUND_GENERATE_FAILED);
        }

        // 提交即扣（同事务，不足 STOCK_NOT_ENOUGH 整体回滚——无超卖窗口，12 §1.3）
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(dto.getWholesalerId())
                .tenantId(tenantId)
                .skuId(dto.getSkuId())
                .qty(dto.getQty())
                .refDocNo(docNo)
                .operatorUserId(userId)
                .build());

        log.info("[P3] WA {} 手动出库申请 doc={} wholesaler={} sku={} -{}",
                userId, docNo, dto.getWholesalerId(), dto.getSkuId(), dto.getQty());
        return toVo(reload(out.getId()), null);
    }

    // ==================== WA：列表 ====================

    @Override
    public Page<OutboundRequestVo> listForWa(Long userId, String status, String source, int page, int size) {
        Set<Long> wholesalerIds = new LinkedHashSet<>();
        wholesalerIds.addAll(authService.listActiveWholesalerIds(userId, "WA"));
        wholesalerIds.addAll(authService.listActiveWeWholesalerIds(userId));
        Page<OutboundRequestVo> empty = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100), 0);
        if (wholesalerIds.isEmpty()) {
            return empty;
        }
        Page<OutboundRequest> p = outboundRequestMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<OutboundRequest>()
                        .in(OutboundRequest::getWholesalerId, wholesalerIds)
                        .eq(status != null && !status.isBlank(), OutboundRequest::getStatus, status)
                        .eq(source != null && !source.isBlank(), OutboundRequest::getSource, source)
                        .orderByDesc(OutboundRequest::getCreatedAt));
        return mapPage(p);
    }

    // ==================== WA：R4 撤回（12 §3.1） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo withdrawByWa(Long outboundId, Long userId) {
        OutboundRequest out = loadOutbound(outboundId);
        requireWaRole(out.getWholesalerId(), userId);

        switch (out.getStatus()) {
            case OutboundRequest.STATUS_PENDING_ACCEPT -> {
                // 直撤：CAS→WITHDRAWN + 回补 + 意向单联动 + 通知 WK
                if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                        OutboundRequest::getId, OutboundRequest::getStatus,
                        OutboundRequest.STATUS_PENDING_ACCEPT, OutboundRequest.STATUS_WITHDRAWN, null)) {
                    throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
                }
                reverseForDoc(out, userId, "R4 商户直撤回补");
                recomputeInquiryState(out.getInquiryId());
                notificationService.send(out.getTenantId(), warehouseRecipient(out),
                        Notification.TYPE_OUTBOUND_WITHDRAWN, "出库单已撤回",
                        "出库单 " + out.getDocNo() + "（" + out.getQty() + " 件）已被商户撤回，库存已回补。",
                        Notification.REF_OUTBOUND, out.getId());
                log.info("[P3][R4] WA {} 直撤出库单 doc={}", userId, out.getDocNo());
            }
            case OutboundRequest.STATUS_PRINTED -> {
                // 纸质单已在现场：不迁移状态，置 flag 待 WK 二次确认（12 §3.1）
                int affected = outboundRequestMapper.update(null, new LambdaUpdateWrapper<OutboundRequest>()
                        .eq(OutboundRequest::getId, out.getId())
                        .eq(OutboundRequest::getStatus, OutboundRequest.STATUS_PRINTED)
                        .eq(OutboundRequest::getWithdrawRequested, 0)
                        .set(OutboundRequest::getWithdrawRequested, 1)
                        .set(OutboundRequest::getWithdrawRequestedAt, LocalDateTime.now()));
                if (affected != 1) {
                    // 重复申请 / 并发漂移：已打印仍在 → 50331；已流转到终态或已出库 → 50335
                    OutboundRequest cur = reload(out.getId());
                    if (OutboundRequest.STATUS_PRINTED.equals(cur.getStatus())
                            || OutboundRequest.STATUS_PENDING_ACCEPT.equals(cur.getStatus())) {
                        throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT, "撤回申请已提交或状态已变更，请刷新");
                    }
                    throw new BizException(ErrorCode.OUTBOUND_NOT_WITHDRAWABLE);
                }
                notificationService.send(out.getTenantId(), warehouseRecipient(out),
                        Notification.TYPE_OUTBOUND_WITHDRAW_REQUESTED, "出库撤回申请待确认",
                        "出库单 " + out.getDocNo() + " 已打印，商户申请撤回，请核对现场纸质单后确认或拒绝。",
                        Notification.REF_OUTBOUND, out.getId());
                log.info("[P3][R4] WA {} 申请撤回已打印单 doc={}", userId, out.getDocNo());
            }
            // 已出库/客诉中/终态：不可撤（已出库走退货 R5——T3 波）
            default -> throw new BizException(ErrorCode.OUTBOUND_NOT_WITHDRAWABLE);
        }
        return toVo(reload(out.getId()), null);
    }

    // ==================== WA：30 天客诉（12 §3.4 / PRD 09 §3） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo complainByWa(Long outboundId, Long userId, OutboundComplainDto dto) {
        // S2 防御性双层
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少客诉理由");
        }
        if (dto.getReason().length() > 512 || (dto.getAttachments() != null && dto.getAttachments().size() > 5)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        OutboundRequest out = loadOutbound(outboundId);
        requireWaRole(out.getWholesalerId(), userId);

        // 前置：仅代建出库（source=WK_CREATED）且已出库可诉（PRD 09 §3.1）
        if (!OutboundRequest.SOURCE_WK_CREATED.equals(out.getSource())) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "仅代建出库可发起客诉");
        }
        if (!OutboundRequest.STATUS_COMPLETED.equals(out.getStatus())) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        // 30 天窗口（completed_at 锚点；缺失回退 created_at 防御脏数据）
        LocalDateTime anchor = out.getCompletedAt() != null ? out.getCompletedAt() : out.getCreatedAt();
        if (anchor == null || anchor.isBefore(LocalDateTime.now().minusDays(COMPLAINT_WINDOW_DAYS))) {
            throw new BizException(ErrorCode.OUTBOUND_COMPLAINT_WINDOW_CLOSED);
        }

        // CAS：COMPLETED → COMPLAINED（并发唯一赢家）
        if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                OutboundRequest::getId, OutboundRequest::getStatus,
                OutboundRequest.STATUS_COMPLETED, OutboundRequest.STATUS_COMPLAINED, null)) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        // 建 KS- 仲裁单（一单一诉闸门在 ArbitrationService 内；同事务，失败整体回滚含状态迁移）
        Arbitration arb = arbitrationService.createOutboundComplaint(out, userId, "WA",
                dto.getReason(), dto.getAttachments());

        // 通知 WK（OPS 端角标=待仲裁列表计数，平台角色无定向收件人——12 §3.4 最小实现）
        notificationService.send(out.getTenantId(), warehouseRecipient(out),
                Notification.TYPE_COMPLAINT_CREATED, "代建出库被客诉",
                "客诉单 " + arb.getDocNo() + "：出库单 " + out.getDocNo() + " 被商户提出客诉，等待平台运维仲裁。",
                Notification.REF_ARBITRATION, arb.getId());

        log.info("[P3] WA {} 客诉代建出库 doc={} arb={}", userId, out.getDocNo(), arb.getDocNo());
        return toVo(reload(out.getId()), null);
    }

    // ==================== WK/TA：作业列表 ====================

    @Override
    public Page<OutboundRequestVo> listByTenant(Long tenantId, Long userId, String status, int page, int size) {
        requireWkOrTa(tenantId, userId);
        Page<OutboundRequest> p = outboundRequestMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<OutboundRequest>()
                        .eq(OutboundRequest::getTenantId, tenantId)
                        .eq(status != null && !status.isBlank(), OutboundRequest::getStatus, status)
                        .orderByDesc(OutboundRequest::getCreatedAt));
        return mapPage(p);
    }

    // ==================== WK：打印 / 回退 / 登记出库（12 §1.2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo printByWk(Long outboundId, Long wkUserId) {
        OutboundRequest out = loadOutbound(outboundId);
        requireWkRole(out.getTenantId(), wkUserId);

        if (OutboundRequest.STATUS_PRINTED.equals(out.getStatus())) {
            // 补打：count++ 不迁移状态（12 §1.6）
            int affected = outboundRequestMapper.update(null, new LambdaUpdateWrapper<OutboundRequest>()
                    .eq(OutboundRequest::getId, out.getId())
                    .eq(OutboundRequest::getStatus, OutboundRequest.STATUS_PRINTED)
                    .setSql("print_count = print_count + 1"));
            if (affected != 1) {
                throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
            }
        } else {
            // 首打：PENDING_ACCEPT→PRINTED（printed_at 首打时间）；其余状态 50330
            DocStateMachine.assertCanGo(DocKind.OUTBOUND, out.getStatus(), OutboundRequest.STATUS_PRINTED);
            if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                    OutboundRequest::getId, OutboundRequest::getStatus,
                    OutboundRequest.STATUS_PENDING_ACCEPT, OutboundRequest.STATUS_PRINTED,
                    uw -> uw.set(OutboundRequest::getPrintedAt, LocalDateTime.now())
                            .setSql("print_count = print_count + 1"))) {
                throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
            }
        }
        return toVo(reload(out.getId()), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo revertToPendingByWk(Long outboundId, Long wkUserId) {
        OutboundRequest out = loadOutbound(outboundId);
        requireWkRole(out.getTenantId(), wkUserId);
        // PRINTED→PENDING_ACCEPT（重新核对回退，纯作业不动库存）；清撤回 flag 防悬挂
        DocStateMachine.assertCanGo(DocKind.OUTBOUND, out.getStatus(), OutboundRequest.STATUS_PENDING_ACCEPT);
        if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                OutboundRequest::getId, OutboundRequest::getStatus,
                OutboundRequest.STATUS_PRINTED, OutboundRequest.STATUS_PENDING_ACCEPT,
                uw -> uw.set(OutboundRequest::getWithdrawRequested, 0)
                        .set(OutboundRequest::getWithdrawRequestedAt, null))) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // N3（08-p3-review）：回退清 flag 隐式失效撤回申请 → 补发回执（回到待受理后 WA 可直撤）
        if (out.getWithdrawRequested() != null && out.getWithdrawRequested() == 1) {
            notificationService.sendToAll(out.getTenantId(), waRecipients(out.getWholesalerId()),
                    Notification.TYPE_OUTBOUND_WITHDRAW_REJECTED, "撤回申请已失效",
                    "出库单 " + out.getDocNo() + " 已由仓库回退至待受理，您此前的撤回申请已失效；"
                            + "如仍需撤回，现可直接撤回该单。",
                    Notification.REF_OUTBOUND, out.getId());
        }
        return toVo(reload(out.getId()), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo registerByWk(Long outboundId, Long wkUserId) {
        // 旧签名兼容（13 §7.1：旧用例不传参行为兼容——托盘按默认建议值释放）
        return registerByWk(outboundId, null, wkUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo registerByWk(Long outboundId, com.cangchu.document.dto.OutboundRegisterDto dto,
                                          Long wkUserId) {
        Integer palletOverride = dto != null ? dto.getPalletRelease() : null;
        if (palletOverride != null && palletOverride < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        OutboundRequest out = loadOutbound(outboundId);
        requireWkRole(out.getTenantId(), wkUserId);
        // PRINTED→COMPLETED（登记出库；PENDING_ACCEPT 须先打印——矩阵红线）
        DocStateMachine.assertCanGo(DocKind.OUTBOUND, out.getStatus(), OutboundRequest.STATUS_COMPLETED);
        if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                OutboundRequest::getId, OutboundRequest::getStatus,
                OutboundRequest.STATUS_PRINTED, OutboundRequest.STATUS_COMPLETED,
                uw -> uw.set(OutboundRequest::getCompletedAt, LocalDateTime.now())
                        .set(OutboundRequest::getWithdrawRequested, 0)
                        .set(OutboundRequest::getWithdrawRequestedAt, null))) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // P3b T3-W1（D-8=A，13 §2.4-3）：托盘在货离仓的物理时点释放——同事务追加独立
        // PALLET_RELEASE 流水（原 OUTBOUND 流水永不 update；释放=0 不写流水）。
        // R4/R8 撤回只发生在 COMPLETED 之前 → 从未释放 → OUTBOUND_REVERSAL 维持 pallet 0，无需回补。
        int palletReleased = inventoryService.releaseOutboundPallet(
                com.cangchu.inventory.dto.PalletReleaseContext.builder()
                        .wholesalerId(out.getWholesalerId())
                        .tenantId(out.getTenantId())
                        .skuId(out.getSkuId())
                        .docQty(out.getQty())
                        .palletReleaseOverride(palletOverride)
                        .refDocNo(out.getDocNo())
                        .operatorUserId(wkUserId)
                        .build());
        if (palletReleased > 0) {
            log.info("[P3b] 出库登记托盘释放 doc={} released={}", out.getDocNo(), palletReleased);
        }
        // 询价终态联动（12 §1.4：登记出库同事务内检查）
        recomputeInquiryState(out.getInquiryId());
        // N3（08-p3-review）：撤回申请在途被登记出库隐式否决 → 补发回执（对齐 rejectWithdrawByWk 有通知的对称性）
        if (out.getWithdrawRequested() != null && out.getWithdrawRequested() == 1) {
            notificationService.sendToAll(out.getTenantId(), waRecipients(out.getWholesalerId()),
                    Notification.TYPE_OUTBOUND_WITHDRAW_REJECTED, "撤回申请未获受理",
                    "出库单 " + out.getDocNo() + " 的撤回申请未获受理，仓库已登记出库，单据已完成。",
                    Notification.REF_OUTBOUND, out.getId());
        }
        log.info("[P3] WK {} 登记出库 doc={}", wkUserId, out.getDocNo());
        return toVo(reload(out.getId()), null);
    }

    // ==================== WK：R4 二次确认 / 拒绝（12 §3.1） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo confirmWithdrawByWk(Long outboundId, Long wkUserId) {
        OutboundRequest out = loadOutbound(outboundId);
        requireWkRole(out.getTenantId(), wkUserId);
        if (out.getWithdrawRequested() == null || out.getWithdrawRequested() != 1) {
            throw new BizException(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
        }
        // CAS：PRINTED→CANCELLED（flag 保留作审计留痕）。
        // N1 修复（08-p3-review）：CAS 条件补 withdraw_requested=1——上方 flag 检查是锁外预读，
        // 并发「一拒一确认」时拒绝方先清 flag，确认方旧 CAS 仍会成功，与刚做出的拒绝决定相悖；
        // 条件化后确认方 affected=0 → 50336（申请已不在）。
        if (!DocStateMachine.casTransition(outboundRequestMapper, DocKind.OUTBOUND, out.getId(),
                OutboundRequest::getId, OutboundRequest::getStatus,
                OutboundRequest.STATUS_PRINTED, OutboundRequest.STATUS_CANCELLED,
                uw -> uw.eq(OutboundRequest::getWithdrawRequested, 1))) {
            // 语义化：仍是 PRINTED → 必是 flag 被并发拒绝清掉（50336）；否则状态漂移（50331）
            OutboundRequest cur = reload(out.getId());
            if (cur != null && OutboundRequest.STATUS_PRINTED.equals(cur.getStatus())) {
                throw new BizException(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
            }
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        reverseForDoc(out, wkUserId, "R4 仓库确认撤回回补");
        recomputeInquiryState(out.getInquiryId());
        notificationService.sendToAll(out.getTenantId(), waRecipients(out.getWholesalerId()),
                Notification.TYPE_OUTBOUND_WITHDRAWN, "撤回申请已确认",
                "出库单 " + out.getDocNo() + " 的撤回申请已由仓库确认，单据已撤销，库存已回补。",
                Notification.REF_OUTBOUND, out.getId());
        log.info("[P3][R4] WK {} 确认撤回 doc={}", wkUserId, out.getDocNo());
        return toVo(reload(out.getId()), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo rejectWithdrawByWk(Long outboundId, Long wkUserId) {
        OutboundRequest out = loadOutbound(outboundId);
        requireWkRole(out.getTenantId(), wkUserId);
        // 条件清 flag（无 flag / 状态漂移 → 50336）
        int affected = outboundRequestMapper.update(null, new LambdaUpdateWrapper<OutboundRequest>()
                .eq(OutboundRequest::getId, out.getId())
                .eq(OutboundRequest::getStatus, OutboundRequest.STATUS_PRINTED)
                .eq(OutboundRequest::getWithdrawRequested, 1)
                .set(OutboundRequest::getWithdrawRequested, 0)
                .set(OutboundRequest::getWithdrawRequestedAt, null));
        if (affected != 1) {
            throw new BizException(ErrorCode.OUTBOUND_NO_WITHDRAW_REQUEST);
        }
        notificationService.sendToAll(out.getTenantId(), waRecipients(out.getWholesalerId()),
                Notification.TYPE_OUTBOUND_WITHDRAW_REJECTED, "撤回申请被拒绝",
                "出库单 " + out.getDocNo() + " 的撤回申请被仓库拒绝（纸质单已在作业），单据继续履约。",
                Notification.REF_OUTBOUND, out.getId());
        log.info("[P3][R4] WK {} 拒绝撤回 doc={}", wkUserId, out.getDocNo());
        return toVo(reload(out.getId()), null);
    }

    // ==================== WK：代建出库（12 §3.3 US-WK-02b） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundRequestVo createByWk(WkOutboundCreateDto dto, Long wkUserId) {
        // S2 防御性双层
        if (dto.getWholesalerId() == null || dto.getSkuId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (dto.getQty() == null || dto.getQty() <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        int palletQty = dto.getPalletQty() != null ? dto.getPalletQty() : 0;
        if (palletQty < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }

        // R14 前置钩子（50313）+ WK 鉴权（tenantId 由 wholesaler 真实归属推导）
        WholesalerVo wholesaler = docPreconditions.requireWholesalerActive(dto.getWholesalerId());
        Long tenantId = wholesaler.getTenantId();
        requireWkRole(tenantId, wkUserId);

        SkuVo sku = skuService.getById(dto.getSkuId());
        if (sku == null || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }

        // 二次确认凭据（前端显著弹窗的后端凭据，缺省 50338）
        if (!Boolean.TRUE.equals(dto.getConfirmed())) {
            throw new BizException(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED, "代建出库需二次确认");
        }
        // 大额校验（锁外预读 onhand；qty > onhand×50% 须复述件数，06 §3.5b）。
        // 整数口径：qty×2 > onhand ⇔ qty > onhand×50%（边界 qty=onhand/2 不触发）。
        int onhand = currentOnhand(dto.getWholesalerId(), dto.getSkuId());
        if ((long) dto.getQty() * 2 > onhand
                && (dto.getRestatedQty() == null || !dto.getRestatedQty().equals(dto.getQty()))) {
            throw new BizException(ErrorCode.OUTBOUND_LARGE_CONFIRM_REQUIRED);
        }

        String docNo = documentNumberService.generate(DocType.OUTBOUND, resolveSimpleCode(tenantId));
        LocalDateTime now = LocalDateTime.now();
        OutboundRequest out = new OutboundRequest();
        out.setId(snowflakeIdUtil.nextId());
        out.setDocNo(docNo);
        out.setTenantId(tenantId);
        out.setWholesalerId(dto.getWholesalerId());
        out.setSkuId(dto.getSkuId());
        out.setQty(dto.getQty());
        out.setPalletQty(palletQty);
        // 代建直达 COMPLETED（12 §1.3：P1 语义子集，completed_at=客诉窗口锚点）
        out.setStatus(OutboundRequest.STATUS_COMPLETED);
        out.setSource(OutboundRequest.SOURCE_WK_CREATED);
        out.setCompletedAt(now);
        out.setPrintCount(0);
        out.setWithdrawRequested(0);
        out.setWkUserId(wkUserId);
        try {
            outboundRequestMapper.insert(out);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.OUTBOUND_GENERATE_FAILED);
        }

        // 提交即扣（不足 STOCK_NOT_ENOUGH 整体回滚，现状语义）
        inventoryService.deductStock(OutboundContext.builder()
                .wholesalerId(dto.getWholesalerId())
                .tenantId(tenantId)
                .skuId(dto.getSkuId())
                .qty(dto.getQty())
                .refDocNo(docNo)
                .operatorUserId(wkUserId)
                .build());

        // 通知归属 WA（「已确认（代建）」队列=WA 出库列表 source=WK_CREATED 过滤）
        // P3 缺陷修复：user_roles 推导收件人（owner_user_id 在 SELF_OPERATED 上是 TA），多账号全发
        notificationService.sendToAll(tenantId, waRecipients(dto.getWholesalerId()),
                Notification.TYPE_OUTBOUND_PROXY_CREATED, "代建出库已登记",
                "出库单 " + docNo + "：仓库代您登记出库 " + dto.getQty() + " 件（已确认·代建）。"
                        + "如有异议可在出库后 30 天内发起客诉。",
                Notification.REF_OUTBOUND, out.getId());

        log.info("[P3] WK {} 代建出库 doc={} wholesaler={} sku={} -{} (onhand={})",
                wkUserId, docNo, dto.getWholesalerId(), dto.getSkuId(), dto.getQty(), onhand);
        return toVo(reload(out.getId()), null);
    }

    // ==================== 跨域出口（R13） ====================

    @Override
    public long countOpenForWholesaler(Long wholesalerId) {
        return outboundRequestMapper.selectCount(new LambdaQueryWrapper<OutboundRequest>()
                .eq(OutboundRequest::getWholesalerId, wholesalerId)
                .in(OutboundRequest::getStatus, OPEN_STATUSES));
    }

    // ==================== 私有 ====================

    /**
     * WITHDRAWN/CANCELLED 必配回补（12 §1.3 推论）：reverseOutbound 锁内按 refDocNo 解析
     * 原 OUTBOUND 流水配对（reversal_of_id 非空、biz_time 沿用原锚点）。
     * 托盘：P1 OUTBOUND 不动托盘账，回补对称传 0（doc.pallet_qty 仅作业记录）。
     */
    private void reverseForDoc(OutboundRequest out, Long operatorUserId, String remark) {
        inventoryService.reverseOutbound(OutboundReversalContext.builder()
                .wholesalerId(out.getWholesalerId())
                .tenantId(out.getTenantId())
                .skuId(out.getSkuId())
                .qty(out.getQty())
                .refDocNo(out.getDocNo())
                .operatorUserId(operatorUserId)
                .remark(remark)
                .build());
    }

    /**
     * 意向单联动（12 §1.4/§3.1 冻结口径）：R4 撤回作用于单张出库单——
     * <ul>
     *   <li>存在未结单（PENDING_ACCEPT/PRINTED/COMPLAINED）→ 询价停留 CONFIRMED（部分撤回继续履约）；</li>
     *   <li>全部 WITHDRAWN/CANCELLED → 询价 CAS CONFIRMED→PENDING 回滚（回到待确认）；</li>
     *   <li>全部终态且 ≥1 单 COMPLETED → 询价 CAS CONFIRMED→COMPLETED（登记出库联动；
     *       混合终态归 COMPLETED——已有实际履约，见据实现备注）。</li>
     * </ul>
     * CAS eq(CONFIRMED) 兜底：已 VOIDED/COMPLETED 的询价不受影响（幂等静默）。
     */
    private void recomputeInquiryState(Long inquiryId) {
        if (inquiryId == null) {
            return;
        }
        List<OutboundRequest> siblings = outboundRequestMapper.selectList(
                new LambdaQueryWrapper<OutboundRequest>().eq(OutboundRequest::getInquiryId, inquiryId));
        boolean anyOpen = siblings.stream().anyMatch(o -> OPEN_STATUSES.contains(o.getStatus()));
        if (anyOpen || siblings.isEmpty()) {
            return;
        }
        boolean anyCompleted = siblings.stream()
                .anyMatch(o -> OutboundRequest.STATUS_COMPLETED.equals(o.getStatus()));
        String target = anyCompleted ? InquiryRequest.STATUS_COMPLETED : InquiryRequest.STATUS_PENDING;
        int affected = inquiryRequestMapper.update(null, new LambdaUpdateWrapper<InquiryRequest>()
                .eq(InquiryRequest::getId, inquiryId)
                .eq(InquiryRequest::getStatus, InquiryRequest.STATUS_CONFIRMED)
                .set(InquiryRequest::getStatus, target));
        if (affected == 1) {
            log.info("[P3] 询价 {} 联动 CONFIRMED→{}（出库单全部终结）", inquiryId, target);
        }
    }

    /** 仓库侧通知收件人：代建单=登记 WK；询价/WA 提交单无定向 WK → 租户联系人（TA，审批中心先例）。 */
    private Long warehouseRecipient(OutboundRequest out) {
        if (OutboundRequest.SOURCE_WK_CREATED.equals(out.getSource()) && out.getWkUserId() != null) {
            return out.getWkUserId();
        }
        return tenantService.getContactUserId(out.getTenantId());
    }

    /**
     * 「归属 WA」通知收件人（P3 缺陷修复）：以 user_roles 推导（listForWa 同源先例），多账号全发。
     * 不可用 wholesalers.owner_user_id——SELF_OPERATED 商户该列是 TA 操作人，会漏发真实 WA。
     */
    private List<Long> waRecipients(Long wholesalerId) {
        return authService.listActiveWaUserIdsOfWholesaler(wholesalerId);
    }

    /** 锁外预读在库（大额校验用；真正扣减以 deductStock 锁内校验为准）。 */
    private int currentOnhand(Long wholesalerId, Long skuId) {
        List<InventoryVo> list = inventoryService.queryInventory(wholesalerId, skuId);
        return list.isEmpty() || list.get(0).getQty() == null ? 0 : list.get(0).getQty();
    }

    /**
     * S4：WA 侧操作人须为该 wholesaler 的 ACTIVE WA。
     * WE 不开放出库操作（PRD 09 §5：出库客诉 WE ❌；WePermissions 白名单无出库授权位——授权位扩展留产品收口）。
     */
    private void requireWaRole(Long wholesalerId, Long userId) {
        if (!authService.hasWholesalerRole(userId, "WA", wholesalerId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅批发商管理员可操作出库单");
        }
    }

    /** S4：WK 侧作业操作人须为该租户 WK。 */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员可执行出库作业");
        }
    }

    /** 作业列表：WK 或 TA 可见。 */
    private void requireWkOrTa(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或租户管理员可查看出库作业");
        }
    }

    /** 取出库单（TenantLine 兜底：跨租户不可见 → 按不存在，统一 50330 拒绝不泄露存在性）。 */
    private OutboundRequest loadOutbound(Long outboundId) {
        OutboundRequest out = outboundId != null ? outboundRequestMapper.selectById(outboundId) : null;
        if (out == null) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "出库单不存在");
        }
        return out;
    }

    private OutboundRequest reload(Long id) {
        return outboundRequestMapper.selectById(id);
    }

    /** 取租户简码用于 docNo（G-S2 经 TenantService）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + tenantId;
    }

    private Page<OutboundRequestVo> mapPage(Page<OutboundRequest> p) {
        Map<Long, String> nameCache = new HashMap<>();
        Page<OutboundRequestVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream()
                .map(r -> toVo(r, nameCache.computeIfAbsent(r.getWholesalerId(), id -> {
                    WholesalerVo w = wholesalerService.getById(id);
                    return w != null ? w.getName() : "";
                })))
                .toList());
        return out;
    }

    private OutboundRequestVo toVo(OutboundRequest r, String wholesalerName) {
        return OutboundRequestVo.builder()
                .id(r.getId())
                .docNo(r.getDocNo())
                .inquiryId(r.getInquiryId())
                .tenantId(r.getTenantId())
                .wholesalerId(r.getWholesalerId())
                .wholesalerName(wholesalerName)
                .skuId(r.getSkuId())
                .qty(r.getQty())
                .palletQty(r.getPalletQty())
                .status(r.getStatus())
                .source(r.getSource())
                .wkUserId(r.getWkUserId())
                .printedAt(r.getPrintedAt())
                .printCount(r.getPrintCount())
                .completedAt(r.getCompletedAt())
                .withdrawRequested(r.getWithdrawRequested())
                .withdrawRequestedAt(r.getWithdrawRequestedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
