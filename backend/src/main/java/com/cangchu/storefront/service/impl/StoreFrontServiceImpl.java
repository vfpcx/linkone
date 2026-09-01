package com.cangchu.storefront.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import com.cangchu.pricing.service.PricingService;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.storefront.vo.StoreFrontVo;
import com.cangchu.storefront.vo.StoreSkuVo;
import com.cangchu.storefront.vo.StoreWholesalerVo;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.service.StorefrontFeatureService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.StorefrontFeatureVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RT 店铺前台聚合服务实现（phase-1 B2 · 只读）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>G-2.1/G-2.4 租户隔离（关键）：RT 无登录态 → TenantContext 为 null → TenantLine 兜底不注入条件。
 *       因此本服务**不依赖**全局兜底，而是先把 storeId/code 解析为可信 {@code tenantId}（来自 stores/tenants
 *       这两张表的真实归属），随后对店内 WA / SKU / 库存的所有查询都以该 tenantId 显式过滤
 *       （WA 用 {@link WholesalerService#listByTenant}，SKU 用 {@link SkuService#listByTenantForRt} 的
 *       tenantId 入参），并在装配库存时再次校验 inventory.tenantId == 解析租户，三重防跨店泄漏。
 *       <b>绝不</b>接受 RT 传 X-Tenant-Id 决定数据范围（store→tenant 映射才是唯一可信来源）。</li>
 *   <li>只读：不建表、不改既有业务；仅复用 A1/A2/B1 已交付 service。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreFrontServiceImpl implements StoreFrontService {

    private final StoreMapper storeMapper;
    private final TenantMapper tenantMapper;
    private final WholesalerService wholesalerService;
    private final SkuService skuService;
    private final InventoryService inventoryService;
    private final PricingService pricingService;
    // P5-A W4：撮合配置读 tenant 域 Service 出口（G-S2，禁跨域 mapper 直连 / 禁跨域 entity 直用）
    private final StorefrontFeatureService storefrontFeatureService;

    @Override
    public StoreFrontVo getStorePage(Long storeId, String code) {
        return getStorePage(storeId, code, null);
    }

    @Override
    public StoreFrontVo getStorePage(Long storeId, String code, String rtPhone) {
        ResolvedStore rs = resolve(storeId, code);
        StorefrontFeatureVo featured = storefrontFeatureService.getFeatured(rs.tenantId(), rs.store().getId());

        List<StoreWholesalerVo> wholesalers = aggregateWholesalers(rs.tenantId(), null, rtPhone, featured);

        return StoreFrontVo.builder()
                .storeId(rs.store().getId())
                .tenantId(rs.tenantId())
                .storeCode(rs.storeCode())
                .storeName(rs.store().getName())
                .intro(rs.store().getIntro())
                .coverUrl(rs.store().getCoverUrl())
                .businessHours(rs.store().getBusinessHours())
                .status(rs.store().getStatus())
                .wholesalers(wholesalers)
                .featuredSkuIds(featured.getMainSkuIds())
                .pinnedWholesalerIds(featured.getPinWaIds())
                .build();
    }

    @Override
    public List<StoreWholesalerVo> listWholesalers(Long storeId, String code) {
        ResolvedStore rs = resolve(storeId, code);
        StorefrontFeatureVo featured = storefrontFeatureService.getFeatured(rs.tenantId(), rs.store().getId());
        // 不含 SKU 的轻量列表：仅店内 ACTIVE 批发商（置顶前置）
        List<StoreWholesalerVo> wholesalers = wholesalerService.listByTenant(rs.tenantId()).stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .map(w -> StoreWholesalerVo.builder()
                        .wholesalerId(w.getId())
                        .name(w.getName())
                        .intro(w.getIntro())
                        .status(w.getStatus())
                        .skus(List.of())
                        .pinned(isPinned(featured, w.getId()))
                        .build())
                .toList();
        return reorderPinnedFirst(wholesalers, featured);
    }

    @Override
    public List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId) {
        return listSkus(storeId, code, wholesalerId, null);
    }

    @Override
    public List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId, String rtPhone) {
        ResolvedStore rs = resolve(storeId, code);
        if (wholesalerId == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "wholesalerId 不能为空");
        }
        // 校验该商户确属本店租户（防越店拉取他店商户 SKU）
        boolean inStore = wholesalerService.listByTenant(rs.tenantId()).stream()
                .anyMatch(w -> "ACTIVE".equals(w.getStatus()) && w.getId().equals(wholesalerId));
        if (!inStore) {
            // 不属于本店或非 ACTIVE：返回空，不泄漏跨店信息
            return List.of();
        }
        StorefrontFeatureVo featured = storefrontFeatureService.getFeatured(rs.tenantId(), rs.store().getId());
        return buildOnSaleSkus(rs.tenantId(), wholesalerId, rtPhone, featured);
    }

    // ==================== 聚合 ====================

    /**
     * 聚合店内（仅 ACTIVE）批发商 + 各自在售 SKU。
     * @param wholesalerId 可空；非空则仅聚合该商户
     * @param rtPhone      可空；已登录 RT 手机号，用于解析各 SKU 的专属价 matchedPrice
     * @param featured     P5-A W4 撮合配置（可空=无配置，行为与旧版一致）
     */
    private List<StoreWholesalerVo> aggregateWholesalers(Long tenantId, Long wholesalerId, String rtPhone,
                                                         StorefrontFeatureVo featured) {
        List<StoreWholesalerVo> wholesalers = wholesalerService.listByTenant(tenantId).stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .filter(w -> wholesalerId == null || w.getId().equals(wholesalerId))
                .map(w -> StoreWholesalerVo.builder()
                        .wholesalerId(w.getId())
                        .name(w.getName())
                        .intro(w.getIntro())
                        .status(w.getStatus())
                        .skus(buildOnSaleSkus(tenantId, w.getId(), rtPhone, featured))
                        .pinned(isPinned(featured, w.getId()))
                        .build())
                .toList();
        return reorderPinnedFirst(wholesalers, featured);
    }

    /**
     * 某商户的在售 SKU：listed=true（A2 listByTenantForRt 已保证）且 库存 qty>0。
     * tenantId 显式传入下游做隔离；库存按 skuId 关联，再次核对 tenantId 一致。
     *
     * <p>P2 定价 Wave 3b（可选鉴权）：{@code rtPhone} 非空时对每个 SKU 经 {@link PricingService#resolvePrice}
     * （qty=1）解析成交价；若解析价与公开单价不同（=命中有效专属价）则填 matchedPrice，unitPrice 恒保持公开价。
     * {@code rtPhone} 为空（匿名）→ resolvePrice 直接回退公开价，matchedPrice 恒 null。
     *
     * @param rtPhone 已登录 RT 手机号；匿名传 null
     */
    List<StoreSkuVo> buildOnSaleSkus(Long tenantId, Long wholesalerId, String rtPhone, StorefrontFeatureVo featured) {
        // A2：仅 listed=true + 公开价，按 tenantId 显式隔离
        List<SkuVo> listedSkus = skuService.listByTenantForRt(tenantId, wholesalerId);
        if (listedSkus.isEmpty()) {
            return List.of();
        }
        // B1：该商户当前有货（qty>0）的库存，按 skuId 建索引；核对 tenantId 防跨店
        Map<Long, Integer> stockBySku = inventoryService.listInStockSkusFor(wholesalerId).stream()
                .filter(inv -> tenantId.equals(inv.getTenantId()))
                .filter(inv -> inv.getQty() != null && inv.getQty() > 0)
                .collect(Collectors.toMap(InventoryVo::getSkuId, InventoryVo::getQty, (a, b) -> a));

        List<Long> featuredSkuIds = featured != null ? featured.getMainSkuIds() : null;
        Set<Long> featuredSet = featuredSkuIds == null ? Set.of() : new HashSet<>(featuredSkuIds);

        List<StoreSkuVo> skus = listedSkus.stream()
                .filter(sku -> stockBySku.containsKey(sku.getId()))   // 库存 qty>0 才算在售
                .map(sku -> StoreSkuVo.builder()
                        .skuId(sku.getId())
                        .wholesalerId(sku.getWholesalerId())
                        .name(sku.getName())
                        .spec(sku.getSpec())
                        .mainImage(sku.getMainImage())
                        .unitPrice(sku.getUnitPrice())
                        .moqPrice(sku.getMoqPrice())
                        .moqQty(sku.getMoqQty())
                        .stockQty(stockBySku.get(sku.getId()))
                        .matchedPrice(resolveMatchedPrice(wholesalerId, sku, rtPhone))
                        .featured(featuredSet.contains(sku.getId()))
                        .build())
                .toList();
        return reorderFeaturedFirst(skus, featuredSkuIds);
    }

    /**
     * 置顶批发商前置：按配置顺序（sort_order）把店内存在的置顶批发商提到列表最前，未置顶保持原序。
     * 无配置 / 空配置时原样返回（行为与旧版一致）。
     */
    private List<StoreWholesalerVo> reorderPinnedFirst(List<StoreWholesalerVo> wholesalers, StorefrontFeatureVo featured) {
        if (featured == null || featured.getPinWaIds() == null || featured.getPinWaIds().isEmpty()) {
            return wholesalers;
        }
        List<Long> pinnedIds = featured.getPinWaIds();
        Set<Long> pinnedSet = new HashSet<>(pinnedIds);
        List<StoreWholesalerVo> ordered = new ArrayList<>(wholesalers.size());
        for (Long id : pinnedIds) {
            for (StoreWholesalerVo w : wholesalers) {
                if (id.equals(w.getWholesalerId())) {
                    ordered.add(w);
                    break;
                }
            }
        }
        for (StoreWholesalerVo w : wholesalers) {
            if (!pinnedSet.contains(w.getWholesalerId())) {
                ordered.add(w);
            }
        }
        return ordered;
    }

    /**
     * 主推 SKU 前置：按配置顺序（sort_order）把在售的主推 SKU 提到该商户列表最前，未主推保持原序。
     * 无配置 / 空配置时原样返回（行为与旧版一致）。
     */
    private List<StoreSkuVo> reorderFeaturedFirst(List<StoreSkuVo> skus, List<Long> featuredSkuIds) {
        if (featuredSkuIds == null || featuredSkuIds.isEmpty()) {
            return skus;
        }
        Set<Long> featuredSet = new HashSet<>(featuredSkuIds);
        List<StoreSkuVo> ordered = new ArrayList<>(skus.size());
        for (Long id : featuredSkuIds) {
            for (StoreSkuVo s : skus) {
                if (id.equals(s.getSkuId())) {
                    ordered.add(s);
                    break;
                }
            }
        }
        for (StoreSkuVo s : skus) {
            if (!featuredSet.contains(s.getSkuId())) {
                ordered.add(s);
            }
        }
        return ordered;
    }

    private boolean isPinned(StorefrontFeatureVo featured, Long wholesalerId) {
        return featured != null && featured.getPinWaIds() != null && featured.getPinWaIds().contains(wholesalerId);
    }

    /**
     * 解析该 SKU 对该 RT 的专属价（叠加字段）：匿名或未命中专属价 → null；命中且不同于公开单价 → 专属价。
     *
     * <p>浏览态无数量语境，qty 恒取 1（走单价而非起批价）；故仅当存在真正的客户专属价时
     * resolved 才可能 ≠ 公开单价，据此把"命中专属价"与"回退公开价"区分开。
     */
    private BigDecimal resolveMatchedPrice(Long wholesalerId, SkuVo sku, String rtPhone) {
        if (rtPhone == null || rtPhone.isBlank()) {
            return null;
        }
        BigDecimal resolved = pricingService.resolvePrice(wholesalerId, sku.getId(), rtPhone, 1);
        // resolved 与公开单价一致 → 无专属价（或专属价恰等于公开价），不展示 matchedPrice
        if (resolved == null || sku.getUnitPrice() == null
                || resolved.compareTo(sku.getUnitPrice()) == 0) {
            return null;
        }
        return resolved;
    }

    // ==================== 进店解析（storeId / code → tenant） ====================

    private record ResolvedStore(Store store, Long tenantId, String storeCode) {}

    /**
     * 进店解析：storeId 优先，否则用店铺码(code=tenantSimpleCode)。
     * 解析得到的 tenantId 来自 stores/tenants 真实归属，是后续隔离的唯一可信来源。
     */
    private ResolvedStore resolve(Long storeId, String code) {
        Store store;
        Long tenantId;

        if (storeId != null) {
            // 注：RT 无 TenantContext，stores 的 TenantLine 兜底此时不注入条件，故可全局按 id 解析
            store = storeMapper.selectById(storeId);
            if (store == null) {
                throw new BizException(ErrorCode.STORE_NOT_FOUND);
            }
            tenantId = store.getTenantId();
        } else if (StringUtils.hasText(code)) {
            Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                    .eq(Tenant::getTenantSimpleCode, code.trim())
                    .last("LIMIT 1"));
            if (tenant == null) {
                throw new BizException(ErrorCode.STORE_NOT_FOUND);
            }
            tenantId = tenant.getId();
            store = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                    .eq(Store::getTenantId, tenantId)
                    .last("LIMIT 1"));
            if (store == null) {
                throw new BizException(ErrorCode.STORE_NOT_FOUND);
            }
        } else {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "storeId 与 code 至少传一个");
        }

        // 显式拒绝非 ACTIVE 仓进店：仅已审核通过(ACTIVE)的仓库可被 RT 扫码进店，
        // 不再靠"无 ACTIVE 商户=空店"隐性兜底（PENDING/REJECTED 仓一律 STORE_NOT_FOUND，不泄漏存在性）
        Tenant owner = tenantMapper.selectById(tenantId);
        if (owner == null || !"ACTIVE".equals(owner.getStatus())) {
            throw new BizException(ErrorCode.STORE_NOT_FOUND);
        }

        String storeCode = resolveStoreCode(tenantId);
        return new ResolvedStore(store, tenantId, storeCode);
    }

    /** 店铺码 = 租户简码 tenantSimpleCode（与 TA 端 getStoreQr 一致口径）。 */
    private String resolveStoreCode(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null ? tenant.getTenantSimpleCode() : null;
    }
}
