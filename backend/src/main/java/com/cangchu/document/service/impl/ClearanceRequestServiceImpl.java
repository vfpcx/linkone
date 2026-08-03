package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.file.AttachmentUrls;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ClearanceCreateDto;
import com.cangchu.document.dto.ClearanceDecideDto;
import com.cangchu.document.dto.ClearanceUpdateDto;
import com.cangchu.document.entity.ClearanceRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.ClearanceRequestMapper;
import com.cangchu.document.service.ClearanceRequestService;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.ClearanceRequestVo;
import com.cangchu.inventory.dto.ClearStockContext;
import com.cangchu.inventory.dto.ClearStockResult;
import com.cangchu.inventory.service.BatchService;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.BatchVo;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 清库单服务实现（P3b T4-W2，13 §3.4；与 CountSheetServiceImpl 同构）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4：建/编/提/删 requireWkRole、审批 requireTaRole、查看 requireWkOrTa——user_roles
 *       登录态推导；wholesalerId/skuId 随批次推导（不取客户端）；批次经 BatchService 出口
 *       校验归属（跨租户按不存在 50363，G-S1 不直连 BatchMapper）。</li>
 *   <li>S6 防重：同批次在途清库单先查后写（50365 友好报错）+ uk_qk_batch_pending 部分唯一兜底
 *       （并发双建/REJECTED 重提撞新在途单均由 DuplicateKey 转 50365）。</li>
 *   <li>S7：审批通过在 InventoryService 锁 {@code lock:inv:{w}:{s}} 内 clearStock——
 *       applied 以审批时刻锁内 onhand 封顶（封顶口径家族第 4 处），qty 恒 ≥0。</li>
 *   <li>R14 有意不接（13 §3.6）：清库是存量库存治理，商户下架/退驻中仍可清库
 *       （防「无法清库→永远退不了驻」死锁）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClearanceRequestServiceImpl implements ClearanceRequestService {

    /** 清库凭证照片上限（PRD 总纲：清库凭证 ≤3 张，与单据链 ≤5 不同域） */
    private static final int MAX_PHOTOS = 3;

    private static final Set<String> REASONS = Set.of(
            ClearanceRequest.REASON_EXPIRED, ClearanceRequest.REASON_DAMAGED, ClearanceRequest.REASON_OTHER);

    private final ClearanceRequestMapper clearanceRequestMapper;
    private final BatchService batchService;
    private final InventoryService inventoryService;
    private final SkuService skuService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final DocumentNumberService documentNumberService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    // ==================== WK：建 / 编 / 删 / 提 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearanceRequestVo createByWk(ClearanceCreateDto dto, Long tenantId, Long userId) {
        if (dto == null || dto.getBatchId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少清库批次");
        }
        requireWkRole(tenantId, userId);
        // 批次经 inventory 域出口取回（跨租户按不存在 50363）；前置 50365
        BatchVo batch = batchService.getTenantBatch(tenantId, dto.getBatchId());
        assertClearable(batch);
        int qty = resolveQty(dto.getQty(), batch);
        validateCommonFields(dto.getReason(), dto.getReasonRemark(), dto.getPalletRelease());
        String attachments = encodePhotos(dto.getAttachments());

        // 同批次在途至多一张（先查后写 50365；uk_qk_batch_pending 兜底并发双建）
        Long open = clearanceRequestMapper.selectCount(new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getBatchId, dto.getBatchId())
                .in(ClearanceRequest::getStatus,
                        ClearanceRequest.STATUS_DRAFT, ClearanceRequest.STATUS_PENDING_APPROVAL));
        if (open != null && open > 0) {
            throw new BizException(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE, "该批次已有进行中的清库单");
        }

        ClearanceRequest req = new ClearanceRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(documentNumberService.generate(DocType.CLEARANCE, resolveSimpleCode(tenantId)));
        req.setTenantId(tenantId);
        req.setWholesalerId(batch.getWholesalerId());
        req.setSkuId(batch.getSkuId());
        req.setBatchId(dto.getBatchId());
        req.setQty(qty);
        req.setPalletRelease(dto.getPalletRelease());
        req.setReason(dto.getReason());
        req.setReasonRemark(trimToNull(dto.getReasonRemark(), 512));
        req.setAttachments(attachments);
        req.setStatus(ClearanceRequest.STATUS_DRAFT);
        req.setPendingFlag(1);
        req.setWkUserId(userId);
        req.setRemark(trimToNull(dto.getRemark(), 512));
        try {
            clearanceRequestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            // 并发双建撞 uk_qk_batch_pending（docNo 撞 uk 概率可忽略，50365 语义占优——盘点 50356 先例）
            throw new BizException(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE, "该批次已有进行中的清库单");
        }
        log.info("[P3b][QK] WK {} 建清库草稿 doc={} batch={} qty={} reason={}",
                userId, req.getDocNo(), dto.getBatchId(), qty, dto.getReason());
        return toVo(req, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearanceRequestVo updateByWk(Long id, ClearanceUpdateDto dto, Long userId) {
        if (dto == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        ClearanceRequest req = loadRequest(id);
        requireWkRole(req.getTenantId(), userId);
        BatchVo batch = batchService.getTenantBatch(req.getTenantId(), req.getBatchId());
        int qty = resolveQty(dto.getQty(), batch);
        validateCommonFields(dto.getReason(), dto.getReasonRemark(), dto.getPalletRelease());
        String attachments = encodePhotos(dto.getAttachments());

        LocalDateTime now = LocalDateTime.now();
        if (ClearanceRequest.STATUS_REJECTED.equals(req.getStatus())) {
            // 被驳回编辑重提（矩阵 REJECTED→DRAFT）：复检批次仍待清理（等待期被清/被冻结则 50365）；
            // pending_flag 回置 1 撞同批次新在途单 → uk_qk_batch_pending → 50365
            assertClearable(batch);
            try {
                boolean ok = DocStateMachine.casTransition(clearanceRequestMapper, DocKind.CLEARANCE,
                        id, ClearanceRequest::getId, ClearanceRequest::getStatus,
                        ClearanceRequest.STATUS_REJECTED, ClearanceRequest.STATUS_DRAFT,
                        uw -> uw.set(ClearanceRequest::getPendingFlag, 1)
                                .set(ClearanceRequest::getWkUserId, userId)
                                .set(ClearanceRequest::getUpdatedAt, now));
                if (!ok) {
                    throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
                }
            } catch (DataIntegrityViolationException e) {
                throw new BizException(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE, "该批次已有进行中的清库单");
            }
        } else if (!ClearanceRequest.STATUS_DRAFT.equals(req.getStatus())) {
            // 待审批/已通过不可编辑（矩阵红线统一 50330）
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        clearanceRequestMapper.update(null, new LambdaUpdateWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getId, id)
                .set(ClearanceRequest::getQty, qty)
                .set(ClearanceRequest::getPalletRelease, dto.getPalletRelease())
                .set(ClearanceRequest::getReason, dto.getReason())
                .set(ClearanceRequest::getReasonRemark, trimToNull(dto.getReasonRemark(), 512))
                .set(ClearanceRequest::getAttachments, attachments)
                .set(ClearanceRequest::getRemark, trimToNull(dto.getRemark(), 512))
                .set(ClearanceRequest::getWkUserId, userId)
                .set(ClearanceRequest::getUpdatedAt, LocalDateTime.now()));
        log.info("[P3b][QK] WK {} 编辑清库单 doc={} qty={}", userId, req.getDocNo(), qty);
        return toVo(loadRequest(id), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByWk(Long id, Long userId) {
        ClearanceRequest req = loadRequest(id);
        requireWkRole(req.getTenantId(), userId);
        // 仅草稿可删（条件删除防并发提交后误删，盘点先例）
        int affected = clearanceRequestMapper.delete(new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getId, id)
                .eq(ClearanceRequest::getStatus, ClearanceRequest.STATUS_DRAFT));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        log.info("[P3b][QK] WK {} 删除清库草稿 doc={}", userId, req.getDocNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearanceRequestVo submitByWk(Long id, Long userId) {
        ClearanceRequest req = loadRequest(id);
        requireWkRole(req.getTenantId(), userId);
        DocStateMachine.assertCanGo(DocKind.CLEARANCE, req.getStatus(), ClearanceRequest.STATUS_PENDING_APPROVAL);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(clearanceRequestMapper, DocKind.CLEARANCE,
                id, ClearanceRequest::getId, ClearanceRequest::getStatus,
                ClearanceRequest.STATUS_DRAFT, ClearanceRequest.STATUS_PENDING_APPROVAL,
                uw -> uw.set(ClearanceRequest::getWkUserId, userId)
                        .set(ClearanceRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // 通知：提交 → 租户管理员（审批中心角标先例，getContactUserId）
        notificationService.send(req.getTenantId(), tenantService.getContactUserId(req.getTenantId()),
                Notification.TYPE_CLEARANCE_PENDING, "新的清库单待审批",
                "清库单 " + req.getDocNo() + "（商品「" + skuName(req.getSkuId()) + "」，现场核数 "
                        + req.getQty() + " 件，原因：" + reasonLabel(req.getReason())
                        + "）已提交，请在审批中心处理；通过时将按剩余在库封顶生效。",
                Notification.REF_CLEARANCE, req.getId());
        log.info("[P3b][QK] WK {} 提交清库单 doc={}", userId, req.getDocNo());
        return toVo(loadRequest(id), null, null, null);
    }

    // ==================== 查询 ====================

    @Override
    public List<ClearanceRequestVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status) {
        requireWkOrTa(tenantId, userId);
        boolean pendingQueue = ClearanceRequest.STATUS_PENDING_APPROVAL.equals(status);
        LambdaQueryWrapper<ClearanceRequest> qw = new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getTenantId, tenantId)
                .eq(wholesalerId != null, ClearanceRequest::getWholesalerId, wholesalerId)
                .eq(status != null && !status.isBlank(), ClearanceRequest::getStatus, status);
        if (pendingQueue) {
            // 待审批队列创建升序（先到先审，idx_qk_tenant_status 覆盖）
            qw.orderByAsc(ClearanceRequest::getCreatedAt);
        } else {
            qw.orderByDesc(ClearanceRequest::getCreatedAt);
        }
        return clearanceRequestMapper.selectList(qw).stream()
                .map(r -> toVo(r, null, null, null))
                .toList();
    }

    @Override
    public ClearanceRequestVo getDetail(Long id, Long userId) {
        ClearanceRequest req = loadRequest(id);
        requireWkOrTa(req.getTenantId(), userId);
        BatchVo batch = null;
        try {
            batch = batchService.getTenantBatch(req.getTenantId(), req.getBatchId());
        } catch (BizException ignored) {
            // 防御：批次行缺失不阻断单据详情
        }
        return toVo(req, wholesalerName(req.getWholesalerId()), skuName(req.getSkuId()), batch);
    }

    // ==================== TA：审批 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClearanceRequestVo decideByTa(Long id, ClearanceDecideDto dto, Long userId) {
        if (dto == null || dto.getConclusion() == null
                || !(ClearanceRequest.STATUS_APPROVED.equals(dto.getConclusion())
                        || ClearanceRequest.STATUS_REJECTED.equals(dto.getConclusion()))) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审批结论非法");
        }
        boolean approved = ClearanceRequest.STATUS_APPROVED.equals(dto.getConclusion());
        String remark = dto.getRemark() != null ? dto.getRemark().trim() : null;
        if (!approved && (remark == null || remark.isEmpty())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "驳回须填写理由");
        }
        ClearanceRequest req = loadRequest(id);
        requireTaRole(req.getTenantId(), userId);
        DocStateMachine.assertCanGo(DocKind.CLEARANCE, req.getStatus(), dto.getConclusion());

        // 并发双裁 CAS 决出唯一赢家（败方 50331）；pending_flag 置 NULL 释放同批次唯一位
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(clearanceRequestMapper, DocKind.CLEARANCE,
                id, ClearanceRequest::getId, ClearanceRequest::getStatus,
                ClearanceRequest.STATUS_PENDING_APPROVAL, dto.getConclusion(),
                uw -> uw.set(ClearanceRequest::getPendingFlag, null)
                        .set(ClearanceRequest::getTaUserId, userId)
                        .set(ClearanceRequest::getDecidedAt, now)
                        .set(!approved, ClearanceRequest::getRejectRemark, remark)
                        .set(ClearanceRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        String resultBrief;
        if (approved) {
            resultBrief = applyApproved(req, userId);
        } else {
            resultBrief = "已驳回：" + remark + "（记录保留，可修改后重新提交）";
        }

        // 结论 → 发起库管（单人，盘点/纠错结论先例）
        notificationService.send(req.getTenantId(), req.getWkUserId(),
                Notification.TYPE_CLEARANCE_DECIDED, "清库单已有审批结论",
                "清库单 " + req.getDocNo() + " " + resultBrief,
                Notification.REF_CLEARANCE, req.getId());
        log.info("[P3b][QK] TA {} 审批清库单 doc={} conclusion={}", userId, req.getDocNo(), dto.getConclusion());
        return toVo(loadRequest(id), null, null, null);
    }

    /**
     * 审批通过事务体（13 §3.4）：锁内 clearStock 封顶（applied=min(现场核数, max(onhand,0))，
     * 封顶口径家族第 4 处）→ EXPIRY_CLEARANCE 流水（batch_id 落值、biz_time=清库日当日截止、
     * pallet_delta=−释放封顶）→ pallet_release 回写实际释放值（RTN 先例）+ 差额写单据备注
     * → 批次 remaining 清零转 CLEARED（启→关冻结的在途单按提交时策略走完）
     * → 商户凭证通知（站内信含照片凭证，PRD §3.5）。
     *
     * @return 结论通知摘要
     */
    private String applyApproved(ClearanceRequest req, Long taUserId) {
        ClearStockResult result = inventoryService.clearStock(ClearStockContext.builder()
                .wholesalerId(req.getWholesalerId())
                .tenantId(req.getTenantId())
                .skuId(req.getSkuId())
                .batchId(req.getBatchId())
                .qty(req.getQty())
                .palletReleaseOverride(req.getPalletRelease())
                .refDocNo(req.getDocNo())
                .operatorUserId(taUserId)
                .build());

        String shortfallNote = null;
        if (result.getShortfallQty() > 0) {
            shortfallNote = "清库 " + req.getQty() + " 件，审批时在库仅 " + result.getAppliedQty()
                    + " 件，已按 " + result.getAppliedQty() + " 件生效，差额 "
                    + result.getShortfallQty() + " 件请线下核查";
        }
        clearanceRequestMapper.update(null, new LambdaUpdateWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getId, req.getId())
                .set(ClearanceRequest::getPalletRelease, result.getPalletReleased())
                .set(shortfallNote != null, ClearanceRequest::getRemark,
                        appendCapped(req.getRemark(), shortfallNote, 512))
                .set(ClearanceRequest::getUpdatedAt, LocalDateTime.now()));

        // 批次生效：推算剩余清零、CLEARED、cleared_at（inventory 域出口，G-S1）
        batchService.markCleared(req.getBatchId());

        // 商户凭证通知（站内信含照片凭证 URL；不计正常出库统计、不影响销售数据——文案明示）
        List<String> photos = AttachmentUrls.decode(req.getAttachments());
        String content = "您的商品「" + skuName(req.getSkuId()) + "」批次因"
                + reasonLabel(req.getReason()) + "已强制清库 " + result.getAppliedQty()
                + " 件（清库单 " + req.getDocNo() + "，仓储费当日截止，不计入正常出库统计）。凭证照片："
                + String.join("、", photos);
        notificationService.sendToAll(req.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                Notification.TYPE_CLEARANCE_DECIDED, "批次已强制清库", content,
                Notification.REF_CLEARANCE, req.getId());

        return "已通过：清库 " + result.getAppliedQty() + " 件、释放托盘 " + result.getPalletReleased()
                + " 个已生效" + (shortfallNote != null ? "（" + shortfallNote + "）" : "") + "。";
    }

    // ==================== R13 / 看板出口 ====================

    @Override
    public long countOpenForWholesaler(Long wholesalerId) {
        Long cnt = clearanceRequestMapper.selectCount(new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getWholesalerId, wholesalerId)
                .in(ClearanceRequest::getStatus,
                        ClearanceRequest.STATUS_DRAFT, ClearanceRequest.STATUS_PENDING_APPROVAL));
        return cnt != null ? cnt : 0;
    }

    @Override
    public long countPendingApprovalForTenant(Long tenantId) {
        Long cnt = clearanceRequestMapper.selectCount(new LambdaQueryWrapper<ClearanceRequest>()
                .eq(ClearanceRequest::getTenantId, tenantId)
                .eq(ClearanceRequest::getStatus, ClearanceRequest.STATUS_PENDING_APPROVAL));
        return cnt != null ? cnt : 0;
    }

    // ==================== 私有 ====================

    /** 前置校验（50365）：仅 PENDING_CLEARANCE 且推算剩余&gt;0 的批次可清库（13 §3.4）。 */
    private void assertClearable(BatchVo batch) {
        if (!com.cangchu.inventory.entity.Batch.STATUS_PENDING_CLEARANCE.equals(batch.getStatus())
                || batch.getRemainingQty() == null || batch.getRemainingQty() <= 0) {
            throw new BizException(ErrorCode.CLEARANCE_BATCH_NOT_CLEARABLE);
        }
    }

    /** 清库件数决议：null=默认批次推算剩余；&gt;0 且 ≤ 池当前在库（PRD §3.5 表单口径，50251）。 */
    private int resolveQty(Integer qty, BatchVo batch) {
        int resolved = qty != null ? qty : (batch.getRemainingQty() != null ? batch.getRemainingQty() : 0);
        if (resolved <= 0) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "清库件数无效");
        }
        // 现场核数 ≤ 该 SKU 池当前在库（审批时刻锁内还会再封顶——两时点语义分离）
        inventoryService.assertStockEnough(batch.getWholesalerId(), batch.getSkuId(), resolved);
        return resolved;
    }

    private void validateCommonFields(String reason, String reasonRemark, Integer palletRelease) {
        if (reason == null || !REASONS.contains(reason)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "清库原因非法");
        }
        if (ClearanceRequest.REASON_OTHER.equals(reason)
                && (reasonRemark == null || reasonRemark.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "选择其他原因时须填写备注");
        }
        if (palletRelease != null && palletRelease < 0) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "释放托盘数无效");
        }
    }

    /** 实物照片必填 ≥1（50366，R19 刚性）≤3；N2 白名单由 AttachmentUrls.encode 校验（50340）。 */
    private String encodePhotos(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            throw new BizException(ErrorCode.CLEARANCE_PHOTO_REQUIRED);
        }
        if (attachments.size() > MAX_PHOTOS) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "清库凭证照片最多 " + MAX_PHOTOS + " 张");
        }
        return AttachmentUrls.encode(attachments);
    }

    /** S4：建/编/提/删须为该租户 WK（盘点同构）。 */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员可操作清库单");
        }
    }

    /** S4：审批须为该租户 TA（01 §4.3）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅租户管理员可审批清库单");
        }
    }

    /** 查看：WK 或 TA（requireWkOrTa 先例）。 */
    private void requireWkOrTa(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或租户管理员可查看清库单");
        }
    }

    /** 取清库单（TenantLine 兜底：跨租户不可见 → 按不存在拒绝）。 */
    private ClearanceRequest loadRequest(Long id) {
        ClearanceRequest req = id != null ? clearanceRequestMapper.selectById(id) : null;
        if (req == null) {
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND, "清库单不存在");
        }
        return req;
    }

    private String reasonLabel(String reason) {
        return switch (reason) {
            case ClearanceRequest.REASON_EXPIRED -> "过期";
            case ClearanceRequest.REASON_DAMAGED -> "损坏";
            case ClearanceRequest.REASON_OTHER -> "其他";
            default -> reason;
        };
    }

    private String wholesalerName(Long wholesalerId) {
        WholesalerVo w = wholesalerService.getById(wholesalerId);
        return w != null ? w.getName() : String.valueOf(wholesalerId);
    }

    private String skuName(Long skuId) {
        SkuVo sku = skuService.getById(skuId);
        return sku != null ? sku.getName() : String.valueOf(skuId);
    }

    /** 追加备注并按列宽截断（封顶差额信息优先，盘点先例）。 */
    private String appendCapped(String base, String append, int max) {
        if (append == null) {
            return null;
        }
        String merged = (base == null || base.isBlank()) ? append : base + "；" + append;
        if (merged.length() > max) {
            merged = merged.substring(0, max);
        }
        return merged;
    }

    private String trimToNull(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > max) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        return t;
    }

    /** 取租户简码用于 docNo（经 TenantService，G-S2 先例）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + tenantId;
    }

    private ClearanceRequestVo toVo(ClearanceRequest r, String wholesalerName, String skuName, BatchVo batch) {
        Integer currentStock = null;
        Integer suggested = null;
        if (batch != null) {
            // 详情链路：当刻在库 + 默认释放托盘建议值（封顶预览，13 §2.4-2；盘点 toItemVo 同构）
            List<InventoryVo> list = inventoryService.queryInventory(r.getWholesalerId(), r.getSkuId());
            int onhand = 0;
            int pallet = 0;
            if (!list.isEmpty()) {
                InventoryVo inv = list.get(0);
                onhand = inv.getQty() != null ? Math.max(inv.getQty(), 0) : 0;
                pallet = inv.getPalletQty() != null ? Math.max(inv.getPalletQty(), 0) : 0;
            }
            currentStock = onhand;
            if (r.getPalletRelease() != null) {
                suggested = Math.min(r.getPalletRelease(), pallet);
            } else {
                int applied = Math.min(r.getQty() != null ? r.getQty() : 0, onhand);
                if (applied == 0 || pallet == 0) {
                    suggested = 0;
                } else if (applied >= onhand) {
                    suggested = pallet; // 全出清零：默认释放全部
                } else {
                    suggested = Math.min((int) Math.ceil(pallet * (double) applied / onhand), pallet);
                }
            }
        }
        return ClearanceRequestVo.builder()
                .id(r.getId())
                .docNo(r.getDocNo())
                .tenantId(r.getTenantId())
                .wholesalerId(r.getWholesalerId())
                .wholesalerName(wholesalerName)
                .skuId(r.getSkuId())
                .skuName(skuName)
                .batchId(r.getBatchId())
                .batchNo(batch != null ? batch.getBatchNo() : null)
                .batchExpiryDate(batch != null ? batch.getExpiryDate() : null)
                .batchRemainingQty(batch != null ? batch.getRemainingQty() : null)
                .qty(r.getQty())
                .palletRelease(r.getPalletRelease())
                .reason(r.getReason())
                .reasonRemark(r.getReasonRemark())
                .attachments(AttachmentUrls.decode(r.getAttachments()))
                .status(r.getStatus())
                .wkUserId(r.getWkUserId())
                .taUserId(r.getTaUserId())
                .decidedAt(r.getDecidedAt())
                .rejectRemark(r.getRejectRemark())
                .remark(r.getRemark())
                .currentStock(currentStock)
                .suggestedPalletRelease(suggested)
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
