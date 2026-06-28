package com.cangchu.storefront.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
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
import com.cangchu.tenant.service.WholesalerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
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

    @Override
    public StoreFrontVo getStorePage(Long storeId, String code) {
        ResolvedStore rs = resolve(storeId, code);

        List<StoreWholesalerVo> wholesalers = aggregateWholesalers(rs.tenantId(), null);

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
                .build();
    }

    @Override
    public List<StoreWholesalerVo> listWholesalers(Long storeId, String code) {
        ResolvedStore rs = resolve(storeId, code);
        // 不含 SKU 的轻量列表：仅店内 ACTIVE 批发商
        return wholesalerService.listByTenant(rs.tenantId()).stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .map(w -> StoreWholesalerVo.builder()
                        .wholesalerId(w.getId())
                        .name(w.getName())
                        .intro(w.getIntro())
                        .status(w.getStatus())
                        .skus(List.of())
                        .build())
                .toList();
    }

    @Override
    public List<StoreSkuVo> listSkus(Long storeId, String code, Long wholesalerId) {
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
        return buildOnSaleSkus(rs.tenantId(), wholesalerId);
    }

    // ==================== 聚合 ====================

    /**
     * 聚合店内（仅 ACTIVE）批发商 + 各自在售 SKU。
     * @param wholesalerId 可空；非空则仅聚合该商户
     */
    private List<StoreWholesalerVo> aggregateWholesalers(Long tenantId, Long wholesalerId) {
        return wholesalerService.listByTenant(tenantId).stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .filter(w -> wholesalerId == null || w.getId().equals(wholesalerId))
                .map(w -> StoreWholesalerVo.builder()
                        .wholesalerId(w.getId())
                        .name(w.getName())
                        .intro(w.getIntro())
                        .status(w.getStatus())
                        .skus(buildOnSaleSkus(tenantId, w.getId()))
                        .build())
                .toList();
    }

    /**
     * 某商户的在售 SKU：listed=true（A2 listByTenantForRt 已保证）且 库存 qty>0。
     * tenantId 显式传入下游做隔离；库存按 skuId 关联，再次核对 tenantId 一致。
     */
    private List<StoreSkuVo> buildOnSaleSkus(Long tenantId, Long wholesalerId) {
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

        return listedSkus.stream()
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
                        .build())
                .toList();
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

        String storeCode = resolveStoreCode(tenantId);
        return new ResolvedStore(store, tenantId, storeCode);
    }

    /** 店铺码 = 租户简码 tenantSimpleCode（与 TA 端 getStoreQr 一致口径）。 */
    private String resolveStoreCode(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null ? tenant.getTenantSimpleCode() : null;
    }
}
