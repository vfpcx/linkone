package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.product.service.SkuService;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.StorefrontFeature;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.StorefrontFeatureMapper;
import com.cangchu.tenant.service.StorefrontFeatureService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.StorefrontFeatureVo;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 店铺撮合配置服务实现（P5-A W4，18-p5-design §2.2/§4.3/§6；归属 tenant 域）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：getMyFeatured/saveMyFeatured 均以 user_roles 登录态推导的 TA 角色为唯一可信来源
 *       （{@link #requireTaRole}，参考 WholesalerServiceImpl.requireTaRole 写法）；storefront RT 匿名
 *       只读消费走无鉴权 {@link #getFeatured}，由调用方传入可信 tenantId/storeId。</li>
 *   <li>租户隔离：storefront_featured 已纳入 TenantLine 白名单（兜底注入 tenant_id 条件），
 *       service 内再显式 eq(tenantId) 校验归属（双保险，G-2.2）。</li>
 *   <li>跨域边界：在售 SKU 校验走 product 域 {@link SkuService}（G-S2 服务出口），
 *       批发商/店铺校验走本域 Service/Mapper，禁止跨域 mapper 直连。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorefrontFeatureServiceImpl implements StorefrontFeatureService {

    /** 主推商品数量上限（50711） */
    static final int MAIN_SKU_MAX = 20;
    /** 置顶批发商数量上限（50712） */
    static final int PIN_WA_MAX = 5;

    private final StorefrontFeatureMapper storefrontFeatureMapper;
    private final StoreMapper storeMapper;
    private final AuthService authService;
    private final WholesalerService wholesalerService;
    // 在售 SKU 校验跨域走 product 域 Service 出口（G-S2，禁 mapper 直连）
    private final SkuService skuService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    public StorefrontFeatureVo getFeatured(Long tenantId, Long storeId) {
        return StorefrontFeatureVo.builder()
                .mainSkuIds(listRefIds(tenantId, storeId, StorefrontFeature.KIND_MAIN_SKU))
                .pinWaIds(listRefIds(tenantId, storeId, StorefrontFeature.KIND_PIN_WA))
                .build();
    }

    @Override
    public StorefrontFeatureVo getMyFeatured(Long tenantId, Long operatorUserId) {
        requireTaRole(tenantId, operatorUserId);
        Store store = requireStore(tenantId);
        return getFeatured(tenantId, store.getId());
    }

    @Override
    @Transactional
    public void saveMyFeatured(Long tenantId, Long operatorUserId, List<Long> mainSkuIds, List<Long> pinWaIds) {
        requireTaRole(tenantId, operatorUserId);
        Store store = requireStore(tenantId);

        List<Long> mainSkuIdsNorm = normalize(mainSkuIds);
        List<Long> pinWaIdsNorm = normalize(pinWaIds);

        // 写前校验（幂等，不改变任何数据即直接抛错）
        if (mainSkuIdsNorm.size() > MAIN_SKU_MAX) {
            throw new BizException(ErrorCode.STOREFRONT_MAIN_SKU_LIMIT);
        }
        if (pinWaIdsNorm.size() > PIN_WA_MAX) {
            throw new BizException(ErrorCode.STOREFRONT_PIN_WA_LIMIT);
        }
        if (hasDuplicates(mainSkuIdsNorm) || hasDuplicates(pinWaIdsNorm)) {
            throw new BizException(ErrorCode.STOREFRONT_FEATURED_DUPLICATED);
        }
        validateMainSkus(tenantId, mainSkuIdsNorm);
        validatePinWas(tenantId, pinWaIdsNorm);

        // 覆盖写：DELETE (store_id, kind) 后 INSERT 新序列表，同事务
        replace(tenantId, store.getId(), StorefrontFeature.KIND_MAIN_SKU, mainSkuIdsNorm);
        replace(tenantId, store.getId(), StorefrontFeature.KIND_PIN_WA, pinWaIdsNorm);
        log.info("[W4] TA {} 覆盖保存店铺撮合配置（tenant {}，store {}）：mainSku={}，pinWa={}",
                operatorUserId, tenantId, store.getId(), mainSkuIdsNorm, pinWaIdsNorm);
    }

    // ==================== 私有方法 ====================

    private List<Long> listRefIds(Long tenantId, Long storeId, String kind) {
        return storefrontFeatureMapper.selectList(new LambdaQueryWrapper<StorefrontFeature>()
                        .eq(StorefrontFeature::getTenantId, tenantId)
                        .eq(StorefrontFeature::getStoreId, storeId)
                        .eq(StorefrontFeature::getKind, kind)
                        .orderByAsc(StorefrontFeature::getSortOrder))
                .stream()
                .map(StorefrontFeature::getRefId)
                .toList();
    }

    /**
     * 覆盖写：删除该 (store_id, kind) 的全部旧行，再按数组顺序落 sort_order 插入新行。
     */
    private void replace(Long tenantId, Long storeId, String kind, List<Long> refIds) {
        storefrontFeatureMapper.delete(new LambdaQueryWrapper<StorefrontFeature>()
                .eq(StorefrontFeature::getTenantId, tenantId)
                .eq(StorefrontFeature::getStoreId, storeId)
                .eq(StorefrontFeature::getKind, kind));
        int sortOrder = 0;
        for (Long refId : refIds) {
            StorefrontFeature row = new StorefrontFeature();
            row.setId(snowflakeIdUtil.nextId());
            row.setTenantId(tenantId);
            row.setStoreId(storeId);
            row.setKind(kind);
            row.setRefId(refId);
            row.setSortOrder(sortOrder++);
            storefrontFeatureMapper.insert(row);
        }
    }

    /**
     * 50714：主推 SKU 必须是本租户在售（listed=true）SKU。
     * 在售口径取 A2 已上架 SKU（listByTenantForRt）；库存为浏览期天然过滤，不阻断配置保存。
     */
    private void validateMainSkus(Long tenantId, List<Long> mainSkuIds) {
        if (mainSkuIds.isEmpty()) {
            return;
        }
        Set<Long> listedSkuIds = skuService.listByTenantForRt(tenantId, null).stream()
                .map(SkuVo::getId)
                .collect(Collectors.toSet());
        if (!listedSkuIds.containsAll(mainSkuIds)) {
            throw new BizException(ErrorCode.STOREFRONT_REF_INVALID);
        }
    }

    /**
     * 50714：置顶批发商必须是本店入驻且 ACTIVE 的批发商（与店铺前台可见口径一致）。
     */
    private void validatePinWas(Long tenantId, List<Long> pinWaIds) {
        if (pinWaIds.isEmpty()) {
            return;
        }
        Set<Long> activeWaIds = wholesalerService.listByTenant(tenantId).stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .map(WholesalerVo::getId)
                .collect(Collectors.toSet());
        if (!activeWaIds.containsAll(pinWaIds)) {
            throw new BizException(ErrorCode.STOREFRONT_REF_INVALID);
        }
    }

    /**
     * S4 角色鉴权：校验用户在指定租户下具备有效 TA 角色，否则抛越权。
     * user_roles 归 account 域，走 AuthService（语义等价：role=TA & tenant_id & ACTIVE）。
     */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * 店铺上下文：tenant 与 store 1:1，取本租户店铺；未建仓（无店铺）视为无店铺上下文。
     */
    private Store requireStore(Long tenantId) {
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (store == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的店铺，请先完成建仓");
        }
        return store;
    }

    private List<Long> normalize(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).toList();
    }

    private boolean hasDuplicates(List<Long> ids) {
        return ids.size() != new HashSet<>(ids).size();
    }
}
