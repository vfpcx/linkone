package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.ArbitrationMapper;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.ArbitrationService;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.vo.ArbitrationVo;
import com.cangchu.inventory.dto.DisputeRestoreContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仲裁服务实现（P3 BE-W1，12 §2.6 / §4：入库异议 TA 仲裁最小闭环）。
 *
 * <p>结论注册表（12 §4.2）：biz_type → 合法结论集；副作用（恢复流水/状态迁移）在 decide 内
 * 按 biz_type 分支——OUTBOUND_COMPLAINT 注册项已冻结取值域，副作用 BE-W2 落地（OPS 端点）。
 *
 * <p>安全规约：TA 鉴权走 {@code authService.hasRole(userId,"TA",tenantId)} 先例；
 * 仲裁单经 TenantLine 兜底（arbitrations 白名单）——跨租户按不存在（50334），不泄露存在性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrationServiceImpl implements ArbitrationService {

    private final ArbitrationMapper arbitrationMapper;
    private final InboundRequestMapper inboundRequestMapper;
    // P3 BE-W2（12 §3.4）：客诉裁决联动出库单 COMPLAINED→COMPLETED
    private final com.cangchu.document.mapper.OutboundRequestMapper outboundRequestMapper;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final WholesalerService wholesalerService;
    private final TenantService tenantService;
    private final DocumentNumberService documentNumberService;
    private final AuthService authService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final ObjectMapper objectMapper;

    /** 结论注册表（12 §4.2）：新增仲裁类型只加注册项不改流程。 */
    private static final Map<String, Set<String>> ALLOWED_CONCLUSIONS = Map.of(
            Arbitration.BIZ_INBOUND_DISPUTE,
            Set.of(Arbitration.CONCLUSION_APPROVED, Arbitration.CONCLUSION_REJECTED),
            Arbitration.BIZ_OUTBOUND_COMPLAINT,
            Set.of(Arbitration.WK_LIABLE, Arbitration.WA_LIABLE, Arbitration.NEGOTIATED, Arbitration.NO_LIABILITY));

    /** 差额定责取值域（12 §4.1 liability 四选枚举）。 */
    private static final Set<String> LIABILITY_ENUM =
            Set.of(Arbitration.WK_LIABLE, Arbitration.WA_LIABLE, Arbitration.NEGOTIATED, Arbitration.NO_LIABILITY);

    // ==================== 创建（入库异议，disputeByWa 同事务调用） ====================

    @Override
    public Arbitration createInboundDispute(InboundRequest inbound, Long initiatorUserId, String initiatorRole,
                                            String reason, List<String> attachments,
                                            int reversedQty, int shortfallQty) {
        String simpleCode = tenantService.getSimpleCode(inbound.getTenantId());
        if (simpleCode == null || simpleCode.isBlank()) {
            simpleCode = "T" + inbound.getTenantId();
        }
        Arbitration arb = new Arbitration();
        arb.setId(snowflakeIdUtil.nextId());
        arb.setDocNo(documentNumberService.generate(DocType.DISPUTE_ARBITRATION, simpleCode));
        arb.setTenantId(inbound.getTenantId());
        arb.setBizType(Arbitration.BIZ_INBOUND_DISPUTE);
        arb.setRefDocType(Arbitration.REF_INBOUND);
        arb.setRefDocId(inbound.getId());
        arb.setRefDocNo(inbound.getDocNo());
        arb.setWholesalerId(inbound.getWholesalerId());
        arb.setInitiatorUserId(initiatorUserId);
        arb.setInitiatorRole(initiatorRole);
        arb.setReason(reason);
        arb.setAttachments(encodeAttachments(attachments));
        arb.setReversedQty(reversedQty);
        arb.setShortfallQty(shortfallQty);
        arb.setStatus(Arbitration.STATUS_PENDING);
        try {
            arbitrationMapper.insert(arb);
        } catch (DuplicateKeyException e) {
            // uk_arb_doc_no 唯一兜底（G-5.1 双保险）
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }
        return arb;
    }

    // ==================== TA 列表 / 裁决 ====================

    @Override
    public Page<ArbitrationVo> listForTa(Long tenantId, Long taUserId, String bizType, String status,
                                         int page, int size) {
        requireTaRole(tenantId, taUserId);
        Page<Arbitration> p = arbitrationMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<Arbitration>()
                        // 显式 eq(tenant_id)（与 TenantLine 兜底叠加，双保险）
                        .eq(Arbitration::getTenantId, tenantId)
                        .eq(bizType != null && !bizType.isBlank(), Arbitration::getBizType, bizType)
                        .eq(status != null && !status.isBlank(), Arbitration::getStatus, status)
                        .orderByDesc(Arbitration::getCreatedAt));
        // 商户名小缓存（页内去重，免 n+1 放大）
        Map<Long, String> nameCache = new HashMap<>();
        Page<ArbitrationVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream()
                .map(a -> toVo(a, nameCache.computeIfAbsent(a.getWholesalerId(), this::wholesalerName)))
                .toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArbitrationVo decideByTa(Long arbitrationId, Long taUserId, ArbitrationDecideDto dto) {
        // TenantLine 兜底：跨租户不可见 → 按不存在（50334）
        Arbitration arb = arbitrationId != null ? arbitrationMapper.selectById(arbitrationId) : null;
        if (arb == null) {
            throw new BizException(ErrorCode.ARBITRATION_NOT_PENDING);
        }
        requireTaRole(arb.getTenantId(), taUserId);
        // TA 端点只受理入库异议（出库客诉归 OPS，BE-W2）
        if (!Arbitration.BIZ_INBOUND_DISPUTE.equals(arb.getBizType())) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        }

        String conclusion = trimToNull(dto.getConclusion());
        String remark = trimToNull(dto.getRemark());
        String liability = trimToNull(dto.getLiability());

        // 结论校验（12 §6.2 50333）：biz_type × conclusion 错配 / REJECTED 缺 remark
        Set<String> allowed = ALLOWED_CONCLUSIONS.getOrDefault(arb.getBizType(), Set.of());
        if (conclusion == null || !allowed.contains(conclusion)) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        }
        boolean rejected = Arbitration.CONCLUSION_REJECTED.equals(conclusion);
        if (rejected && remark == null) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID, "驳回必须填写结论备注");
        }

        // liability 三态校验（12 §4.1 产品刚性规则，50342）：
        // 仅 REJECTED ∧ shortfall_qty>0 必填（枚举四选）；其余场景必须为空
        boolean liabilityRequired = rejected && arb.getShortfallQty() != null && arb.getShortfallQty() > 0;
        if (liabilityRequired) {
            if (liability == null || !LIABILITY_ENUM.contains(liability)) {
                throw new BizException(ErrorCode.ARBITRATION_LIABILITY_INVALID);
            }
        } else if (liability != null) {
            throw new BizException(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        }

        // CAS：PENDING → DECIDED（防并发双裁，12 §2.6）
        LocalDateTime now = LocalDateTime.now();
        int affected = arbitrationMapper.update(null, new LambdaUpdateWrapper<Arbitration>()
                .eq(Arbitration::getId, arb.getId())
                .eq(Arbitration::getStatus, Arbitration.STATUS_PENDING)
                .set(Arbitration::getStatus, Arbitration.STATUS_DECIDED)
                .set(Arbitration::getConclusion, conclusion)
                .set(Arbitration::getLiability, liability)
                .set(Arbitration::getConclusionRemark, remark)
                .set(Arbitration::getArbitratorUserId, taUserId)
                .set(Arbitration::getDecidedAt, now)
                .set(Arbitration::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.ARBITRATION_NOT_PENDING);
        }

        // 副作用：入库单状态迁移 + 库存联动（12 §2.6）
        InboundRequest inbound = inboundRequestMapper.selectById(arb.getRefDocId());
        if (inbound == null) {
            // 仲裁单引用的单据必然存在（同租户内建），防御性兜底
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND);
        }
        String conclusionText;
        if (Arbitration.CONCLUSION_APPROVED.equals(conclusion)) {
            // APPROVED=异议不成立（语义冻结，对齐 04 §1.1）：恢复流水 biz_time=原入库时间戳（G10），
            // 只回 reversed_qty（差额那部分货已离仓不恢复）；入库单 DISPUTED→CONFIRMED
            if (arb.getReversedQty() != null && arb.getReversedQty() > 0) {
                inventoryService.restoreInboundAfterArbitration(DisputeRestoreContext.builder()
                        .wholesalerId(inbound.getWholesalerId())
                        .tenantId(inbound.getTenantId())
                        .skuId(inbound.getSkuId())
                        .qty(arb.getReversedQty())
                        .refDocNo(inbound.getDocNo())
                        .originalInboundAt(inbound.getCreatedAt())
                        .operatorUserId(taUserId)
                        .build());
            }
            casInbound(inbound.getId(), InboundRequest.STATUS_DISPUTED, InboundRequest.STATUS_CONFIRMED, now);
            conclusionText = "通过·恢复流水（入库有效，恢复 " + (arb.getReversedQty() == null ? 0 : arb.getReversedQty()) + " 件）";
        } else {
            // REJECTED=异议成立：保留冲销；入库单 DISPUTED→REVOKED
            casInbound(inbound.getId(), InboundRequest.STATUS_DISPUTED, InboundRequest.STATUS_REVOKED, now);
            conclusionText = "驳回·保留冲销（入库无效）";
        }

        // 双方站内信（WA+WK）；差额定责文案：线下定责依据，平台不接资金（12 §2.6 / PRD 09 §3.2）
        String content = "仲裁单 " + arb.getDocNo() + "（入库单 " + arb.getRefDocNo() + "）结论：" + conclusionText
                + (remark != null ? "。备注：" + remark : "")
                + (liability != null ? "。差额定责：" + liability + "（仅作线下定责依据，平台不接资金）" : "");
        // P3 缺陷修复：「归属 WA」收件人以 user_roles 推导（owner_user_id 在 SELF_OPERATED 上是 TA），多账号全发
        notificationService.sendToAll(arb.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(arb.getWholesalerId()),
                Notification.TYPE_ARBITRATION_DECIDED, "入库异议仲裁已裁决", content,
                Notification.REF_ARBITRATION, arb.getId());
        notificationService.send(arb.getTenantId(), inbound.getWkUserId(),
                Notification.TYPE_ARBITRATION_DECIDED, "入库异议仲裁已裁决", content,
                Notification.REF_ARBITRATION, arb.getId());

        log.info("[P3] TA {} 裁决 arb={} conclusion={} liability={}", taUserId, arb.getDocNo(), conclusion, liability);
        return toVo(arbitrationMapper.selectById(arb.getId()), wholesalerName(arb.getWholesalerId()));
    }

    // ==================== P3 BE-W2：出库客诉 OPS 仲裁（12 §3.4 / PRD 09 §3） ====================

    @Override
    public Arbitration createOutboundComplaint(com.cangchu.document.entity.OutboundRequest outbound,
                                               Long initiatorUserId, String initiatorRole,
                                               String reason, List<String> attachments) {
        // 一单一诉（PRD 09 §1.1）：同一单据同类型仅允许 1 张，已裁决后不可再提（多轮 → P5）。
        // 出库单 COMPLAINED→COMPLETED 可回环（矩阵允许），故不能只靠单据状态闸门，需查历史仲裁单。
        long existing = arbitrationMapper.selectCount(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getRefDocType, Arbitration.REF_OUTBOUND)
                .eq(Arbitration::getRefDocId, outbound.getId()));
        if (existing > 0) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "该出库单已有客诉记录，不可重复提起");
        }
        String simpleCode = tenantService.getSimpleCode(outbound.getTenantId());
        if (simpleCode == null || simpleCode.isBlank()) {
            simpleCode = "T" + outbound.getTenantId();
        }
        Arbitration arb = new Arbitration();
        arb.setId(snowflakeIdUtil.nextId());
        arb.setDocNo(documentNumberService.generate(DocType.COMPLAINT, simpleCode));
        arb.setTenantId(outbound.getTenantId());
        arb.setBizType(Arbitration.BIZ_OUTBOUND_COMPLAINT);
        arb.setRefDocType(Arbitration.REF_OUTBOUND);
        arb.setRefDocId(outbound.getId());
        arb.setRefDocNo(outbound.getDocNo());
        arb.setWholesalerId(outbound.getWholesalerId());
        arb.setInitiatorUserId(initiatorUserId);
        arb.setInitiatorRole(initiatorRole);
        arb.setReason(reason);
        arb.setAttachments(encodeAttachments(attachments));
        arb.setStatus(Arbitration.STATUS_PENDING);
        try {
            arbitrationMapper.insert(arb);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }
        return arb;
    }

    @Override
    public Page<ArbitrationVo> listForOps(Long opsUserId, String bizType, String status, int page, int size) {
        requireOpsRole(opsUserId);
        // OPS 端点只受理出库客诉（入库异议归 TA）；显式传入其他 bizType → 50333
        if (bizType != null && !bizType.isBlank() && !Arbitration.BIZ_OUTBOUND_COMPLAINT.equals(bizType)) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID, "平台运维仅受理出库客诉仲裁");
        }
        // OPS 无租户上下文 → TenantLine 不注入 → 跨租户全平台可见（MybatisPlusConfig 先例）
        Page<Arbitration> p = arbitrationMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<Arbitration>()
                        .eq(Arbitration::getBizType, Arbitration.BIZ_OUTBOUND_COMPLAINT)
                        .eq(status != null && !status.isBlank(), Arbitration::getStatus, status)
                        .orderByDesc(Arbitration::getCreatedAt));
        Map<Long, String> nameCache = new HashMap<>();
        Page<ArbitrationVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream()
                .map(a -> toVo(a, nameCache.computeIfAbsent(a.getWholesalerId(), this::wholesalerName)))
                .toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArbitrationVo decideByOps(Long arbitrationId, Long opsUserId, ArbitrationDecideDto dto) {
        requireOpsRole(opsUserId);
        Arbitration arb = arbitrationId != null ? arbitrationMapper.selectById(arbitrationId) : null;
        if (arb == null) {
            throw new BizException(ErrorCode.ARBITRATION_NOT_PENDING);
        }
        // OPS 端点只受理出库客诉（TA 端镜像门）
        if (!Arbitration.BIZ_OUTBOUND_COMPLAINT.equals(arb.getBizType())) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        }

        String conclusion = trimToNull(dto.getConclusion());
        String remark = trimToNull(dto.getRemark());
        // 结论四选（50333）；remark 必填（PRD 09 §1.1：结论备注是线下赔偿唯一依据，必须留痕）
        Set<String> allowed = ALLOWED_CONCLUSIONS.getOrDefault(arb.getBizType(), Set.of());
        if (conclusion == null || !allowed.contains(conclusion)) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID);
        }
        if (remark == null) {
            throw new BizException(ErrorCode.ARBITRATION_CONCLUSION_INVALID, "必须填写结论备注");
        }
        // liability 仅入库差额定责适用（12 §4.1）：客诉传入 → 50342
        if (trimToNull(dto.getLiability()) != null) {
            throw new BizException(ErrorCode.ARBITRATION_LIABILITY_INVALID);
        }

        // CAS：PENDING → DECIDED（防并发双裁）
        LocalDateTime now = LocalDateTime.now();
        int affected = arbitrationMapper.update(null, new LambdaUpdateWrapper<Arbitration>()
                .eq(Arbitration::getId, arb.getId())
                .eq(Arbitration::getStatus, Arbitration.STATUS_PENDING)
                .set(Arbitration::getStatus, Arbitration.STATUS_DECIDED)
                .set(Arbitration::getConclusion, conclusion)
                .set(Arbitration::getConclusionRemark, remark)
                .set(Arbitration::getArbitratorUserId, opsUserId)
                .set(Arbitration::getDecidedAt, now)
                .set(Arbitration::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.ARBITRATION_NOT_PENDING);
        }

        // 副作用：出库单 COMPLAINED → COMPLETED（任何结论；库存、流水、账单一概不动，D43）
        com.cangchu.document.entity.OutboundRequest outbound = outboundRequestMapper.selectById(arb.getRefDocId());
        if (outbound == null) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID, "客诉引用的出库单不存在");
        }
        boolean moved = DocStateMachine.casTransition(outboundRequestMapper, DocStateMachine.DocKind.OUTBOUND,
                outbound.getId(),
                com.cangchu.document.entity.OutboundRequest::getId,
                com.cangchu.document.entity.OutboundRequest::getStatus,
                com.cangchu.document.entity.OutboundRequest.STATUS_COMPLAINED,
                com.cangchu.document.entity.OutboundRequest.STATUS_COMPLETED, null);
        if (!moved) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        // 双方站内信（WA+WK）：结论仅线下赔偿依据（平台不接资金）
        String conclusionText = switch (conclusion) {
            case Arbitration.WK_LIABLE -> "库管员责任";
            case Arbitration.WA_LIABLE -> "批发商管理员责任";
            case Arbitration.NEGOTIATED -> "双方协商";
            default -> "无责";
        };
        String content = "客诉单 " + arb.getDocNo() + "（出库单 " + arb.getRefDocNo() + "）已裁决：" + conclusionText
                + "。备注：" + remark + "。仅判责，不改库存与账单；结论作为双方线下赔偿依据（平台不接资金）。";
        // P3 缺陷修复：同 decideByTa——收件人以 user_roles 推导，多 WA 账号全发
        notificationService.sendToAll(arb.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(arb.getWholesalerId()),
                Notification.TYPE_ARBITRATION_DECIDED, "出库客诉已裁决", content,
                Notification.REF_ARBITRATION, arb.getId());
        notificationService.send(arb.getTenantId(), outbound.getWkUserId(),
                Notification.TYPE_ARBITRATION_DECIDED, "出库客诉已裁决", content,
                Notification.REF_ARBITRATION, arb.getId());

        log.info("[P3] OPS {} 裁决客诉 arb={} conclusion={}", opsUserId, arb.getDocNo(), conclusion);
        return toVo(arbitrationMapper.selectById(arb.getId()), wholesalerName(arb.getWholesalerId()));
    }

    @Override
    public long countPendingForWholesaler(Long wholesalerId) {
        return arbitrationMapper.selectCount(new LambdaQueryWrapper<Arbitration>()
                .eq(Arbitration::getWholesalerId, wholesalerId)
                .eq(Arbitration::getStatus, Arbitration.STATUS_PENDING));
    }

    // ==================== 私有 ====================

    /** OPS 平台角色鉴权（requireOpsRole 先例，写法对齐 BlacklistServiceImpl）。 */
    private void requireOpsRole(Long userId) {
        if (!authService.hasRole(userId, "OPS")) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }

    /** 入库单 CAS 迁移（from→to）；affected!=1 → 50331（并发被抢占/状态漂移）。 */
    private void casInbound(Long inboundId, String from, String to, LocalDateTime now) {
        int affected = inboundRequestMapper.update(null, new LambdaUpdateWrapper<InboundRequest>()
                .eq(InboundRequest::getId, inboundId)
                .eq(InboundRequest::getStatus, from)
                .set(InboundRequest::getStatus, to)
                .set(InboundRequest::getUpdatedAt, now));
        if (affected != 1) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
    }

    /** TA 鉴权（requireTaRole 先例，写法对齐 WholesalerApplicationServiceImpl）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    private String wholesalerName(Long wholesalerId) {
        WholesalerVo w = wholesalerService.getById(wholesalerId);
        return w != null ? w.getName() : null;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String encodeAttachments(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(attachments);
            if (json.length() > 1024) {
                // 列宽 1024 兜底（≤5 个 ≤200 字 URL 正常不会触发）
                throw new BizException(ErrorCode.VALIDATION_BASIC_001, "附件 URL 过长");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "附件格式非法");
        }
    }

    private List<String> decodeAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            log.warn("[P3] 仲裁附件 JSON 解码失败: {}", json, e);
            return List.of();
        }
    }

    private ArbitrationVo toVo(Arbitration a, String wholesalerName) {
        return ArbitrationVo.builder()
                .id(a.getId())
                .docNo(a.getDocNo())
                .bizType(a.getBizType())
                .refDocType(a.getRefDocType())
                .refDocId(a.getRefDocId())
                .refDocNo(a.getRefDocNo())
                .wholesalerId(a.getWholesalerId())
                .wholesalerName(wholesalerName)
                .initiatorUserId(a.getInitiatorUserId())
                .initiatorRole(a.getInitiatorRole())
                .reason(a.getReason())
                .attachments(decodeAttachments(a.getAttachments()))
                .reversedQty(a.getReversedQty())
                .shortfallQty(a.getShortfallQty())
                .status(a.getStatus())
                .conclusion(a.getConclusion())
                .liability(a.getLiability())
                .conclusionRemark(a.getConclusionRemark())
                .arbitratorUserId(a.getArbitratorUserId())
                .decidedAt(a.getDecidedAt())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
