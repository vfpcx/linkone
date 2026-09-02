package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.file.AttachmentUrls;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.CountSheetCreateDto;
import com.cangchu.document.dto.CountSheetDecideDto;
import com.cangchu.document.dto.CountSheetItemDto;
import com.cangchu.document.dto.CountSheetUpdateDto;
import com.cangchu.document.entity.CountSheet;
import com.cangchu.document.entity.CountSheetItem;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.entity.ReturnRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.CountSheetItemMapper;
import com.cangchu.document.mapper.CountSheetMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.mapper.ReturnRequestMapper;
import com.cangchu.document.service.CountSheetService;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.CountSheetItemVo;
import com.cangchu.document.vo.CountSheetVo;
import com.cangchu.document.vo.StocktakeInTransitHintVo;
import com.cangchu.inventory.dto.GainStockContext;
import com.cangchu.inventory.dto.LossStockContext;
import com.cangchu.inventory.dto.LossStockResult;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点单服务实现（P3b T3-W2，13 §2.2）。
 *
 * <p>安全规约（05-secure-coding-guardrails，与 ReturnRequestServiceImpl 同构）：
 * <ul>
 *   <li>S4：建/编/提/删 requireWkRole、审批 requireTaRole、查看 requireWkOrTa——user_roles
 *       登录态推导；tenantId 由 wholesaler 真实归属推导（不取客户端）；SKU 必须归属被盘商户。</li>
 *   <li>S6 防重：同商户在途盘点先查后写（50356 友好报错）+ uk_cs_ws_pending 部分唯一兜底
 *       （并发双建/REJECTED 重提撞新在途单均由 DuplicateKey 转 50356）。</li>
 *   <li>S7：审批通过逐 SKU 在 InventoryService 锁 {@code lock:inv:{w}:{s}} 内串行
 *       GAIN/LOSS（skuId 升序防死锁）；盘亏 D-10 以审批时刻锁内 onhand 封顶，qty 恒 ≥0。</li>
 *   <li>R14 有意不接（13 §3.6 同理）：盘点是存量库存治理，商户 OFFLINE/退驻中仍可盘。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountSheetServiceImpl implements CountSheetService {

    /** 单张盘点单明细行上限（防御性；T1 提交 ≤50 行先例的盘点域放宽——全仓性质） */
    private static final int MAX_ITEMS = 200;

    private final CountSheetMapper countSheetMapper;
    private final CountSheetItemMapper countSheetItemMapper;
    private final OutboundRequestMapper outboundRequestMapper;
    private final ReturnRequestMapper returnRequestMapper;
    private final SkuService skuService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    // ==================== WK：建 / 编 / 删 / 提 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CountSheetVo createByWk(CountSheetCreateDto dto, Long userId) {
        if (dto == null || dto.getWholesalerId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少被盘商户");
        }
        WholesalerVo wholesaler = wholesalerService.getById(dto.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        Long tenantId = wholesaler.getTenantId();
        requireWkRole(tenantId, userId);
        List<CountSheetItem> items = validateAndBuildItems(dto.getItems(), dto.getWholesalerId(), tenantId, null);

        // 同商户在途至多一张（先查后写 50356；uk_cs_ws_pending 兜底并发双建）
        Long open = countSheetMapper.selectCount(new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getWholesalerId, dto.getWholesalerId())
                .in(CountSheet::getStatus, CountSheet.STATUS_DRAFT, CountSheet.STATUS_PENDING_APPROVAL));
        if (open != null && open > 0) {
            throw new BizException(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        }

        CountSheet sheet = new CountSheet();
        sheet.setId(snowflakeIdUtil.nextId());
        sheet.setDocNo(documentNumberService.generate(DocType.STOCKTAKE, resolveSimpleCode(tenantId)));
        sheet.setTenantId(tenantId);
        sheet.setWholesalerId(dto.getWholesalerId());
        sheet.setStatus(CountSheet.STATUS_DRAFT);
        sheet.setPendingFlag(1);
        sheet.setWkUserId(userId);
        sheet.setRemark(trimToNull(dto.getRemark(), 512));
        sheet.setAttachments(encodeAttachments(dto.getAttachments()));
        try {
            countSheetMapper.insert(sheet);
        } catch (DuplicateKeyException e) {
            // 并发双建撞 uk_cs_ws_pending（docNo 撞 uk_cs_doc_no 概率可忽略，统一转 50356 语义占优）
            throw new BizException(ErrorCode.STOCKTAKE_OPEN_EXISTS);
        }
        for (CountSheetItem item : items) {
            item.setSheetId(sheet.getId());
            countSheetItemMapper.insert(item);
        }

        log.info("[P3b][PD] WK {} 建盘点草稿 doc={} wholesaler={} items={}",
                userId, sheet.getDocNo(), dto.getWholesalerId(), items.size());
        return toVo(sheet, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CountSheetVo updateByWk(Long sheetId, CountSheetUpdateDto dto, Long userId) {
        if (dto == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        CountSheet sheet = loadSheet(sheetId);
        requireWkRole(sheet.getTenantId(), userId);
        List<CountSheetItem> items = validateAndBuildItems(dto.getItems(), sheet.getWholesalerId(),
                sheet.getTenantId(), null);

        LocalDateTime now = LocalDateTime.now();
        if (CountSheet.STATUS_REJECTED.equals(sheet.getStatus())) {
            // 被驳回编辑重提（矩阵 REJECTED→DRAFT）：pending_flag 回置 1，
            // 撞同商户新在途单 → uk_cs_ws_pending → 50356
            try {
                boolean ok = DocStateMachine.casTransition(countSheetMapper, DocKind.STOCKTAKE,
                        sheetId, CountSheet::getId, CountSheet::getStatus,
                        CountSheet.STATUS_REJECTED, CountSheet.STATUS_DRAFT,
                        uw -> uw.set(CountSheet::getPendingFlag, 1)
                                .set(CountSheet::getWkUserId, userId)
                                .set(CountSheet::getUpdatedAt, now));
                if (!ok) {
                    throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
                }
            } catch (DataIntegrityViolationException e) {
                // 含 DuplicateKeyException 子类：pending_flag 回置撞 uk_cs_ws_pending
                throw new BizException(ErrorCode.STOCKTAKE_OPEN_EXISTS);
            }
        } else if (CountSheet.STATUS_DRAFT.equals(sheet.getStatus())) {
            countSheetMapper.update(null, new LambdaUpdateWrapper<CountSheet>()
                    .eq(CountSheet::getId, sheetId)
                    .eq(CountSheet::getStatus, CountSheet.STATUS_DRAFT)
                    .set(CountSheet::getWkUserId, userId)
                    .set(CountSheet::getUpdatedAt, now));
        } else {
            // 待审批/已通过不可编辑（矩阵红线统一 50330）
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }

        // items 全量替换 + 说明/照片覆盖
        countSheetItemMapper.delete(new LambdaQueryWrapper<CountSheetItem>()
                .eq(CountSheetItem::getSheetId, sheetId));
        for (CountSheetItem item : items) {
            item.setSheetId(sheetId);
            countSheetItemMapper.insert(item);
        }
        countSheetMapper.update(null, new LambdaUpdateWrapper<CountSheet>()
                .eq(CountSheet::getId, sheetId)
                .set(CountSheet::getRemark, trimToNull(dto.getRemark(), 512))
                .set(CountSheet::getAttachments, encodeAttachments(dto.getAttachments()))
                .set(CountSheet::getUpdatedAt, LocalDateTime.now()));

        log.info("[P3b][PD] WK {} 编辑盘点 doc={} items={}", userId, sheet.getDocNo(), items.size());
        return toVo(loadSheet(sheetId), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByWk(Long sheetId, Long userId) {
        CountSheet sheet = loadSheet(sheetId);
        requireWkRole(sheet.getTenantId(), userId);
        // 仅草稿可删（PRD §2.2；条件删除防并发提交后误删）
        int affected = countSheetMapper.delete(new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getId, sheetId)
                .eq(CountSheet::getStatus, CountSheet.STATUS_DRAFT));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        countSheetItemMapper.delete(new LambdaQueryWrapper<CountSheetItem>()
                .eq(CountSheetItem::getSheetId, sheetId));
        log.info("[P3b][PD] WK {} 删除盘点草稿 doc={}", userId, sheet.getDocNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CountSheetVo submitByWk(Long sheetId, Long userId) {
        CountSheet sheet = loadSheet(sheetId);
        requireWkRole(sheet.getTenantId(), userId);
        DocStateMachine.assertCanGo(DocKind.STOCKTAKE, sheet.getStatus(), CountSheet.STATUS_PENDING_APPROVAL);
        List<CountSheetItem> items = itemsOf(sheetId);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(countSheetMapper, DocKind.STOCKTAKE,
                sheetId, CountSheet::getId, CountSheet::getStatus,
                CountSheet.STATUS_DRAFT, CountSheet.STATUS_PENDING_APPROVAL,
                uw -> uw.set(CountSheet::getWkUserId, userId)
                        .set(CountSheet::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // 提交时刻 system_qty 快照定格（PRD §2.2-9：提交后的出库不改变差异值；
        // 生效量以审批时刻在库封顶——两时点语义分离，13 §7.3）
        for (CountSheetItem item : items) {
            int onhand = currentOnhand(sheet.getWholesalerId(), item.getSkuId());
            countSheetItemMapper.update(null, new LambdaUpdateWrapper<CountSheetItem>()
                    .eq(CountSheetItem::getId, item.getId())
                    .set(CountSheetItem::getSystemQty, onhand)
                    .set(CountSheetItem::getDiff, item.getActualQty() - onhand)
                    .set(CountSheetItem::getUpdatedAt, LocalDateTime.now()));
        }

        // 通知：提交 → 租户管理员（审批中心角标先例，getContactUserId）
        notificationService.send(sheet.getTenantId(), tenantService.getContactUserId(sheet.getTenantId()),
                Notification.TYPE_STOCKTAKE_PENDING, "新的盘点单待审批",
                "盘点单 " + sheet.getDocNo() + "（商户「" + wholesalerName(sheet.getWholesalerId())
                        + "」，" + items.size() + " 个商品）已提交，请在审批中心处理；"
                        + "盘亏将按审批时刻剩余在库封顶生效。",
                Notification.REF_STOCKTAKE, sheet.getId());

        log.info("[P3b][PD] WK {} 提交盘点 doc={} items={}", userId, sheet.getDocNo(), items.size());
        return toVo(loadSheet(sheetId), null, null, null);
    }

    // ==================== 查询 ====================

    @Override
    public List<CountSheetVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status) {
        requireWkOrTa(tenantId, userId);
        boolean pendingQueue = CountSheet.STATUS_PENDING_APPROVAL.equals(status);
        LambdaQueryWrapper<CountSheet> qw = new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getTenantId, tenantId)
                .eq(wholesalerId != null, CountSheet::getWholesalerId, wholesalerId)
                .eq(status != null && !status.isBlank(), CountSheet::getStatus, status);
        if (pendingQueue) {
            // 待审批队列创建升序（先到先审，idx_cs_tenant_status 覆盖）
            qw.orderByAsc(CountSheet::getCreatedAt);
        } else {
            qw.orderByDesc(CountSheet::getCreatedAt);
        }
        return countSheetMapper.selectList(qw).stream()
                .map(s -> toVo(s, null, null, null))
                .toList();
    }

    @Override
    public CountSheetVo getDetail(Long sheetId, Long userId) {
        CountSheet sheet = loadSheet(sheetId);
        requireWkOrTa(sheet.getTenantId(), userId);
        List<CountSheetItemVo> itemVos = itemsOf(sheetId).stream()
                .map(i -> toItemVo(i, sheet.getWholesalerId()))
                .toList();
        StocktakeInTransitHintVo hint = aggregateInTransit(sheet.getWholesalerId());
        return toVo(sheet, wholesalerName(sheet.getWholesalerId()), itemVos, hint);
    }

    @Override
    public StocktakeInTransitHintVo inTransitHint(Long tenantId, Long userId, Long wholesalerId) {
        requireWkOrTa(tenantId, userId);
        if (wholesalerId == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少商户");
        }
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null || !tenantId.equals(wholesaler.getTenantId())) {
            // 跨租户按不存在（不泄漏存在性）
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        return aggregateInTransit(wholesalerId);
    }

    // ==================== TA：审批 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CountSheetVo decideByTa(Long sheetId, CountSheetDecideDto dto, Long userId) {
        if (dto == null || dto.getConclusion() == null
                || !(CountSheet.STATUS_APPROVED.equals(dto.getConclusion())
                        || CountSheet.STATUS_REJECTED.equals(dto.getConclusion()))) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审批结论非法");
        }
        boolean approved = CountSheet.STATUS_APPROVED.equals(dto.getConclusion());
        String remark = dto.getRemark() != null ? dto.getRemark().trim() : null;
        if (!approved && (remark == null || remark.isEmpty())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "驳回须填写理由");
        }
        CountSheet sheet = loadSheet(sheetId);
        requireTaRole(sheet.getTenantId(), userId);
        DocStateMachine.assertCanGo(DocKind.STOCKTAKE, sheet.getStatus(), dto.getConclusion());

        // 并发双裁 CAS 决出唯一赢家（败方 50331）；pending_flag 置 NULL 释放唯一位
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(countSheetMapper, DocKind.STOCKTAKE,
                sheetId, CountSheet::getId, CountSheet::getStatus,
                CountSheet.STATUS_PENDING_APPROVAL, dto.getConclusion(),
                uw -> uw.set(CountSheet::getPendingFlag, null)
                        .set(CountSheet::getTaUserId, userId)
                        .set(CountSheet::getDecidedAt, now)
                        .set(!approved, CountSheet::getRejectRemark, remark)
                        .set(CountSheet::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        String resultBrief;
        if (approved) {
            resultBrief = applyApproved(sheet, userId);
        } else {
            // 驳回不动账：保留记录零库存零流水（WK 可编辑重提）
            resultBrief = "已驳回：" + remark + "（记录保留，可修改后重新提交）";
        }

        // 结论 → 发起库管（单人，纠错结论先例）
        notificationService.send(sheet.getTenantId(), sheet.getWkUserId(),
                Notification.TYPE_STOCKTAKE_DECIDED, "盘点单已有审批结论",
                "盘点单 " + sheet.getDocNo() + " " + resultBrief,
                Notification.REF_STOCKTAKE, sheet.getId());

        log.info("[P3b][PD] TA {} 审批盘点 doc={} conclusion={}", userId, sheet.getDocNo(), dto.getConclusion());
        return toVo(loadSheet(sheetId), null, null, null);
    }

    /**
     * 审批通过事务体（13 §2.2）：逐 SKU（skuId 升序防死锁）锁内 GAIN/LOSS。
     * 盘亏 D-10：applied 以审批时刻锁内 onhand 封顶；差额写明细 remark + 单据 remark 汇总
     * + 站内信 TA/WK（PRD §2.2-5 文案）。托盘：盘盈 +M、盘亏 −释放（明细 pallet_delta
     * 输入=覆盖值/NULL=默认比例；回写生效带符号值，RTN 回写先例）。
     *
     * @return 结论通知摘要
     */
    private String applyApproved(CountSheet sheet, Long taUserId) {
        List<CountSheetItem> items = itemsOf(sheet.getId()).stream()
                .sorted(Comparator.comparing(CountSheetItem::getSkuId))
                .toList();
        int gainTotal = 0;
        int lossTotal = 0;
        List<String> shortfallNotes = new ArrayList<>();
        for (CountSheetItem item : items) {
            int diff = item.getDiff();
            int appliedDiff;
            Integer effectivePallet;
            String appendRemark = null;
            if (diff > 0) {
                int palletGain = item.getPalletDelta() != null ? item.getPalletDelta() : 0;
                inventoryService.gainStock(GainStockContext.builder()
                        .wholesalerId(sheet.getWholesalerId())
                        .tenantId(sheet.getTenantId())
                        .skuId(item.getSkuId())
                        .qty(diff)
                        .palletDelta(palletGain)
                        .refDocNo(sheet.getDocNo())
                        .operatorUserId(taUserId)
                        .build());
                appliedDiff = diff;
                effectivePallet = palletGain;
                gainTotal += diff;
            } else if (diff < 0) {
                LossStockResult result = inventoryService.lossStock(LossStockContext.builder()
                        .wholesalerId(sheet.getWholesalerId())
                        .tenantId(sheet.getTenantId())
                        .skuId(item.getSkuId())
                        .qty(-diff)
                        .palletReleaseOverride(item.getPalletDelta())
                        .refDocNo(sheet.getDocNo())
                        .operatorUserId(taUserId)
                        .build());
                appliedDiff = -result.getAppliedQty();
                effectivePallet = -result.getPalletReleased();
                lossTotal += result.getAppliedQty();
                if (result.getShortfallQty() > 0) {
                    // D-10 差额文案（PRD §2.2-5）：备注 + 通知定责，不驳回重盘
                    appendRemark = "盘亏 " + (-diff) + " 件，审批时在库仅 " + result.getAppliedQty()
                            + " 件，已按 " + result.getAppliedQty() + " 件生效，差额 "
                            + result.getShortfallQty() + " 件请线下核查";
                    shortfallNotes.add(skuName(item.getSkuId()) + " 差额 " + result.getShortfallQty() + " 件");
                }
            } else {
                // 无差异行：零流水留痕
                appliedDiff = 0;
                effectivePallet = 0;
            }
            String newRemark = appendCapped(item.getRemark(), appendRemark, 512);
            countSheetItemMapper.update(null, new LambdaUpdateWrapper<CountSheetItem>()
                    .eq(CountSheetItem::getId, item.getId())
                    .set(CountSheetItem::getAppliedDiff, appliedDiff)
                    .set(CountSheetItem::getPalletDelta, effectivePallet)
                    .set(newRemark != null, CountSheetItem::getRemark, newRemark)
                    .set(CountSheetItem::getUpdatedAt, LocalDateTime.now()));
        }

        if (!shortfallNotes.isEmpty()) {
            // 封顶差额自动写入盘点单备注（PRD §2.2-5）+ 站内信 TA/WK 双方
            String summary = "盘亏封顶差额：" + String.join("；", shortfallNotes);
            countSheetMapper.update(null, new LambdaUpdateWrapper<CountSheet>()
                    .eq(CountSheet::getId, sheet.getId())
                    .set(CountSheet::getRemark, appendCapped(sheet.getRemark(), summary, 512))
                    .set(CountSheet::getUpdatedAt, LocalDateTime.now()));
            String content = "盘点单 " + sheet.getDocNo() + " 审批通过时部分盘亏超出剩余在库，已按剩余在库封顶生效："
                    + String.join("；", shortfallNotes) + "。请线下核查差额归属。";
            notificationService.send(sheet.getTenantId(), tenantService.getContactUserId(sheet.getTenantId()),
                    Notification.TYPE_STOCKTAKE_DECIDED, "盘亏封顶差额提醒", content,
                    Notification.REF_STOCKTAKE, sheet.getId());
        }
        return "已通过：盘盈 " + gainTotal + " 件、盘亏 " + lossTotal + " 件已生效"
                + (shortfallNotes.isEmpty() ? "" : "（封顶差额：" + String.join("；", shortfallNotes) + "，请线下核查）")
                + "。";
    }

    @Override
    public long countOpenForWholesaler(Long wholesalerId) {
        // R13（13 §7.1 终版枚举）：DRAFT/PENDING_APPROVAL 在途；REJECTED/APPROVED 已结
        return countSheetMapper.selectCount(new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getWholesalerId, wholesalerId)
                .in(CountSheet::getStatus, CountSheet.STATUS_DRAFT, CountSheet.STATUS_PENDING_APPROVAL));
    }

    @Override
    public long countPendingApprovalForTenant(Long tenantId) {
        // TA 工作台「待审批盘点单」计数（P5-C，19 §3）；照抄 Clearance 同名出口
        Long cnt = countSheetMapper.selectCount(new LambdaQueryWrapper<CountSheet>()
                .eq(CountSheet::getTenantId, tenantId)
                .eq(CountSheet::getStatus, CountSheet.STATUS_PENDING_APPROVAL));
        return cnt != null ? cnt : 0;
    }

    // ==================== 私有 ====================

    /**
     * 明细校验（50355：空/重复 SKU/实物数<0）+ 构建实体（system_qty/diff 预填当刻账面——
     * 提交时会重新快照定格）。SKU 必须存在且归属被盘商户（跨商户按不存在）。
     */
    private List<CountSheetItem> validateAndBuildItems(List<CountSheetItemDto> dtos, Long wholesalerId,
                                                      Long tenantId, Long sheetId) {
        if (dtos == null || dtos.isEmpty()) {
            throw new BizException(ErrorCode.STOCKTAKE_ITEMS_INVALID);
        }
        if (dtos.size() > MAX_ITEMS) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "单张盘点单最多 " + MAX_ITEMS + " 行");
        }
        Set<Long> seen = new HashSet<>();
        List<CountSheetItem> items = new ArrayList<>(dtos.size());
        for (CountSheetItemDto dto : dtos) {
            if (dto == null || dto.getSkuId() == null || dto.getActualQty() == null
                    || dto.getActualQty() < 0 || !seen.add(dto.getSkuId())) {
                throw new BizException(ErrorCode.STOCKTAKE_ITEMS_INVALID);
            }
            if (dto.getPalletDelta() != null && dto.getPalletDelta() < 0) {
                throw new BizException(ErrorCode.STOCKTAKE_ITEMS_INVALID);
            }
            SkuVo sku = skuService.getById(dto.getSkuId());
            if (sku == null || !wholesalerId.equals(sku.getWholesalerId())) {
                throw new BizException(ErrorCode.SKU_NOT_FOUND);
            }
            int onhand = currentOnhand(wholesalerId, dto.getSkuId());
            CountSheetItem item = new CountSheetItem();
            item.setId(snowflakeIdUtil.nextId());
            item.setSheetId(sheetId);
            item.setTenantId(tenantId);
            item.setSkuId(dto.getSkuId());
            item.setSystemQty(onhand);
            item.setActualQty(dto.getActualQty());
            item.setDiff(dto.getActualQty() - onhand);
            item.setPalletDelta(dto.getPalletDelta());
            item.setRemark(trimToNull(dto.getRemark(), 512));
            items.add(item);
        }
        return items;
    }

    /** 在途提示条聚合（13 §2.2 护栏）：出库 PENDING_ACCEPT/PRINTED + 退货 ACCEPTED，按 SKU 分桶。 */
    private StocktakeInTransitHintVo aggregateInTransit(Long wholesalerId) {
        List<OutboundRequest> outbounds = outboundRequestMapper.selectList(
                new LambdaQueryWrapper<OutboundRequest>()
                        .eq(OutboundRequest::getWholesalerId, wholesalerId)
                        .in(OutboundRequest::getStatus,
                                OutboundRequest.STATUS_PENDING_ACCEPT, OutboundRequest.STATUS_PRINTED));
        List<ReturnRequest> returns = returnRequestMapper.selectList(
                new LambdaQueryWrapper<ReturnRequest>()
                        .eq(ReturnRequest::getWholesalerId, wholesalerId)
                        .eq(ReturnRequest::getStatus, ReturnRequest.STATUS_ACCEPTED));
        Map<String, Integer> skuOut = new LinkedHashMap<>();
        long outQty = 0;
        for (OutboundRequest o : outbounds) {
            skuOut.merge(String.valueOf(o.getSkuId()), o.getQty(), Integer::sum);
            outQty += o.getQty();
        }
        Map<String, Integer> skuRet = new LinkedHashMap<>();
        long retQty = 0;
        for (ReturnRequest r : returns) {
            skuRet.merge(String.valueOf(r.getSkuId()), r.getQty(), Integer::sum);
            retQty += r.getQty();
        }
        return StocktakeInTransitHintVo.builder()
                .outboundDocCount(outbounds.size())
                .outboundQtyTotal(outQty)
                .returnDocCount(returns.size())
                .returnQtyTotal(retQty)
                .skuOutboundQty(skuOut)
                .skuReturnQty(skuRet)
                .build();
    }

    /** S4：建/编/提/删须为该租户 WK（ReturnRequestServiceImpl.requireWkRole 同构）。 */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员可操作盘点单");
        }
    }

    /** S4：审批须为该租户 TA（01 §4.3：盘点审批=租户管理员，D23 全量审批）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅租户管理员可审批盘点单");
        }
    }

    /** 查看：WK 或 TA（requireWkOrTa 先例，OutboundRequestServiceImpl:619）。 */
    private void requireWkOrTa(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或租户管理员可查看盘点单");
        }
    }

    /** 取盘点单（TenantLine 兜底：跨租户不可见 → 按不存在拒绝）。 */
    private CountSheet loadSheet(Long sheetId) {
        CountSheet sheet = sheetId != null ? countSheetMapper.selectById(sheetId) : null;
        if (sheet == null) {
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND, "盘点单不存在");
        }
        return sheet;
    }

    private List<CountSheetItem> itemsOf(Long sheetId) {
        return countSheetItemMapper.selectList(new LambdaQueryWrapper<CountSheetItem>()
                .eq(CountSheetItem::getSheetId, sheetId)
                .orderByAsc(CountSheetItem::getId));
    }

    /** 当刻账面（只读快照无锁；无库存行=0——账面 0 也可盘，实物>0 即盘盈）。 */
    private int currentOnhand(Long wholesalerId, Long skuId) {
        List<InventoryVo> list = inventoryService.queryInventory(wholesalerId, skuId);
        if (list.isEmpty() || list.get(0).getQty() == null) {
            return 0;
        }
        return list.get(0).getQty();
    }

    /** 明细 VO（详情链路）：附当刻在库与盘亏默认释放托盘建议值（封顶实时预览，13 §2.4-2 公式）。 */
    private CountSheetItemVo toItemVo(CountSheetItem item, Long wholesalerId) {
        Integer currentStock = null;
        Integer suggested = null;
        List<InventoryVo> list = inventoryService.queryInventory(wholesalerId, item.getSkuId());
        if (!list.isEmpty()) {
            InventoryVo inv = list.get(0);
            int qty = inv.getQty() != null ? Math.max(inv.getQty(), 0) : 0;
            int pallet = inv.getPalletQty() != null ? Math.max(inv.getPalletQty(), 0) : 0;
            currentStock = qty;
            if (item.getDiff() != null && item.getDiff() < 0) {
                int applied = Math.min(-item.getDiff(), qty);
                if (applied == 0 || pallet == 0) {
                    suggested = 0;
                } else if (applied >= qty) {
                    suggested = pallet; // 全出清零：默认释放全部
                } else {
                    suggested = Math.min((int) Math.ceil(pallet * (double) applied / qty), pallet);
                }
            }
        } else {
            currentStock = 0;
            if (item.getDiff() != null && item.getDiff() < 0) {
                suggested = 0;
            }
        }
        return CountSheetItemVo.builder()
                .id(item.getId())
                .skuId(item.getSkuId())
                .skuName(skuName(item.getSkuId()))
                .systemQty(item.getSystemQty())
                .actualQty(item.getActualQty())
                .diff(item.getDiff())
                .appliedDiff(item.getAppliedDiff())
                .palletDelta(item.getPalletDelta())
                .remark(item.getRemark())
                .currentStock(currentStock)
                .suggestedPalletRelease(suggested)
                .build();
    }

    private String wholesalerName(Long wholesalerId) {
        WholesalerVo w = wholesalerService.getById(wholesalerId);
        return w != null ? w.getName() : String.valueOf(wholesalerId);
    }

    private String skuName(Long skuId) {
        SkuVo sku = skuService.getById(skuId);
        return sku != null ? sku.getName() : String.valueOf(skuId);
    }

    private String encodeAttachments(List<String> attachments) {
        if (attachments != null && attachments.size() > 5) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "现场照片最多 5 张");
        }
        return AttachmentUrls.encode(attachments);
    }

    /** 追加备注并按列宽截断（超宽保尾部追加内容——封顶差额信息优先于原说明）。 */
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
        return "T" + String.valueOf(tenantId);
    }

    private CountSheetVo toVo(CountSheet s, String wholesalerName,
                              List<CountSheetItemVo> items, StocktakeInTransitHintVo hint) {
        return CountSheetVo.builder()
                .id(s.getId())
                .docNo(s.getDocNo())
                .tenantId(s.getTenantId())
                .wholesalerId(s.getWholesalerId())
                .wholesalerName(wholesalerName)
                .status(s.getStatus())
                .wkUserId(s.getWkUserId())
                .taUserId(s.getTaUserId())
                .decidedAt(s.getDecidedAt())
                .rejectRemark(s.getRejectRemark())
                .remark(s.getRemark())
                .attachments(AttachmentUrls.decode(s.getAttachments()))
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .items(items)
                .inTransitHint(hint)
                .build();
    }
}
