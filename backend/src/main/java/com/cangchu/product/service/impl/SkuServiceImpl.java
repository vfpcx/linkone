package com.cangchu.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.product.dto.SkuCreateDto;
import com.cangchu.product.dto.SkuUpdateDto;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.entity.Spu;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.service.SpuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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
    private final SpuService spuService;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional
    public SkuVo createSku(Long wholesalerId, SkuCreateDto dto, Long operatorUserId) {
        // 校验 wholesaler 存在且属当前租户（走 tenant 域 Service，隔离行为等同原 selectById）
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
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
        // P5-D D56（22 §3.2）：可选挂接平台标品——spuId 非空时校验 ACTIVE 并写名称/品类快照
        //（编辑不改挂接，16 §2.3；历史自由 spec 文本保留）
        applySpuSnapshot(sku, dto.getSpuId());
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

        // §10 并发一致性：partial update——只 set 本次真正传入(非空)的列，不再整实体 updateById
        // 覆盖。原语义为"非空才改"（DTO 字段为 null 即保持不变），此处逐字段等价迁移到
        // LambdaUpdateWrapper；内存 sku 副本同步合并值，仅用于价格不变量校验与返回 VO，对外行为不变。
        // 这样"改价"与"上下架"并发时各自只写自己那列，互不覆盖。
        LambdaUpdateWrapper<Sku> uw = new LambdaUpdateWrapper<Sku>().eq(Sku::getId, skuId);

        if (dto.getName() != null) {
            if (dto.getName().isBlank()) {
                throw new BizException(ErrorCode.SKU_NAME_REQUIRED);
            }
            String name = dto.getName().trim();
            sku.setName(name);
            uw.set(Sku::getName, name);
        }
        if (dto.getSpec() != null) {
            sku.setSpec(dto.getSpec());
            uw.set(Sku::getSpec, dto.getSpec());
        }
        if (dto.getUnitPrice() != null) {
            sku.setUnitPrice(dto.getUnitPrice());
            uw.set(Sku::getUnitPrice, dto.getUnitPrice());
        }
        if (dto.getMoqPrice() != null) {
            sku.setMoqPrice(dto.getMoqPrice());
            uw.set(Sku::getMoqPrice, dto.getMoqPrice());
        }
        if (dto.getMoqQty() != null) {
            sku.setMoqQty(dto.getMoqQty());
            uw.set(Sku::getMoqQty, dto.getMoqQty());
        }
        if (dto.getMainImage() != null) {
            sku.setMainImage(dto.getMainImage());
            uw.set(Sku::getMainImage, dto.getMainImage());
        }

        // 改后整体再校验一次价格不变量（S2）——基于合并后的有效值，行为同原
        validatePrice(sku.getUnitPrice(), sku.getMoqPrice(), sku.getMoqQty());

        LocalDateTime now = LocalDateTime.now();
        sku.setUpdatedAt(now);
        uw.set(Sku::getUpdatedAt, now);
        skuMapper.update(null, uw);
        return toVo(sku);
    }

    @Override
    @Transactional
    public SkuVo toggleListing(Long skuId, boolean on, Long operatorUserId) {
        Sku sku = requireOwnedSku(skuId, operatorUserId);
        // 上下架（可逆）——注意：**不**级联作废专属价。下架是临时行为，重新上架后专属价应仍有效。
        // TODO Wave-later: 当新增「删除 SKU」（不可逆软删/hard delete）操作时，在该删除路径调用
        //   pricingService.disableBySku(skuId) 级联作废专属价；退驻/RT 注销亦在 later wave。
        //   现阶段 SkuService 无删除操作，disableBySku 能力已就绪但无触发点。
        // §10 并发一致性：partial update——只 set listed + updated_at，不覆盖 price 等其它列，
        // 避免与并发"改价"(updateSku)用旧快照整实体覆盖互相丢改动。
        LocalDateTime now = LocalDateTime.now();
        skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getId, skuId)
                .set(Sku::getListed, on)
                .set(Sku::getUpdatedAt, now));
        sku.setListed(on);
        sku.setUpdatedAt(now);
        log.info("[A2] operator {} 将 SKU {} 上下架置为 listed={}", operatorUserId, skuId, on);
        return toVo(sku);
    }

    @Override
    @Transactional
    public int delistAllByWholesaler(Long wholesalerId) {
        // R13 副作用链数据级联：批量 partial update（只 set listed/updated_at），
        // 调用方（tenant 域退驻审批）已完成 S4 鉴权与状态机校验，此处不再鉴权。
        int affected = skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getWholesalerId, wholesalerId)
                .eq(Sku::getListed, true)
                .set(Sku::getListed, false)
                .set(Sku::getUpdatedAt, LocalDateTime.now()));
        log.info("[P2][R13] 商户 {} 退驻级联下架 SKU {} 行", wholesalerId, affected);
        return affected;
    }

    @Override
    public List<SkuVo> listByWholesaler(Long wholesalerId, Long operatorUserId) {
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        // P3b T1-BE（13 §5.1 补口，上线检查单 §5-4 遗留）：只读列表放宽为 WK/WA/TA——
        // WK 代建选货需 SKU 名称展示；写路径（create/update/toggle）维持 WA/TA 不放宽
        requireWkOrWaOrTa(wholesaler, operatorUserId);
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

    @Override
    public SkuVo getById(Long skuId) {
        // 只读跨域出口（G-S1/G-S2）：内部同经 skuMapper.selectById，隔离行为与原直连一致
        // （受 TenantLine 兜底），跨租户不可见返回 null；归属核对留给调用方。
        Sku sku = skuMapper.selectById(skuId);
        return sku == null ? null : toVo(sku);
    }

    @Override
    public List<SkuVo> listForRtBySkuIds(Long tenantId, Long wholesalerId, Collection<Long> skuIds) {
        // C1 RT 价目（23-p5-c-c1 §5.1）：含下架的批量快照。RT 无 TenantContext → 显式 eq 隔离；
        // 只回属于 (tenantId, wholesalerId) 的行，越店/越商户的 skuId 一律不出现（不泄漏）。
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getTenantId, tenantId)
                        .eq(Sku::getWholesalerId, wholesalerId)
                        .in(Sku::getId, skuIds))
                .stream().map(this::toVo).toList();
    }

    // ==================== 私有方法 ====================

    /**
     * 挂接平台标品并写名称/品类快照（P5-D D56，22 §3.2）。
     * spuId 为空 = 不挂接（清空快照）；非空 → ACTIVE 校验（不存在 SPU_NOT_FOUND / 非 ACTIVE
     * SPU_NOT_LINKABLE）后快照随标品落库。S4 鉴权由本方法调用方（createSku）完成。
     */
    private void applySpuSnapshot(Sku sku, Long spuId) {
        if (spuId == null) {
            sku.setSpuId(null);
            sku.setSpuName(null);
            sku.setSpuCategoryL1(null);
            sku.setSpuCategoryL2(null);
            return;
        }
        Spu spu = spuService.requireLinkable(spuId);
        sku.setSpuId(spu.getId());
        sku.setSpuName(spu.getName());
        sku.setSpuCategoryL1(spu.getCategoryL1());
        sku.setSpuCategoryL2(spu.getCategoryL2());
    }

    /**
     * S4 归属鉴权：operator 须为该商户的 WA（role=WA & wholesaler_id=商户 & ACTIVE）
     * 或该商户所属租户的 TA（role=TA & tenant_id=租户 & ACTIVE）。皆非则越权拒绝。
     */
    private void requireWaOrTa(WholesalerVo wholesaler, Long userId) {
        // WA（批发商维度）或该商户所属租户的 TA；语义与原 user_roles 直连逐一等价
        if (authService.hasWholesalerRole(userId, "WA", wholesaler.getId())) {
            return;
        }
        if (!authService.hasRole(userId, "TA", wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * S4 只读列表鉴权（P3b T1-BE，13 §5.1）：WA 归属 / TA 同租户 / <b>WK 同租户</b>（只读放宽，
     * requireWkOrTa 写法先例见 OutboundRequestServiceImpl）。写路径禁用本方法。
     */
    private void requireWkOrWaOrTa(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, "WA", wholesaler.getId())) {
            return;
        }
        if (authService.hasRole(userId, "TA", wholesaler.getTenantId())
                || authService.hasRole(userId, "WK", wholesaler.getTenantId())) {
            return;
        }
        throw new BizException(ErrorCode.PERMISSION_TENANT_001);
    }

    /** 读取 SKU 并做归属鉴权；不存在（含跨租户被 TenantLine 过滤）→ SKU_NOT_FOUND。 */
    private Sku requireOwnedSku(Long skuId, Long operatorUserId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.SKU_NOT_FOUND);
        }
        WholesalerVo wholesaler = wholesalerService.getById(sku.getWholesalerId());
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
                .spuName(s.getSpuName())
                .spuCategoryL1(s.getSpuCategoryL1())
                .spuCategoryL2(s.getSpuCategoryL2())
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
