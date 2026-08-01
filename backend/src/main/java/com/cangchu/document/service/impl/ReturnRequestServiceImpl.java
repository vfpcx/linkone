package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.ReturnCreateDto;
import com.cangchu.document.dto.ReturnRegisterDto;
import com.cangchu.document.dto.ReturnWithdrawDto;
import com.cangchu.document.entity.ReturnRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.ReturnRequestMapper;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.ReturnRequestService;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import com.cangchu.document.vo.ReturnRequestVo;
import com.cangchu.inventory.dto.ReturnStockContext;
import com.cangchu.inventory.dto.ReturnStockResult;
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
import java.util.List;

/**
 * 退货单服务实现（P3b T3-W1，13 §2.1）。
 *
 * <p>安全规约（05-secure-coding-guardrails，与 InboundRequestServiceImpl 同构）：
 * <ul>
 *   <li>S4 越权（G-1.3）：发起/撤回以 user_roles 推导 WA 归属（D-9：WE 一律 42004，
 *       授权位未开）；受理/登记 requireWkRole；tenantId 由 sku→wholesaler 真实归属推导，不取客户端。</li>
 *   <li>S5/S7：登记扣减在 InventoryService 锁 {@code lock:inv:{w}:{s}} 内单事务执行，
 *       不足 STOCK_NOT_ENOUGH 拒绝且整体回滚（单据保持 ACCEPTED）。</li>
 *   <li>S6：docNo 唯一索引兜底；「CAS 迁移 + returnStock + 通知」单事务，任一失败整体回滚。</li>
 *   <li>R14 有意不接（13 §3.6 同理）：退货是存量库存治理（退驻前置库存=0 依赖退货通道），
 *       商户 OFFLINE/退驻流程中仍可退货清库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnRequestServiceImpl implements ReturnRequestService {

    private final ReturnRequestMapper returnRequestMapper;
    private final SkuService skuService;
    private final TenantService tenantService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnRequestVo createByWa(ReturnCreateDto dto, Long userId) {
        // S2 防御性双层（DTO @Valid 之外）
        if (dto == null || dto.getSkuId() == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少商品 SKU");
        }
        if (dto.getQty() == null || dto.getQty() <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        if (dto.getRemark() != null && dto.getRemark().length() > 512) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        // wholesaler/tenant 由 sku 真实归属推导（S4；TenantLine 兜底跨租户按不存在）
        SkuVo sku = skuService.getById(dto.getSkuId());
        if (sku == null) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }
        Long wholesalerId = sku.getWholesalerId();
        Long tenantId = sku.getTenantId();
        // D-9：仅 WA（WE 授权位未开放，入口对员工隐藏）
        requireWaOnly(wholesalerId, userId);

        // 软校验（PRD §2.1）：退货件数 ≤ 当前在库——仅拒超量提交，不锁定库存（登记前货仍可售）
        inventoryService.assertStockEnough(wholesalerId, sku.getId(), dto.getQty());

        ReturnRequest req = new ReturnRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(documentNumberService.generate(DocType.RETURN, resolveSimpleCode(tenantId)));
        req.setTenantId(tenantId);
        req.setWholesalerId(wholesalerId);
        req.setSkuId(sku.getId());
        req.setQty(dto.getQty());
        req.setPalletRelease(0);
        req.setStatus(ReturnRequest.STATUS_PENDING_ACCEPT);
        req.setWaUserId(userId);
        req.setRemark(trimToNull(dto.getRemark()));
        try {
            returnRequestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }

        // 通知①（13 §5.3）：发起 → 库管（user_roles 推导，多账号全发；文案零角色码）
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        String wholesalerName = wholesaler != null ? wholesaler.getName() : String.valueOf(wholesalerId);
        notificationService.sendToAll(tenantId, authService.listActiveWkUserIdsOfTenant(tenantId),
                Notification.TYPE_RETURN_CREATED, "新的退货申请待受理",
                "商户「" + wholesalerName + "」提交退货申请 " + req.getDocNo() + "（"
                        + sku.getName() + " × " + dto.getQty() + " 件），请及时受理；"
                        + "登记出货前该商品仍可售、库存不变。",
                Notification.REF_RETURN, req.getId());

        log.info("[P3b][RTN] user {} 发起退货 doc={} wholesaler={} sku={} qty={}",
                userId, req.getDocNo(), wholesalerId, sku.getId(), dto.getQty());
        return toVo(req, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnRequestVo withdrawByWa(Long returnId, ReturnWithdrawDto dto, Long userId) {
        if (dto == null || dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少撤回理由");
        }
        if (dto.getReason().length() > 512) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001);
        }
        ReturnRequest req = loadReturn(returnId);
        requireWaOnly(req.getWholesalerId(), userId);
        // 受理锁单后不可撤（矩阵红线 ACCEPTED→WITHDRAWN ❌ → 50330）
        DocStateMachine.assertCanGo(DocKind.RETURN, req.getStatus(), ReturnRequest.STATUS_WITHDRAWN);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(returnRequestMapper, DocKind.RETURN,
                returnId, ReturnRequest::getId, ReturnRequest::getStatus,
                ReturnRequest.STATUS_PENDING_ACCEPT, ReturnRequest.STATUS_WITHDRAWN,
                uw -> uw.set(ReturnRequest::getWithdrawReason, dto.getReason().trim())
                        .set(ReturnRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        // D-7 架构红利：登记前撤回本就没扣库存 → 无回补流水
        log.info("[P3b][RTN] user {} 撤回退货 doc={}", userId, req.getDocNo());
        return toVo(returnRequestMapper.selectById(returnId), null, null);
    }

    @Override
    public Page<ReturnRequestVo> listForWa(Long userId, String status, int page, int size) {
        // 「我管的商户」= WA 绑定 ∪ WE 绑定（WE 只读可见，13 §5.2；写操作 D-9 一律拒）
        java.util.LinkedHashSet<Long> wholesalerIds = new java.util.LinkedHashSet<>();
        wholesalerIds.addAll(authService.listActiveWholesalerIds(userId, "WA"));
        wholesalerIds.addAll(authService.listActiveWeWholesalerIds(userId));
        Page<ReturnRequestVo> empty = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100), 0);
        if (wholesalerIds.isEmpty()) {
            return empty;
        }
        Page<ReturnRequest> p = returnRequestMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<ReturnRequest>()
                        .in(ReturnRequest::getWholesalerId, wholesalerIds)
                        .eq(status != null && !status.isBlank(), ReturnRequest::getStatus, status)
                        .orderByDesc(ReturnRequest::getCreatedAt));
        Page<ReturnRequestVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(r -> toVo(r, null, null)).toList());
        return out;
    }

    @Override
    public List<ReturnRequestVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status) {
        requireWkOrTa(tenantId, userId);
        boolean pendingQueue = ReturnRequest.STATUS_PENDING_ACCEPT.equals(status);
        LambdaQueryWrapper<ReturnRequest> qw = new LambdaQueryWrapper<ReturnRequest>()
                .eq(ReturnRequest::getTenantId, tenantId)
                .eq(wholesalerId != null, ReturnRequest::getWholesalerId, wholesalerId)
                .eq(status != null && !status.isBlank(), ReturnRequest::getStatus, status);
        if (pendingQueue) {
            // 待受理队列按创建升序（先到先受理，idx_rtn_tenant_status 覆盖）
            qw.orderByAsc(ReturnRequest::getCreatedAt);
        } else {
            qw.orderByDesc(ReturnRequest::getCreatedAt);
        }
        boolean fillStock = pendingQueue || ReturnRequest.STATUS_ACCEPTED.equals(status);
        return returnRequestMapper.selectList(qw).stream()
                .map(r -> fillStock ? withStockHint(r) : toVo(r, null, null))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnRequestVo acceptByWk(Long returnId, Long userId) {
        ReturnRequest req = loadReturn(returnId);
        requireWkRole(req.getTenantId(), userId);
        // 不可达（终态/已受理等）由矩阵统一 50330
        DocStateMachine.assertCanGo(DocKind.RETURN, req.getStatus(), ReturnRequest.STATUS_ACCEPTED);
        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(returnRequestMapper, DocKind.RETURN,
                returnId, ReturnRequest::getId, ReturnRequest::getStatus,
                ReturnRequest.STATUS_PENDING_ACCEPT, ReturnRequest.STATUS_ACCEPTED,
                uw -> uw.set(ReturnRequest::getWkUserId, userId)
                        .set(ReturnRequest::getAcceptedAt, now)
                        .set(ReturnRequest::getUpdatedAt, now));
        if (!ok) {
            // 与 WA 撤回竞态由 CAS 决出唯一赢家（败方刷新重试）
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }
        log.info("[P3b][RTN] WK {} 受理退货 doc={}", userId, req.getDocNo());
        return withStockHint(returnRequestMapper.selectById(returnId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnRequestVo registerByWk(Long returnId, ReturnRegisterDto dto, Long userId) {
        int actual;
        Integer palletOverride = null;
        String extraRemark = null;
        if (dto != null) {
            if (dto.getActualQty() != null && dto.getActualQty() <= 0) {
                throw new BizException(ErrorCode.STOCK_QTY_INVALID);
            }
            if (dto.getPalletRelease() != null && dto.getPalletRelease() < 0) {
                throw new BizException(ErrorCode.STOCK_QTY_INVALID);
            }
            if (dto.getRemark() != null && dto.getRemark().length() > 512) {
                throw new BizException(ErrorCode.VALIDATION_BASIC_001);
            }
            palletOverride = dto.getPalletRelease();
            extraRemark = trimToNull(dto.getRemark());
        }
        ReturnRequest req = loadReturn(returnId);
        requireWkRole(req.getTenantId(), userId);
        // 待受理不可直登（矩阵红线 PENDING_ACCEPT→COMPLETED ❌ → 50330，必须经受理）
        DocStateMachine.assertCanGo(DocKind.RETURN, req.getStatus(), ReturnRequest.STATUS_COMPLETED);

        actual = (dto != null && dto.getActualQty() != null) ? dto.getActualQty() : req.getQty();
        // 登记按实覆写留痕（PRD §2.1）：差异自动写备注，叠加 WK 手填备注
        String remark = extraRemark;
        if (actual != req.getQty()) {
            String diffNote = "申请 " + req.getQty() + " 件，实退 " + actual + " 件";
            remark = remark == null ? diffNote : diffNote + "；" + remark;
        }
        String finalRemark = remark;

        LocalDateTime now = LocalDateTime.now();
        boolean ok = DocStateMachine.casTransition(returnRequestMapper, DocKind.RETURN,
                returnId, ReturnRequest::getId, ReturnRequest::getStatus,
                ReturnRequest.STATUS_ACCEPTED, ReturnRequest.STATUS_COMPLETED,
                uw -> uw.set(ReturnRequest::getQty, actual)
                        .set(ReturnRequest::getCompletedAt, now)
                        .set(finalRemark != null, ReturnRequest::getRemark, finalRemark)
                        .set(ReturnRequest::getWkUserId, userId)
                        .set(ReturnRequest::getUpdatedAt, now));
        if (!ok) {
            throw new BizException(ErrorCode.DOC_STATE_CAS_CONFLICT);
        }

        // D-7 此刻才扣（锁内单事务）：不足抛 STOCK_NOT_ENOUGH(50251) → 本方法事务整体回滚，
        // 单据保持 ACCEPTED（页面提示联系商户改单，与 04 §3.2 拣货不足同构）
        ReturnStockResult rs = inventoryService.returnStock(ReturnStockContext.builder()
                .wholesalerId(req.getWholesalerId())
                .tenantId(req.getTenantId())
                .skuId(req.getSkuId())
                .qty(actual)
                .palletReleaseOverride(palletOverride)
                .refDocNo(req.getDocNo())
                .operatorUserId(userId)
                .build());

        // 实际释放托盘回写单据（默认建议值/覆盖值经封顶后的落库值）
        returnRequestMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReturnRequest>()
                .eq(ReturnRequest::getId, returnId)
                .set(ReturnRequest::getPalletRelease, rs.getPalletReleased())
                .set(ReturnRequest::getUpdatedAt, LocalDateTime.now()));

        // 通知②（13 §5.3）：登记完成 → 商户（含实退件数、释放托盘；计费当日截止）
        notificationService.sendToAll(req.getTenantId(),
                authService.listActiveWaUserIdsOfWholesaler(req.getWholesalerId()),
                Notification.TYPE_RETURN_COMPLETED, "退货登记完成",
                "退货单 " + req.getDocNo() + " 已登记出货：实退 " + actual + " 件、释放托盘 "
                        + rs.getPalletReleased() + " 个，库存已扣减，仓储费当日截止。",
                Notification.REF_RETURN, req.getId());

        log.info("[P3b][RTN] WK {} 登记退货 doc={} requested={} actual={} palletReleased={} -> 库存={}",
                userId, req.getDocNo(), req.getQty(), actual, rs.getPalletReleased(), rs.getRemainingQty());
        return toVo(returnRequestMapper.selectById(returnId), rs.getRemainingQty(), null);
    }

    @Override
    public long countOpenForWholesaler(Long wholesalerId) {
        // R13（13 §7.1 终版枚举）：待受理/已受理未登记均未结；WITHDRAWN/COMPLETED 已结
        return returnRequestMapper.selectCount(new LambdaQueryWrapper<ReturnRequest>()
                .eq(ReturnRequest::getWholesalerId, wholesalerId)
                .in(ReturnRequest::getStatus,
                        ReturnRequest.STATUS_PENDING_ACCEPT, ReturnRequest.STATUS_ACCEPTED));
    }

    // ==================== 私有 ====================

    /**
     * S4（D-9）：发起/撤回操作人须为该 wholesaler 的 ACTIVE WA。
     * WE 一律 42004（退货授权位未开放，P5 与出库授权位一并议）；其余 42001。
     */
    private void requireWaOnly(Long wholesalerId, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesalerId)) {
            return;
        }
        if (authService.hasWholesalerRole(userId, "WE", wholesalerId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_004, "员工暂不可操作退货，请联系批发商管理员");
        }
        throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅该批发商管理员可操作退货申请");
    }

    /** S4：受理/登记操作人须为该租户 WK（写法对齐 OutboundRequestServiceImpl.requireWkRole）。 */
    private void requireWkRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员可执行退货作业");
        }
    }

    /** 受理队列/历史：WK 或 TA 可见（requireWkOrTa 先例，OutboundRequestServiceImpl:619）。 */
    private void requireWkOrTa(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "WK", tenantId) && !authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_001, "仅本仓库管员或管理员可查看退货队列");
        }
    }

    /** 取退货单（TenantLine 兜底：跨租户不可见 → 按不存在拒绝）。 */
    private ReturnRequest loadReturn(Long returnId) {
        ReturnRequest req = returnId != null ? returnRequestMapper.selectById(returnId) : null;
        if (req == null) {
            throw new BizException(ErrorCode.INBOUND_NOT_FOUND, "退货单不存在");
        }
        return req;
    }

    /** 登记页提示数据：当前在库 + 默认释放托盘建议值（13 §2.4-2 公式，轻量只读快照无锁）。 */
    private ReturnRequestVo withStockHint(ReturnRequest req) {
        List<InventoryVo> list = inventoryService.queryInventory(req.getWholesalerId(), req.getSkuId());
        Integer onhand = null;
        Integer suggested = null;
        if (!list.isEmpty()) {
            InventoryVo inv = list.get(0);
            int qty = inv.getQty() != null ? Math.max(inv.getQty(), 0) : 0;
            int pallet = inv.getPalletQty() != null ? Math.max(inv.getPalletQty(), 0) : 0;
            onhand = qty;
            if (pallet == 0 || qty == 0) {
                suggested = qty == 0 ? 0 : pallet;
            } else if (req.getQty() >= qty) {
                // 全出清零（05 §3.3）：默认释放全部占用托盘
                suggested = pallet;
            } else {
                suggested = Math.min((int) Math.ceil(pallet * (double) req.getQty() / qty), pallet);
            }
        }
        return toVo(req, onhand, suggested);
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 取租户简码用于 docNo（经 TenantService，G-S2 先例）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + String.valueOf(tenantId);
    }

    private ReturnRequestVo toVo(ReturnRequest r, Integer currentStock, Integer suggestedPalletRelease) {
        return ReturnRequestVo.builder()
                .id(r.getId())
                .docNo(r.getDocNo())
                .tenantId(r.getTenantId())
                .wholesalerId(r.getWholesalerId())
                .skuId(r.getSkuId())
                .qty(r.getQty())
                .palletRelease(r.getPalletRelease())
                .status(r.getStatus())
                .withdrawReason(r.getWithdrawReason())
                .waUserId(r.getWaUserId())
                .wkUserId(r.getWkUserId())
                .acceptedAt(r.getAcceptedAt())
                .completedAt(r.getCompletedAt())
                .remark(r.getRemark())
                .createdAt(r.getCreatedAt())
                .currentStock(currentStock)
                .suggestedPalletRelease(suggestedPalletRelease)
                .build();
    }
}
