package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.common.file.AttachmentUrls;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.InboundRejectDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.dto.InboundWithdrawDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundCorrection;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.InboundCorrectionMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.document.vo.InboundStockPreviewVo;
import com.cangchu.common.util.WePermissions;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.DisputeReversalResult;
import com.cangchu.inventory.dto.InboundDisputeContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单服务实现（phase-1 C1：WK 代建登记）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权（G-1.3）：operator 必须是该商户所属租户的 WK——以 user_roles 登录态推导为唯一可信来源
 *       （{@link #requireWkRole}：role=WK & tenant_id=商户租户 & ACTIVE，参考 A1 requireTaRole / A2 requireWaOrTa）。</li>
 *   <li>租户隔离（G-2.1/G-2.2）：tenantId 由 wholesaler 真实归属推导，<b>不取客户端</b>；
 *       wholesaler/sku 经 selectById 受 TenantLine 兜底过滤（跨租户不可见→拒绝），再显式校验 sku 属该 wholesaler。</li>
 *   <li>S2 校验（G-3.1）：qty>0、wholesalerId/skuId 必填（DTO @Valid + 本类防御性双层）。</li>
 *   <li>S6 唯一/单事务（G-5.1）：docNo 由 DocumentNumberService 生成 + doc_no 唯一索引兜底；
 *       「建单 + addStock 增库存/写流水」在<b>单事务</b>内，任一失败整体回滚，单据与库存严格同增。</li>
 * </ul>
 *
 * <p>事务说明：addStock 自身 @Transactional 会并入本方法事务（Propagation.REQUIRED），
 * 故登记失败时库存增量一并回滚，保证一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundRequestServiceImpl implements InboundRequestService {

    private final InboundRequestMapper inboundRequestMapper;
    // P3b T1-BE（13 §7.1）：R13 未结枚举含在途纠错 PENDING（同 document 域，直连 Mapper 合规）
    private final InboundCorrectionMapper inboundCorrectionMapper;
    // G-S1/G-S2 还债：他域数据只走对方 Service（不再直连 WholesalerMapper/SkuMapper/TenantMapper）
    private final SkuService skuService;
    private final TenantService tenantService;
    // G-S1/G-S2 还债：user_roles 归 account 域，requireWkRole 经 AuthService 鉴权。
    private final AuthService authService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    // P3 BE-W1（12 §2）：异议冲销→仲裁单同事务编排 + 站内信
    private final ArbitrationService arbitrationService;
    private final NotificationService notificationService;
    // P3 BE-W2（12 §1.1）：R14 商户 ACTIVE 前置钩子（50313）
    private final com.cangchu.document.statemachine.DocPreconditions docPreconditions;

    /** 代建入库 WA 确认窗口（12 §2.2：72h，登记时显式落列） */
    private static final int WA_CONFIRM_WINDOW_HOURS = 72;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo registerByWk(InboundRegisterDto dto, Long wkUserId) {
        // S2：必填 + 数量
        if (dto.getWholesalerId() == null) {
            throw new BizException(ErrorCode.INBOUND_WHOLESALER_REQUIRED);
        }
        if (dto.getSkuId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少商品 SKU");
        }
        if (dto.getQty() == null || dto.getQty() <= 0) {
            throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
        }
        int palletQty = dto.getPalletQty() != null ? dto.getPalletQty() : 0;
        if (palletQty < 0) {
            throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
        }

        // R14 前置钩子（P3 BE-W2 补齐，12 §1.1：代建入库/代建出库/WA 手动出库三处复用）：
        // 商户须存在且 ACTIVE（OFFLINE/WITHDRAWN → 50313 拒新业务），TenantLine 兜底跨租户不可见
        WholesalerVo wholesaler = docPreconditions.requireWholesalerActive(dto.getWholesalerId());
        // tenantId 由 wholesaler 真实归属推导（不取客户端），后续所有落库以此为准
        Long tenantId = wholesaler.getTenantId();

        // S4：operator 必须是该租户 WK
        requireWkRole(tenantId, wkUserId);

        // 校验 sku 存在、属该 wholesaler（且同租户，经 SkuService，内部同受 TenantLine 兜底）
        SkuVo sku = skuService.getById(dto.getSkuId());
        if (sku == null || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }

        // 生成单据号（DocumentNumberService，C2 复用）
        String docNo = documentNumberService.generate(DocType.INBOUND, resolveSimpleCode(tenantId));

        // 建入库单（P3 12 §2.2：PENDING_WA_CONFIRM + 72h deadline 显式落列，可售不冻结）
        LocalDateTime now = LocalDateTime.now();
        InboundRequest req = new InboundRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(docNo);
        req.setWholesalerId(dto.getWholesalerId());
        req.setTenantId(tenantId);
        req.setSkuId(dto.getSkuId());
        req.setQty(dto.getQty());
        req.setPalletQty(palletQty);
        req.setStatus(InboundRequest.STATUS_PENDING_WA_CONFIRM);
        req.setSource(InboundRequest.SOURCE_WK_CREATED);
        // created_at 由 MetaObjectHandler 以同一 now 语义填充；deadline 显式落列（队列排序/Job SQL 比较不依赖应用时钟）
        req.setCreatedAt(now);
        req.setWaConfirmDeadline(now.plusHours(WA_CONFIRM_WINDOW_HOURS));
        req.setAutoAccepted(0);
        req.setWkUserId(wkUserId);
        try {
            inboundRequestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            // doc_no 唯一约束冲突（极端并发）→ 语义化，不暴露 DB 异常
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }

        // 单事务内增库存 + 写 INBOUND 流水（refDocNo=docNo, operatorUserId=wkUserId）
        InventoryVo inv = inventoryService.addStock(InboundContext.builder()
                .wholesalerId(dto.getWholesalerId())
                .tenantId(tenantId)
                .skuId(dto.getSkuId())
                .qty(dto.getQty())
                .palletQty(palletQty)
                .refDocNo(docNo)
                .operatorUserId(wkUserId)
                .build());

        // 同事务通知归属 WA（12 §2.2；产品口径文案：可售 + 72h 异议 + 差额定责，PRD 09 §6.1）
        // P3 缺陷修复：收件人以 user_roles 推导（SELF_OPERATED 的 owner_user_id 是 TA 操作人，
        // 按 owner 发会漏发真实 WA；listForWa 同源先例），多 WA 账号全发
        notificationService.sendToAll(tenantId, authService.listActiveWaUserIdsOfWholesaler(dto.getWholesalerId()),
                Notification.TYPE_INBOUND_PENDING_CONFIRM,
                "代建入库待确认",
                "入库单 " + docNo + "：登记 " + dto.getQty() + " 件，请在 72 小时内确认或提出异议，逾期自动确认。"
                        + "代建入库即视为可售，异议仅覆盖仍在库部分；已售出部分将进入差额定责。",
                Notification.REF_INBOUND, req.getId());

        log.info("[C1] WK {} 登记入库 doc={} wholesaler={} sku={} +{} -> 库存={}",
                wkUserId, docNo, dto.getWholesalerId(), dto.getSkuId(), dto.getQty(), inv.getQty());

        return toVo(req, inv.getQty());
    }

    // ==================== P3 BE-W1：WA 确认 / 异议 / 队列 / 72h 自动确认（12 §2） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo confirmByWa(Long inboundId, Long userId) {
        InboundRequest req = loadInbound(inboundId);
        requireWaOrAuthorizedWe(req.getWholesalerId(), userId);

        // CAS：PENDING_WA_CONFIRM → CONFIRMED（与 72h Job / 异议竞态由 CAS 决出唯一赢家）
        LocalDateTime now = LocalDateTime.now();
        int affected = inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .eq(InboundRequest::getStatus, InboundRequest.STATUS_PENDING_WA_CONFIRM)
                .set(InboundRequest::getStatus, InboundRequest.STATUS_CONFIRMED)
                .set(InboundRequest::getWaConfirmAt, now)
                .set(InboundRequest::getAutoAccepted, 0)
                .set(InboundRequest::getUpdatedAt, now));
        if (affected != 1) {
            throw casConflict(inboundId);
        }
        log.info("[P3] WA {} 确认入库 doc={}", userId, req.getDocNo());
        return toVo(inboundRequestMapper.selectById(inboundId), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundDisputeResultVo disputeByWa(Long inboundId, Long userId, InboundDisputeDto dto) {
        // S2 防御性双层（DTO @Valid 之外）：reason 必填 ≤512、附件 ≤5
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少异议理由");
        }
        if (dto.getReason().length() > 512 || (dto.getAttachments() != null && dto.getAttachments().size() > 5)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        InboundRequest req = loadInbound(inboundId);
        String initiatorRole = requireWaOrAuthorizedWe(req.getWholesalerId(), userId);

        // 1) CAS：PENDING_WA_CONFIRM → DISPUTED（防重复异议/与自动确认竞态，12 §2.3-1）
        LocalDateTime now = LocalDateTime.now();
        int affected = inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .eq(InboundRequest::getStatus, InboundRequest.STATUS_PENDING_WA_CONFIRM)
                .set(InboundRequest::getStatus, InboundRequest.STATUS_DISPUTED)
                .set(InboundRequest::getDisputedAt, now)
                .set(InboundRequest::getUpdatedAt, now));
        if (affected != 1) {
            throw casConflict(inboundId);
        }

        // 2) 封顶冲销（12 §2.4：锁内 min(登记量, 当前在库)，售罄 0 冲销不写流水）
        DisputeReversalResult reversal = inventoryService.reverseInboundForDispute(InboundDisputeContext.builder()
                .wholesalerId(req.getWholesalerId())
                .tenantId(req.getTenantId())
                .skuId(req.getSkuId())
                .registeredQty(req.getQty())
                .palletQty(req.getPalletQty())
                .refDocNo(req.getDocNo())
                .operatorUserId(userId)
                .build());

        // 3) 建 YY- 仲裁单（差额 shortfall_qty 快照，落单后不可变）
        Arbitration arb = arbitrationService.createInboundDispute(req, userId, initiatorRole,
                dto.getReason(), dto.getAttachments(), reversal.getReversedQty(), reversal.getShortfallQty());

        // 4) 通知 TA（审批中心角标）+ WK（同事务）
        String brief = "仲裁单 " + arb.getDocNo() + "：入库单 " + req.getDocNo()
                + " 被提出异议（已冲销 " + reversal.getReversedQty() + " 件，差额 " + reversal.getShortfallQty() + " 件）。";
        notificationService.send(req.getTenantId(), tenantService.getContactUserId(req.getTenantId()),
                Notification.TYPE_DISPUTE_CREATED, "新的入库异议待仲裁",
                brief + "请在审批中心处理。", Notification.REF_ARBITRATION, arb.getId());
        notificationService.send(req.getTenantId(), req.getWkUserId(),
                Notification.TYPE_DISPUTE_CREATED, "代建入库被提出异议",
                brief, Notification.REF_ARBITRATION, arb.getId());

        log.info("[P3] WA {} 异议入库 doc={} reversed={} shortfall={} arb={}",
                userId, req.getDocNo(), reversal.getReversedQty(), reversal.getShortfallQty(), arb.getDocNo());
        return InboundDisputeResultVo.builder()
                .inboundId(req.getId())
                .docNo(req.getDocNo())
                .status(InboundRequest.STATUS_DISPUTED)
                .registeredQty(req.getQty())
                .reversedQty(reversal.getReversedQty())
                .shortfallQty(reversal.getShortfallQty())
                .arbitrationId(arb.getId())
                .arbitrationDocNo(arb.getDocNo())
                .build();
    }

    @Override
    public Page<InboundRequestVo> listForWa(Long userId, String status, String source, int page, int size) {
        // 「我管的商户」= WA 绑定 ∪ WE 绑定（WE 队列只读可见；confirm/dispute/withdraw 时再校验授权位）
        java.util.LinkedHashSet<Long> wholesalerIds = new java.util.LinkedHashSet<>();
        wholesalerIds.addAll(authService.listActiveWholesalerIds(userId, "WA"));
        wholesalerIds.addAll(authService.listActiveWeWholesalerIds(userId));
        Page<InboundRequestVo> empty = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100), 0);
        if (wholesalerIds.isEmpty()) {
            return empty;
        }
        boolean pendingQueue = InboundRequest.STATUS_PENDING_WA_CONFIRM.equals(status);
        LambdaQueryWrapper<InboundRequest> qw = new LambdaQueryWrapper<InboundRequest>()
                .in(InboundRequest::getWholesalerId, wholesalerIds)
                .eq(status != null && !status.isBlank(), InboundRequest::getStatus, status)
                // P3b T1（13 §5.1）：source 过滤（WA_SUBMIT=我的申请 / WK_CREATED=代建）
                .eq(source != null && !source.isBlank(), InboundRequest::getSource, source);
        if (pendingQueue) {
            // 待确认队列按倒计时升序（12 §2.2）
            qw.orderByAsc(InboundRequest::getWaConfirmDeadline);
        } else {
            qw.orderByDesc(InboundRequest::getCreatedAt);
        }
        Page<InboundRequest> p = inboundRequestMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), qw);
        Page<InboundRequestVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(r -> toVo(r, null)).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoConfirmExpired() {
        // 12 §2.5：时间比较在 SQL 内用数据库时间（BND-S3-01）；Job 无 TenantContext → 全平台扫描（先例）
        List<InboundRequest> expired = inboundRequestMapper.selectList(new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getStatus, InboundRequest.STATUS_PENDING_WA_CONFIRM)
                .isNotNull(InboundRequest::getWaConfirmDeadline)
                .apply("wa_confirm_deadline <= NOW()"));
        int confirmed = 0;
        for (InboundRequest req : expired) {
            // 逐行 CAS（而非批量 UPDATE）：每行成功后要写 WA 通知；与手动操作竞态由 CAS 决出唯一赢家
            int affected = inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                    .eq(InboundRequest::getId, req.getId())
                    .eq(InboundRequest::getStatus, InboundRequest.STATUS_PENDING_WA_CONFIRM)
                    .set(InboundRequest::getStatus, InboundRequest.STATUS_CONFIRMED)
                    .set(InboundRequest::getAutoAccepted, 1)
                    .setSql("wa_confirm_at = NOW()")
                    .set(InboundRequest::getUpdatedAt, LocalDateTime.now()));
            if (affected == 1) {
                // P3 缺陷修复：同 registerByWk——「归属 WA」收件人以 user_roles 推导，多账号全发
                notificationService.sendToAll(req.getTenantId(),
                        authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                        Notification.TYPE_INBOUND_AUTO_CONFIRMED, "入库单已自动确认",
                        "入库单 " + req.getDocNo() + " 超过 72 小时未处理，已自动确认。",
                        Notification.REF_INBOUND, req.getId());
                confirmed++;
            }
        }
        if (confirmed > 0) {
            log.info("[P3][72h Job] 本次自动确认 {} 单（扫描 {} 单）", confirmed, expired.size());
        }
        return confirmed;
    }

    @Override
    public List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId) {
        return listByTenant(tenantId, wholesalerId, null);
    }

    @Override
    public List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId, String status) {
        boolean submittedQueue = InboundRequest.STATUS_SUBMITTED.equals(status);
        LambdaQueryWrapper<InboundRequest> qw = new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getTenantId, tenantId)
                .eq(wholesalerId != null, InboundRequest::getWholesalerId, wholesalerId)
                .eq(status != null && !status.isBlank(), InboundRequest::getStatus, status);
        if (submittedQueue) {
            // P3b T1：WK 待受理队列按创建时间升序（先到先受理；idx_inb_tenant_status 覆盖）
            qw.orderByAsc(InboundRequest::getCreatedAt);
        } else {
            qw.orderByDesc(InboundRequest::getCreatedAt);
        }
        return inboundRequestMapper.selectList(qw).stream()
                .map(r -> toVo(r, null))
                .toList();
    }

    @Override
    public InboundStockPreviewVo stockPreview(Long inboundId, Long userId) {
        InboundRequest req = loadInbound(inboundId);
        requireWaOrAuthorizedWe(req.getWholesalerId(), userId);
        // 轻量只读快照（无锁，允许轻微过期；实际冲销以 disputeByWa 锁内计算为准）
        List<InventoryVo> list = inventoryService.queryInventory(req.getWholesalerId(), req.getSkuId());
        int onhand = list.isEmpty() || list.get(0).getQty() == null ? 0 : Math.max(list.get(0).getQty(), 0);
        int expectedReversal = Math.min(req.getQty(), onhand);
        int expectedShortfall = req.getQty() - expectedReversal;
        return InboundStockPreviewVo.builder()
                .onhand(onhand)
                .expectedReversal(expectedReversal)
                .expectedShortfall(expectedShortfall)
                .build();
    }

    @Override
    public long countOpenForWholesaler(Long wholesalerId) {
        // R13（12 §8.2 + 13 §7.1 扩）：待确认/争议中/待受理/已受理未登记 均未结；
        // CONFIRMED/REVOKED/WITHDRAWN/REJECTED 已结
        long openDocs = inboundRequestMapper.selectCount(new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getWholesalerId, wholesalerId)
                .in(InboundRequest::getStatus,
                        InboundRequest.STATUS_PENDING_WA_CONFIRM, InboundRequest.STATUS_DISPUTED,
                        InboundRequest.STATUS_SUBMITTED, InboundRequest.STATUS_ACCEPTED));
        // 13 §7.1 未结枚举终版：在途纠错 PENDING 同样阻退驻（对象是该商户库存）
        long openCorrections = inboundCorrectionMapper.selectCount(new LambdaQueryWrapper<InboundCorrection>()
                .eq(InboundCorrection::getWholesalerId, wholesalerId)
                .eq(InboundCorrection::getStatus, InboundCorrection.STATUS_PENDING));
        return openDocs + openCorrections;
    }

    // ==================== P3b T1 正向申请链（13 §1/§5.1） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<InboundRequestVo> submitByWa(InboundSubmitDto dto, Long userId) {
        // S2 防御性双层（DTO @Valid 之外）
        if (dto == null || dto.getWholesalerId() == null) {
            throw new BizException(ErrorCode.INBOUND_WHOLESALER_REQUIRED);
        }
        if (dto.getItems() == null || dto.getItems().isEmpty() || dto.getItems().size() > 50) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "申请明细为空或超上限");
        }
        // R14 前置钩子（第 4 处复用，13 §1.4）：商户须 ACTIVE；tenantId 由真实归属推导（S4）
        WholesalerVo wholesaler = docPreconditions.requireWholesalerActive(dto.getWholesalerId());
        Long tenantId = wholesaler.getTenantId();
        // S4：WA 本人或持 INBOUND_SUBMIT 授权位的 WE（D-5；未授权 WE → 42004）
        requireWaOrSubmitWe(dto.getWholesalerId(), userId);

        // D-5 多行拆单：单事务建 N 张单，任一行非法整批回滚（原子性）；共享 batch_submit_id
        long batchSubmitId = snowflakeIdUtil.nextId();
        LocalDateTime now = LocalDateTime.now();
        List<InboundRequest> created = new java.util.ArrayList<>(dto.getItems().size());
        int totalQty = 0;
        for (InboundSubmitDto.Item item : dto.getItems()) {
            if (item.getQty() == null || item.getQty() <= 0) {
                throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
            }
            int palletQty = item.getPalletQty() != null ? item.getPalletQty() : 0;
            if (palletQty < 0) {
                throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
            }
            SkuVo sku = skuService.getById(item.getSkuId());
            if (sku == null || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
                throw new BizException(ErrorCode.SKU_NOT_FOUND);
            }
            InboundRequest req = new InboundRequest();
            req.setId(snowflakeIdUtil.nextId());
            req.setDocNo(documentNumberService.generate(DocType.INBOUND, resolveSimpleCode(tenantId)));
            req.setWholesalerId(dto.getWholesalerId());
            req.setTenantId(tenantId);
            req.setSkuId(item.getSkuId());
            // requested_qty=申请件数（落值后不可变）；qty 同步落值（登记时覆写为实登）
            req.setQty(item.getQty());
            req.setRequestedQty(item.getQty());
            req.setPalletQty(palletQty);
            // 13 §7.1：显式落 SUBMITTED（不依赖 V16 列默认 PENDING_WA_CONFIRM）
            req.setStatus(InboundRequest.STATUS_SUBMITTED);
            req.setSource(InboundRequest.SOURCE_WA_SUBMIT);
            // 正向链 wa_confirm_deadline 恒 NULL → 72h Job 扫描条件天然不命中（10 §1.2-7 零改动）
            req.setWaConfirmDeadline(null);
            req.setAutoAccepted(0);
            req.setBatchSubmitId(batchSubmitId);
            req.setWaUserId(userId);
            req.setRemark(trimToNull(item.getRemark()));
            req.setPrintCount(0);
            req.setCreatedAt(now);
            try {
                inboundRequestMapper.insert(req);
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
            }
            created.add(req);
            totalQty += item.getQty();
        }

        // 同事务通知库管（收件人以 user_roles 推导，多账号全发；文案零角色码）
        InboundRequest first = created.get(0);
        notificationService.sendToAll(tenantId, authService.listActiveWkUserIdsOfTenant(tenantId),
                Notification.TYPE_INBOUND_SUBMITTED, "新的入库申请待受理",
                "商户「" + wholesaler.getName() + "」提交 " + created.size() + " 张入库申请（合计 " + totalQty
                        + " 件），首单 " + first.getDocNo() + "，请及时受理。",
                Notification.REF_INBOUND, first.getId());

        log.info("[P3b] user {} 提交入库申请 {} 张 wholesaler={} batchSubmitId={}",
                userId, created.size(), dto.getWholesalerId(), batchSubmitId);
        return created.stream().map(r -> toVo(r, null)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo withdrawByWa(Long inboundId, InboundWithdrawDto dto, Long userId) {
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少撤回理由");
        }
        InboundRequest req = loadInbound(inboundId);
        if (!InboundRequest.SOURCE_WA_SUBMIT.equals(req.getSource())) {
            // 代建链无撤回入口（异议走 dispute）
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        String role = requireWaOrSubmitWe(req.getWholesalerId(), userId);
        if ("WE".equals(role) && !userId.equals(req.getWaUserId())) {
            // D-5 授权位语义：WE 仅可撤回自己提交的申请
            throw new BizException(ErrorCode.PERMISSION_ROLE_004, "仅可撤回自己提交的申请");
        }
        // R1：仅 SUBMITTED 可撤（受理锁单后 50350，须走库管流转）
        if (!InboundRequest.STATUS_SUBMITTED.equals(req.getStatus())) {
            throw new BizException(ErrorCode.INBOUND_NOT_WITHDRAWABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(inboundRequestMapper, DocStateMachine.DocKind.INBOUND,
                inboundId, InboundRequest::getId, InboundRequest::getStatus,
                InboundRequest.STATUS_SUBMITTED, InboundRequest.STATUS_WITHDRAWN,
                uw -> uw.set(InboundRequest::getWithdrawReason, dto.getReason().trim())
                        .set(InboundRequest::getUpdatedAt, now));
        if (!ok) {
            // 并发被受理/驳回抢先：重读语义化——已非 SUBMITTED → 50350，其余 50331
            InboundRequest cur = inboundRequestMapper.selectById(inboundId);
            if (cur != null && !InboundRequest.STATUS_SUBMITTED.equals(cur.getStatus())) {
                throw new BizException(ErrorCode.INBOUND_NOT_WITHDRAWABLE);
            }
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        log.info("[P3b][R1] user {} 撤回入库申请 doc={}", userId, req.getDocNo());
        return toVo(inboundRequestMapper.selectById(inboundId), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo acceptByWk(Long inboundId, Long userId) {
        InboundRequest req = loadInbound(inboundId);
        requireWkRole(req.getTenantId(), userId);
        // R14（13 §1.4）：商户非 ACTIVE 时存量 SUBMITTED 单受理同样拒绝（50313，
        // 货未入仓不属「保护客户权益」，与出库老单放行不同）
        docPreconditions.requireWholesalerActive(req.getWholesalerId());
        // 不可达（REJECTED→ACCEPTED / 代建链等）由矩阵统一 50330
        DocStateMachine.assertCanGo(DocStateMachine.DocKind.INBOUND,
                req.getStatus(), InboundRequest.STATUS_ACCEPTED);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(inboundRequestMapper, DocStateMachine.DocKind.INBOUND,
                inboundId, InboundRequest::getId, InboundRequest::getStatus,
                InboundRequest.STATUS_SUBMITTED, InboundRequest.STATUS_ACCEPTED,
                uw -> uw.set(InboundRequest::getWkUserId, userId)
                        .set(InboundRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // 同事务通知商户（受理；收件人 user_roles 推导，多账号全发）
        notificationService.sendToAll(req.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                Notification.TYPE_INBOUND_ACCEPTED, "入库申请已受理",
                "入库申请 " + req.getDocNo() + " 已受理，请按约定送货入仓；登记入库后库存与计费才生效。",
                Notification.REF_INBOUND, req.getId());
        log.info("[P3b] WK {} 受理入库申请 doc={}", userId, req.getDocNo());
        return toVo(inboundRequestMapper.selectById(inboundId), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo rejectByWk(Long inboundId, InboundRejectDto dto, Long userId) {
        // S2 防御性双层：reason 单选白名单 + remark 必填 + 附件 ≤5
        if (dto == null || dto.getReason() == null
                || !java.util.Set.of("QTY", "QUALITY", "BATCH", "OTHER").contains(dto.getReason())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "驳回原因非法");
        }
        if (dto.getRemark() == null || dto.getRemark().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少驳回备注");
        }
        if (dto.getRemark().length() > 512
                || (dto.getAttachments() != null && dto.getAttachments().size() > 5)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        InboundRequest req = loadInbound(inboundId);
        requireWkRole(req.getTenantId(), userId);
        DocStateMachine.assertCanGo(DocStateMachine.DocKind.INBOUND,
                req.getStatus(), InboundRequest.STATUS_REJECTED);
        // R2 举证附件（N2 白名单校验，独立 reject_attachments 列）
        String rejectAttachments = AttachmentUrls.encode(dto.getAttachments());
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(inboundRequestMapper, DocStateMachine.DocKind.INBOUND,
                inboundId, InboundRequest::getId, InboundRequest::getStatus,
                InboundRequest.STATUS_SUBMITTED, InboundRequest.STATUS_REJECTED,
                uw -> uw.set(InboundRequest::getRejectReason, dto.getReason())
                        .set(InboundRequest::getRejectRemark, dto.getRemark().trim())
                        .set(rejectAttachments != null, InboundRequest::getRejectAttachments, rejectAttachments)
                        .set(InboundRequest::getWkUserId, userId)
                        .set(InboundRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // 同事务通知商户（驳回；前端可一键复制重建）
        notificationService.sendToAll(req.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                Notification.TYPE_INBOUND_REJECTED, "入库申请被驳回",
                "入库申请 " + req.getDocNo() + " 被驳回：" + dto.getRemark().trim()
                        + "。可复制原申请修改后重新提交。",
                Notification.REF_INBOUND, req.getId());
        log.info("[P3b][R2] WK {} 驳回入库申请 doc={} reason={}", userId, req.getDocNo(), dto.getReason());
        return toVo(inboundRequestMapper.selectById(inboundId), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo registerForwardByWk(Long inboundId, InboundForwardRegisterDto dto, Long userId) {
        if (dto == null || dto.getActualQty() == null || dto.getActualQty() <= 0) {
            throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
        }
        int palletOverride = dto.getPalletQty() != null ? dto.getPalletQty() : -1;
        if (dto.getPalletQty() != null && dto.getPalletQty() < 0) {
            throw new BizException(ErrorCode.INBOUND_QTY_INVALID);
        }
        InboundRequest req = loadInbound(inboundId);
        requireWkRole(req.getTenantId(), userId);
        if (!InboundRequest.SOURCE_WA_SUBMIT.equals(req.getSource())) {
            // 本端点仅正向链（代建链登记走 registerByWk；防经此绕过 72h 链）
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
        // SUBMITTED→CONFIRMED 必须经受理（矩阵红线 50330）
        DocStateMachine.assertCanGo(DocStateMachine.DocKind.INBOUND,
                req.getStatus(), InboundRequest.STATUS_CONFIRMED);

        // T1-5 数量差异边界（13 §1.2）：|actual − requested| / requested ≤ 5%（整型算式，含等于放行）
        int requested = req.getRequestedQty() != null ? req.getRequestedQty() : req.getQty();
        int actual = dto.getActualQty();
        int diff = Math.abs(actual - requested);
        if (diff * 100L > requested * 5L) {
            throw new BizException(ErrorCode.INBOUND_QTY_DIFF_EXCEEDED);
        }
        String remark = trimToNull(dto.getRemark());
        if (diff != 0 && remark == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "实登与申请件数存在差异，须填写差异备注");
        }
        // D-2 最小版：登记照片 ≤5 挂单据（N2 白名单）
        if (dto.getAttachments() != null && dto.getAttachments().size() > 5) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        String attachments = AttachmentUrls.encode(dto.getAttachments());

        int palletQty = palletOverride >= 0 ? palletOverride
                : (req.getPalletQty() != null ? req.getPalletQty() : 0);
        // 登记事务口径（13 §1.1）：CAS ACCEPTED→CONFIRMED（qty=实登、registered_at=now、附件）
        // → addStock（INBOUND 流水，biz_time=登记时刻，计费次日起算）→ 通知商户。
        // wa_confirm_deadline 恒 NULL → 72h Job 天然不命中；D-3 直落 CONFIRMED 不复活 REGISTERED。
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(inboundRequestMapper, DocStateMachine.DocKind.INBOUND,
                inboundId, InboundRequest::getId, InboundRequest::getStatus,
                InboundRequest.STATUS_ACCEPTED, InboundRequest.STATUS_CONFIRMED,
                uw -> uw.set(InboundRequest::getQty, actual)
                        .set(InboundRequest::getPalletQty, palletQty)
                        .set(InboundRequest::getRegisteredAt, now)
                        .set(attachments != null, InboundRequest::getAttachments, attachments)
                        .set(remark != null, InboundRequest::getRemark, remark)
                        .set(InboundRequest::getWkUserId, userId)
                        .set(InboundRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        // 此刻才 addStock（库存时点不对称是有意设计：出库=创建即扣，入库=登记才加，10 §1.2-1）
        InventoryVo inv = inventoryService.addStock(InboundContext.builder()
                .wholesalerId(req.getWholesalerId())
                .tenantId(req.getTenantId())
                .skuId(req.getSkuId())
                .qty(actual)
                .palletQty(palletQty)
                .refDocNo(req.getDocNo())
                .operatorUserId(userId)
                .build());

        // 同事务通知商户（登记完成；UI 文案统一「已入库」，正向链不出现「已确认」话术）
        notificationService.sendToAll(req.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                Notification.TYPE_INBOUND_REGISTERED, "入库登记完成",
                "入库申请 " + req.getDocNo() + " 已登记入库 " + actual + " 件，库存已生效，仓储费次日起算。",
                Notification.REF_INBOUND, req.getId());

        log.info("[P3b] WK {} 登记正向入库 doc={} requested={} actual={} -> 库存={}",
                userId, req.getDocNo(), requested, actual, inv.getQty());
        return toVo(inboundRequestMapper.selectById(inboundId), inv.getQty());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundRequestVo printByWk(Long inboundId, Long userId) {
        InboundRequest req = loadInbound(inboundId);
        requireWkRole(req.getTenantId(), userId);
        // T1-7：非状态节点（与出库 PRINTED 不同），登记前后均可调；补打 count++
        inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .set(InboundRequest::getPrintedAt, LocalDateTime.now())
                .setSql("print_count = print_count + 1")
                .set(InboundRequest::getUpdatedAt, LocalDateTime.now()));
        return toVo(inboundRequestMapper.selectById(inboundId), null);
    }

    // ==================== 私有 ====================

    /**
     * S4 角色鉴权：校验用户在指定租户下具备有效 WK 角色，否则越权拒绝。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参
     * （写法对齐 A1 requireTaRole / A2 requireWaOrTa）。
     */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.INBOUND_OPERATOR_NOT_WK);
        }
    }

    /** 取入库单（TenantLine 兜底：跨租户不可见 → 按不存在拒绝）。 */
    private InboundRequest loadInbound(Long inboundId) {
        InboundRequest req = inboundId != null ? inboundRequestMapper.selectById(inboundId) : null;
        if (req == null) {
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND);
        }
        return req;
    }

    /**
     * S4（12 §2.3）：确认/异议操作人须为该 wholesaler 的 ACTIVE WA，或该商户持 INBOUND_CONFIRM
     * 授权位的 WE（G7，写法对齐 InquiryServiceImpl.requireWaRole 先例：未授权 WE → 42004）。
     *
     * @return 发起方角色（"WA"/"WE"，记入仲裁单 initiator_role）
     */
    private String requireWaOrAuthorizedWe(Long wholesalerId, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesalerId)) {
            return "WA";
        }
        if (authService.hasWholesalerRole(userId, "WE", wholesalerId)) {
            if (!authService.hasWholesalerPermission(userId, wholesalerId, WePermissions.INBOUND_CONFIRM)) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004, "未获得入库确认授权，请联系商户管理员");
            }
            return "WE";
        }
        throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅该批发商管理员或被授权员工可操作此入库单");
    }

    /**
     * S4（P3b T1，D-5）：提交/撤回操作人须为该 wholesaler 的 ACTIVE WA，或持 INBOUND_SUBMIT
     * 授权位的 WE（写法对齐 {@link #requireWaOrAuthorizedWe}：未授权 WE → 42004）。
     *
     * @return 操作方角色（"WA"/"WE"；WE 撤回时须为提交人本人）
     */
    private String requireWaOrSubmitWe(Long wholesalerId, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesalerId)) {
            return "WA";
        }
        if (authService.hasWholesalerRole(userId, "WE", wholesalerId)) {
            if (!authService.hasWholesalerPermission(userId, wholesalerId, WePermissions.INBOUND_SUBMIT)) {
                throw new BizException(ErrorCode.PERMISSION_ROLE_004, "未获得入库申请授权，请联系商户管理员");
            }
            return "WE";
        }
        throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅该批发商管理员或被授权员工可操作入库申请");
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * CAS 失败语义化（12 §2.3）：重读单据——已被 72h Job 自动确认 → 50332（窗口已关）；
     * 其余（已手动确认/已异议/并发被抢占）→ 50331。
     */
    private BizException casConflict(Long inboundId) {
        InboundRequest cur = inboundRequestMapper.selectById(inboundId);
        if (cur != null && InboundRequest.STATUS_CONFIRMED.equals(cur.getStatus())
                && cur.getAutoAccepted() != null && cur.getAutoAccepted() == 1) {
            return new BizException(ErrorCode.INBOUND_CONFIRM_WINDOW_CLOSED);
        }
        return new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
    }

    /** 取租户简码用于 docNo；查不到则用 tenantId 尾号占位。经 TenantService 取值（G-S2，不直连 TenantMapper）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + String.valueOf(tenantId);
    }

    private InboundRequestVo toVo(InboundRequest r, Integer currentStock) {
        return InboundRequestVo.builder()
                .id(r.getId())
                .docNo(r.getDocNo())
                .wholesalerId(r.getWholesalerId())
                .tenantId(r.getTenantId())
                .skuId(r.getSkuId())
                .qty(r.getQty())
                .palletQty(r.getPalletQty())
                .status(r.getStatus())
                .wkUserId(r.getWkUserId())
                .currentStock(currentStock)
                .source(r.getSource())
                .waConfirmDeadline(r.getWaConfirmDeadline())
                .waConfirmAt(r.getWaConfirmAt())
                .autoAccepted(r.getAutoAccepted())
                .disputedAt(r.getDisputedAt())
                .createdAt(r.getCreatedAt())
                // P3b T1 正向链字段
                .requestedQty(r.getRequestedQty())
                .rejectReason(r.getRejectReason())
                .rejectRemark(r.getRejectRemark())
                .rejectAttachments(AttachmentUrls.decode(r.getRejectAttachments()))
                .withdrawReason(r.getWithdrawReason())
                .attachments(AttachmentUrls.decode(r.getAttachments()))
                .printedAt(r.getPrintedAt())
                .printCount(r.getPrintCount())
                .registeredAt(r.getRegisteredAt())
                .batchSubmitId(r.getBatchSubmitId())
                .waUserId(r.getWaUserId())
                .remark(r.getRemark())
                .build();
    }
}
