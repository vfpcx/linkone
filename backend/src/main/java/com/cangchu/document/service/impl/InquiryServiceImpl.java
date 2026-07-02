package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.entity.InquiryItem;
import com.cangchu.document.entity.InquiryRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.InquiryItemMapper;
import com.cangchu.document.mapper.InquiryRequestMapper;
import com.cangchu.document.mapper.OutboundRequestMapper;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 询价服务实现（phase-1 C2：RT 提交 → WA 确认 → 自动转出库扣库存）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>G-2.1 租户隔离：submitByRt 无登录态，tenantId 由 store→tenant 解析（复用 B2 StoreFrontService），
 *       <b>不取客户端</b>；wholesaler/sku 经显式 tenantId 核对。</li>
 *   <li>S2 校验（G-3.1）：items 非空、qty>0、wholesaler 属店、sku 属 wholesaler。</li>
 *   <li>S4 越权（G-1.3）：confirmByWa 校验操作人在该 wholesaler 下有 ACTIVE 的 WA 角色（user_roles 唯一可信来源）。</li>
 *   <li>S5 状态机 + 单事务（G-4.1/G-5.1）：confirm 仅允许 PENDING；编排「建出库单 + deductStock 扣库存」在<b>单事务</b>内，
 *       任一 item 库存不足 deductStock 抛 STOCK_NOT_ENOUGH，整个确认事务回滚（inquiry 仍 PENDING、无 outbound、库存未扣）。</li>
 *   <li>S6 唯一（G-5.1）：docNo 由 DocumentNumberService 生成 + doc_no 唯一索引兜底。</li>
 * </ul>
 *
 * <p>事务说明：deductStock 自身在 Redisson 锁内经代理调 doDeductInTx(@Transactional)，传播 REQUIRED 并入
 * confirmByWa 的外层事务，库存扣减与单据写入同生共死。
 *
 * <p>inquiry_items 无 tenant_id 列（不在 TenantLine 白名单），其租户隔离经 inquiry_id 间接保证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final InquiryRequestMapper inquiryRequestMapper;
    private final InquiryItemMapper inquiryItemMapper;
    private final OutboundRequestMapper outboundRequestMapper;
    // G-S1/G-S2 还债：他域数据只走对方 Service（不再直连 SkuMapper/TenantMapper）
    private final SkuService skuService;
    private final TenantService tenantService;
    // TODO(G-S1 待抽 AuthService)：user_roles 属 account 域，requireWaRole/listForWa 暂直连，
    //   待抽 account.AuthService.hasRole(...) 后改走 Service（P2 剩余债，已登记 Team Lead）。
    private final UserRoleMapper userRoleMapper;
    private final StoreFrontService storeFrontService;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    // ==================== RT 提交询价 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InquiryVo submitByRt(SubmitInquiryDto dto) {
        // S2：必填 + items
        if (dto.getWholesalerId() == null) {
            throw new BizException(ErrorCode.INQUIRY_WHOLESALER_NOT_IN_STORE, "缺少批发商");
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BizException(ErrorCode.INQUIRY_ITEMS_REQUIRED);
        }
        for (SubmitInquiryDto.InquiryItemDto it : dto.getItems()) {
            if (it.getSkuId() == null) {
                throw new BizException(ErrorCode.VALIDATION_BASIC_003, "缺少商品 SKU");
            }
            if (it.getQty() == null || it.getQty() <= 0) {
                throw new BizException(ErrorCode.INQUIRY_QTY_INVALID);
            }
        }

        // 解析 store→tenant，并校验 wholesaler 属该店（仅 ACTIVE）。复用 B2 StoreFrontService（不取客户端 tenantId）。
        StoreFrontVo store = storeFrontService.getStorePage(dto.getStoreId(), dto.getCode());
        Long tenantId = store.getTenantId();
        Long storeId = store.getStoreId();
        boolean waInStore = store.getWholesalers() != null && store.getWholesalers().stream()
                .map(StoreWholesalerVo::getWholesalerId)
                .anyMatch(id -> id.equals(dto.getWholesalerId()));
        if (!waInStore) {
            throw new BizException(ErrorCode.INQUIRY_WHOLESALER_NOT_IN_STORE);
        }

        // 建询价单（PENDING）
        String docNo = documentNumberService.generate(DocType.INQUIRY, resolveSimpleCode(tenantId));
        InquiryRequest req = new InquiryRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(docNo);
        req.setStoreId(storeId);
        req.setTenantId(tenantId);
        req.setWholesalerId(dto.getWholesalerId());
        req.setStatus(InquiryRequest.STATUS_PENDING);
        req.setRtPhone(dto.getRtPhone());
        try {
            inquiryRequestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.DOC_NO_GENERATE_FAILED);
        }

        // 建明细（含公开价快照）。每个 sku 必须属该 wholesaler 且同租户（显式核对）。
        for (SubmitInquiryDto.InquiryItemDto it : dto.getItems()) {
            SkuVo sku = skuService.getById(it.getSkuId());
            if (sku == null
                    || !tenantId.equals(sku.getTenantId())
                    || !dto.getWholesalerId().equals(sku.getWholesalerId())) {
                throw new BizException(ErrorCode.INQUIRY_SKU_NOT_BELONG);
            }
            BigDecimal unitPrice = sku.getUnitPrice() != null ? sku.getUnitPrice() : BigDecimal.ZERO;
            InquiryItem item = new InquiryItem();
            item.setId(snowflakeIdUtil.nextId());
            item.setInquiryId(req.getId());
            item.setSkuId(it.getSkuId());
            item.setQty(it.getQty());
            item.setUnitPriceSnapshot(unitPrice);
            item.setMoqPriceSnapshot(sku.getMoqPrice() != null ? sku.getMoqPrice() : BigDecimal.ZERO);
            item.setMoqQtySnapshot(sku.getMoqQty() != null ? sku.getMoqQty() : 0);
            item.setDealPrice(unitPrice); // phase-1：成交价 = 单价快照
            inquiryItemMapper.insert(item);
        }

        log.info("[C2] RT {} 提交询价 doc={} store={} wholesaler={} items={}",
                dto.getRtPhone(), docNo, storeId, dto.getWholesalerId(), dto.getItems().size());

        return loadVo(req.getId());
    }

    // ==================== WA 确认 → 自动转出库 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InquiryVo confirmByWa(Long inquiryId, Long waUserId) {
        if (inquiryId == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        InquiryRequest inquiry = inquiryRequestMapper.selectById(inquiryId);
        if (inquiry == null) {
            throw new BizException(ErrorCode.INQUIRY_NOT_FOUND);
        }

        // S4：操作人必须在该 inquiry 的 wholesaler 下有 ACTIVE 的 WA 角色（user_roles 唯一可信来源）
        requireWaRole(inquiry.getWholesalerId(), waUserId);

        // S5 + 并发（§10 P2 状态条件 CAS）：仅 PENDING 可确认；用 UPDATE...WHERE status=PENDING
        // 校验 affected==1，防止并发双击两个请求都读到 PENDING 而重复建出库单/重复扣库存。
        if (!InquiryRequest.STATUS_PENDING.equals(inquiry.getStatus())) {
            throw new BizException(ErrorCode.INQUIRY_STATUS_INVALID);
        }
        int cas = inquiryRequestMapper.update(null, new LambdaUpdateWrapper<InquiryRequest>()
                .set(InquiryRequest::getStatus, InquiryRequest.STATUS_CONFIRMED)
                .set(InquiryRequest::getConfirmedAt, java.time.LocalDateTime.now())
                .eq(InquiryRequest::getId, inquiry.getId())
                .eq(InquiryRequest::getStatus, InquiryRequest.STATUS_PENDING));
        if (cas != 1) {
            // 并发竞争失败或已被他人确认 → 拒绝，避免重复出库
            throw new BizException(ErrorCode.INQUIRY_STATUS_INVALID);
        }

        Long tenantId = inquiry.getTenantId();
        Long wholesalerId = inquiry.getWholesalerId();
        String simpleCode = resolveSimpleCode(tenantId);

        // 遍历 items → 每条生成出库单 + 扣库存（库存不足整体回滚）
        List<InquiryItem> items = inquiryItemMapper.selectList(new LambdaQueryWrapper<InquiryItem>()
                .eq(InquiryItem::getInquiryId, inquiry.getId()));
        for (InquiryItem item : items) {
            String outDocNo = documentNumberService.generate(DocType.OUTBOUND, simpleCode);
            OutboundRequest out = new OutboundRequest();
            out.setId(snowflakeIdUtil.nextId());
            out.setDocNo(outDocNo);
            out.setInquiryId(inquiry.getId());
            out.setTenantId(tenantId);
            out.setWholesalerId(wholesalerId);
            out.setSkuId(item.getSkuId());
            out.setQty(item.getQty());
            out.setStatus(OutboundRequest.STATUS_COMPLETED);
            out.setWkUserId(waUserId);
            try {
                outboundRequestMapper.insert(out);
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.OUTBOUND_GENERATE_FAILED);
            }

            // 扣库存：deductStock 在 Redisson 锁内单事务执行，库存不足抛 STOCK_NOT_ENOUGH → 整个确认事务回滚
            inventoryService.deductStock(OutboundContext.builder()
                    .wholesalerId(wholesalerId)
                    .tenantId(tenantId)
                    .skuId(item.getSkuId())
                    .qty(item.getQty())
                    .refDocNo(outDocNo)
                    .operatorUserId(waUserId)
                    .build());
        }

        // 全部成功 → CONFIRMED → COMPLETED
        InquiryRequest done = new InquiryRequest();
        done.setId(inquiry.getId());
        done.setStatus(InquiryRequest.STATUS_COMPLETED);
        inquiryRequestMapper.updateById(done);

        log.info("[C2] WA {} 确认询价 doc={} wholesaler={} 生成出库 {} 条 → COMPLETED",
                waUserId, inquiry.getDocNo(), wholesalerId, items.size());

        return loadVo(inquiry.getId());
    }

    // ==================== 列表 ====================

    @Override
    public List<InquiryVo> listForWa(Long tenantId, Long waUserId) {
        // 该用户在本租户下作为 WA 归属的所有 wholesaler
        List<Long> waWholesalerIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, waUserId)
                        .eq(UserRole::getRole, "WA")
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getWholesalerId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (waWholesalerIds.isEmpty()) {
            return List.of();
        }
        List<InquiryRequest> reqs = inquiryRequestMapper.selectList(new LambdaQueryWrapper<InquiryRequest>()
                .eq(InquiryRequest::getTenantId, tenantId)
                .in(InquiryRequest::getWholesalerId, waWholesalerIds)
                .orderByDesc(InquiryRequest::getCreatedAt));
        List<InquiryVo> out = new ArrayList<>(reqs.size());
        for (InquiryRequest r : reqs) {
            out.add(toVo(r, loadItems(r.getId())));
        }
        return out;
    }

    // ==================== 私有 ====================

    /** S4：用户在指定 wholesaler 下须有 ACTIVE 的 WA 角色，否则越权拒绝。 */
    private void requireWaRole(Long wholesalerId, Long userId) {
        long c = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "WA")
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getStatus, "ACTIVE"));
        if (c == 0) {
            throw new BizException(ErrorCode.INQUIRY_OPERATOR_NOT_WA);
        }
    }

    /** 取租户简码用于 docNo；查不到则用 tenantId 占位。经 TenantService 取值（G-S2，不直连 TenantMapper）。 */
    private String resolveSimpleCode(Long tenantId) {
        String simpleCode = tenantService.getSimpleCode(tenantId);
        if (simpleCode != null && !simpleCode.isBlank()) {
            return simpleCode;
        }
        return "T" + tenantId;
    }

    private InquiryVo loadVo(Long inquiryId) {
        InquiryRequest r = inquiryRequestMapper.selectById(inquiryId);
        return toVo(r, loadItems(inquiryId));
    }

    private List<InquiryItem> loadItems(Long inquiryId) {
        return inquiryItemMapper.selectList(new LambdaQueryWrapper<InquiryItem>()
                .eq(InquiryItem::getInquiryId, inquiryId)
                .orderByAsc(InquiryItem::getId));
    }

    private InquiryVo toVo(InquiryRequest r, List<InquiryItem> items) {
        InquiryVo vo = new InquiryVo();
        vo.setId(r.getId());
        vo.setDocNo(r.getDocNo());
        vo.setStoreId(r.getStoreId());
        vo.setTenantId(r.getTenantId());
        vo.setWholesalerId(r.getWholesalerId());
        vo.setStatus(r.getStatus());
        vo.setRtPhone(r.getRtPhone());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setConfirmedAt(r.getConfirmedAt());
        vo.setItems(items.stream().map(it -> {
            InquiryVo.InquiryItemVo iv = new InquiryVo.InquiryItemVo();
            iv.setId(it.getId());
            iv.setSkuId(it.getSkuId());
            iv.setQty(it.getQty());
            iv.setUnitPriceSnapshot(it.getUnitPriceSnapshot());
            iv.setMoqPriceSnapshot(it.getMoqPriceSnapshot());
            iv.setMoqQtySnapshot(it.getMoqQtySnapshot());
            iv.setDealPrice(it.getDealPrice());
            return iv;
        }).toList());
        return vo;
    }
}
