package com.cangchu.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.product.dto.SkuCreateDto;
import com.cangchu.product.dto.SkuUpdateDto;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.WholesalerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品 SKU 服务实现（phase-1 A2）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权（G-1.3/G-2.3）：写操作鉴权以 user_roles 登录态推导为唯一可信来源——
 *       操作者须为该 wholesaler 的 WA（{@code role=WA & wholesaler_id=目标 & ACTIVE}，沿用 A1 归属口径），
 *       或该租户的 TA（{@code role=TA & tenant_id=商户所属租户 & ACTIVE}）；二者皆非则拒绝。</li>
 *   <li>租户隔离（G-2.2）：skus 已纳入 MybatisPlusConfig TenantLine 白名单兜底；归属判定再以
 *       wholesaler 真实 tenant_id 为准（而非客户端传参）。跨租户/跨商户的 SKU 因 TenantLine
 *       不可见（selectById 返回 null）或鉴权失败被拒。</li>
 *   <li>S2 价格校验（G-3.1）：unit_price>0、moq_price>=0、moq_qty>=1、name 必填且长度上限，
 *       DTO Bean Validation + 本类 {@link #validatePrice} 双层。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkuServiceImpl implements SkuService {

    private final SkuMapper skuMapper;
    private final WholesalerMapper wholesalerMapper;
    private final UserRoleMapper userRoleMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional
    public SkuVo createSku(Long wholesalerId, SkuCreateDto dto, Long operatorUserId) {
        // 校验 wholesaler 存在且属当前租户（TenantLine 兜底注入 tenant 条件，跨租户不可见）
        Wholesaler wholesaler = wholesalerMapper.selectById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        // S4：operator 必须是该商户的 WA 或该租户的 TA
        requireWaOrTa(wholesaler, operatorUserId);

        // S2：名称 + 价格校验（DTO @Valid 已兜底，此处防御性 + 默认值归一）
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCode.SKU_NAME_REQUIRED);
        }
        BigDecimal moqPrice = dto.getMoqPrice() != null ? dto.getMoqPrice() : BigDecimal.ZERO;
        Integer moqQty = dto.getMoqQty() != null ? dto.getMoqQty() : 1;
        validatePrice(dto.getUnitPrice(), moqPrice, moqQty);

        Sku sku = new Sku();
        sku.setId(snowflakeIdUtil.nextId());
        // tenant_id 由 MetaObjectHandler 自动填充；显式设为商户真实租户，保证与归属一致
        sku.setTenantId(wholesaler.getTenantId());
        sku.setWholesalerId(wholesalerId);
        sku.setSpuId(dto.getSpuId());
        sku.setName(dto.getName().trim());
        sku.setSpec(dto.getSpec());
        sku.setUnitPrice(dto.getUnitPrice());
        sku.setMoqPrice(moqPrice);
        sku.setMoqQty(moqQty);
        sku.setListed(true);
        sku.setMainImage(dto.getMainImage());
        sku.setCreatedBy(operatorUserId);
        skuMapper.insert(sku);

        log.info("[A2] operator {} 为商户 {}（tenant {}）创建 SKU {}",
                operatorUserId, wholesalerId, wholesaler.getTenantId(), sku.getId());
        return toVo(sku);
    }

    @Override
    @Transactional
    public SkuVo updateSku(Long skuId, SkuUpdateDto dto, Long operatorUserId) {
        Sku sku = requireOwnedSku(skuId, operatorUserId);

        if (dto.getName() != null) {
            if (dto.getName().isBlank()) {
                throw new BizException(ErrorCode.SKU_NAME_REQUIRED);
            }
            sku.setName(dto.getName().trim());
        }
        if (dto.getSpec() != null) sku.setSpec(dto.getSpec());
        if (dto.getUnitPrice() != null) sku.setUnitPrice(dto.getUnitPrice());
        if (dto.getMoqPrice() != null) sku.setMoqPrice(dto.getMoqPrice());
        if (dto.getMoqQty() != null) sku.setMoqQty(dto.getMoqQty());
        if (dto.getMainImage() != null) sku.setMainImage(dto.getMainImage());

        // 改后整体再校验一次价格不变量（S2）
        validatePrice(sku.getUnitPrice(), sku.getMoqPrice(), sku.getMoqQty());

        sku.setUpdatedAt(LocalDateTime.now());
        skuMapper.updateById(sku);
        return toVo(sku);
    }

    @Override
    @Transactional
    public SkuVo toggleListing(Long skuId, boolean on, Long operatorUserId) {
        Sku sku = requireOwnedSku(skuId, operatorUserId);
        sku.setListed(on);
        sku.setUpdatedAt(LocalDateTime.now());
        skuMapper.updateById(sku);
        log.info("[A2] operator {} 将 SKU {} 上下架置为 listed={}", operatorUserId, skuId, on);
        return toVo(sku);
    }

    @Override
    public List<SkuVo> listByWholesaler(Long wholesalerId, Long operatorUserId) {
        Wholesaler wholesaler = wholesalerMapper.selectById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        // 商户自己看：含下架
        List<Sku> list = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getWholesalerId, wholesalerId)
                .orderByDesc(Sku::getCreatedAt));
        return list.stream().map(this::toVo).toList();
    }

    @Override
    public List<SkuVo> listByTenantForRt(Long tenantId, Long wholesalerId) {
        // 供 B2 store-front：仅 listed=true 的在售 SKU + 公开价（无专属价）。
        // 注意：本方法被 RT 入口调用时通常无可信租户上下文（TenantLine 不注入），
        // 故此处以入参 tenantId 显式 eq 兜底隔离。
        LambdaQueryWrapper<Sku> qw = new LambdaQueryWrapper<Sku>()
                .eq(Sku::getTenantId, tenantId)
                .eq(Sku::getListed, true);
        if (wholesalerId != null) {
            qw.eq(Sku::getWholesalerId, wholesalerId);
        }
        qw.orderByDesc(Sku::getCreatedAt);
        return skuMapper.selectList(qw).stream().map(this::toVo).toList();
    }

    // ==================== 私有方法 ====================

    /**
     * S4 归属鉴权：operator 须为该商户的 WA（role=WA & wholesaler_id=商户 & ACTIVE）
     * 或该商户所属租户的 TA（role=TA & tenant_id=租户 & ACTIVE）。皆非则越权拒绝。
     */
    private void requireWaOrTa(Wholesaler wholesaler, Long userId) {
        long waCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "WA")
                .eq(UserRole::getWholesalerId, wholesaler.getId())
                .eq(UserRole::getStatus, "ACTIVE"));
        if (waCount > 0) {
            return;
        }
        long taCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getTenantId, wholesaler.getTenantId())
                .eq(UserRole::getStatus, "ACTIVE"));
        if (taCount == 0) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /** 读取 SKU 并做归属鉴权；不存在（含跨租户被 TenantLine 过滤）→ SKU_NOT_FOUND。 */
    private Sku requireOwnedSku(Long skuId, Long operatorUserId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }
        Wholesaler wholesaler = wholesalerMapper.selectById(sku.getWholesalerId());
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        requireWaOrTa(wholesaler, operatorUserId);
        return sku;
    }

    /** S2 公开价不变量：unit_price>0、moq_price>=0、moq_qty>=1。 */
    private void validatePrice(BigDecimal unitPrice, BigDecimal moqPrice, Integer moqQty) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0
                || moqPrice == null || moqPrice.compareTo(BigDecimal.ZERO) < 0
                || moqQty == null || moqQty < 1) {
            throw new BizException(ErrorCode.SKU_PRICE_INVALID);
        }
    }

    private SkuVo toVo(Sku s) {
        return SkuVo.builder()
                .id(s.getId())
                .wholesalerId(s.getWholesalerId())
                .tenantId(s.getTenantId())
                .spuId(s.getSpuId())
                .name(s.getName())
                .spec(s.getSpec())
                .unitPrice(s.getUnitPrice())
                .moqPrice(s.getMoqPrice())
                .moqQty(s.getMoqQty())
                .listed(s.getListed())
                .mainImage(s.getMainImage())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
