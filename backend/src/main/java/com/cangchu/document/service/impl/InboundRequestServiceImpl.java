package com.cangchu.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.enums.DocType;
import com.cangchu.document.mapper.InboundRequestMapper;
import com.cangchu.document.service.DocumentNumberService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
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
    // G-S1/G-S2 还债：他域数据只走对方 Service（不再直连 WholesalerMapper/SkuMapper/TenantMapper）
    private final WholesalerService wholesalerService;
    private final SkuService skuService;
    private final TenantService tenantService;
    // TODO(G-S1 待抽 AuthService)：user_roles 属 account 域，requireWkRole 暂直连，
    //   待抽 account.AuthService.hasRole(...) 后改走 Service（P2 剩余债，已登记 Team Lead）。
    private final UserRoleMapper userRoleMapper;
    private final DocumentNumberService documentNumberService;
    private final InventoryService inventoryService;
    private final SnowflakeIdUtil snowflakeIdUtil;

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

        // 校验 wholesaler 存在且属当前租户（经 WholesalerService，内部同受 TenantLine 兜底，跨租户不可见）
        WholesalerVo wholesaler = wholesalerService.getById(dto.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
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

        // 建入库单（REGISTERED）
        InboundRequest req = new InboundRequest();
        req.setId(snowflakeIdUtil.nextId());
        req.setDocNo(docNo);
        req.setWholesalerId(dto.getWholesalerId());
        req.setTenantId(tenantId);
        req.setSkuId(dto.getSkuId());
        req.setQty(dto.getQty());
        req.setPalletQty(palletQty);
        req.setStatus(InboundRequest.STATUS_REGISTERED);
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

        log.info("[C1] WK {} 登记入库 doc={} wholesaler={} sku={} +{} -> 库存={}",
                wkUserId, docNo, dto.getWholesalerId(), dto.getSkuId(), dto.getQty(), inv.getQty());

        return toVo(req, inv.getQty());
    }

    @Override
    public List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId) {
        LambdaQueryWrapper<InboundRequest> qw = new LambdaQueryWrapper<InboundRequest>()
                .eq(InboundRequest::getTenantId, tenantId)
                .eq(wholesalerId != null, InboundRequest::getWholesalerId, wholesalerId)
                .orderByDesc(InboundRequest::getCreatedAt);
        return inboundRequestMapper.selectList(qw).stream()
                .map(r -> toVo(r, null))
                .toList();
    }

    // ==================== 私有 ====================

    /**
     * S4 角色鉴权：校验用户在指定租户下具备有效 WK 角色，否则越权拒绝。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参
     * （写法对齐 A1 requireTaRole / A2 requireWaOrTa）。
     */
    private void requireWkRole(Long tenantId, Long userId) {
        long wkCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "WK")
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getStatus, "ACTIVE"));
        if (wkCount == 0) {
            throw new BizException(ErrorCode.INBOUND_OPERATOR_NOT_WK);
        }
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
                .createdAt(r.getCreatedAt())
                .build();
    }
}
