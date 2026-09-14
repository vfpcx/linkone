package com.cangchu.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.product.catalog.SpuCatalog;
import com.cangchu.product.dto.MerchantSpuCreateDto;
import com.cangchu.product.dto.MerchantSpuUpdateDto;
import com.cangchu.product.dto.SkuGenerateDto;
import com.cangchu.product.dto.SpecDimensionDto;
import com.cangchu.product.dto.SpuSpecItemDto;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.entity.Spu;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.mapper.SpuMapper;
import com.cangchu.product.service.MerchantSpuService;
import com.cangchu.product.spec.SpuSpecSchemaSupport;
import com.cangchu.product.vo.SkuVo;
import com.cangchu.product.vo.SpuCategoryGroupVo;
import com.cangchu.product.vo.SpuVo;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商户自建聚合 SPU 服务实现（P6 商品规格模型，V42）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>归属：TENANT 行显式落 tenant_id（取商户真实租户，非客户端传参）+ wholesaler_id；
 *       编码自动 TSPU-&lt;雪花&gt;（复用 uk_spu_code 全局唯一）。</li>
 *   <li>隔离：spus 未纳入 TenantLine，所有按 id 的读写均先 {@code loadOwnedTenantSpu}
 *       做「TENANT 归属 + 所属商户在本租户可见（wholesalers 受 TenantLine 过滤）」双查，
 *       失败统一 50735（防枚举）。</li>
 *   <li>组合唯一：同 SPU 下 spec_key 唯一——service 层先查先判（集合比对，友好 50733），
 *       并由 V42 唯一键 uk_sku_spu_spec 做并发兜底（DuplicateKeyException 同样映射 50733）。</li>
 *   <li>价格：复用 phase-1 不变量（单价&gt;0、起批价&gt;=0、起批量&gt;=1），支持逐组合覆盖。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantSpuServiceImpl implements MerchantSpuService {

    private static final String ROLE_WA = "WA";
    private static final String ROLE_TA = "TA";
    private static final String ROLE_WK = "WK";
    private static final int SKU_NAME_MAX_LEN = 128;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final WholesalerService wholesalerService;
    private final AuthService authService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final SpuSpecSchemaSupport specSchemaSupport;

    // ==================================================================
    // 写：创建 / 更新 / 批量生成 / 下架
    // ==================================================================

    @Override
    @Transactional
    public SpuVo create(Long wholesalerId, MerchantSpuCreateDto dto, Long operatorId) {
        WholesalerVo wholesaler = requireWholesaler(wholesalerId);
        requireWaOrTa(wholesaler, operatorId);

        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCode.SPU_NAME_REQUIRED);
        }
        if (!SpuCatalog.validL2(dto.getCategoryL1(), dto.getCategoryL2())) {
            throw new BizException(ErrorCode.SPU_CATEGORY_INVALID);
        }
        // 规格模板规范化 + 上限校验（50730/50731/50732）
        List<SpecDimensionDto> schema = specSchemaSupport.normalize(dto.getSpecSchema());

        Spu spu = new Spu();
        spu.setId(snowflakeIdUtil.nextId());
        spu.setOwnerType(Spu.OWNER_TENANT);
        spu.setTenantId(wholesaler.getTenantId());
        spu.setWholesalerId(wholesalerId);
        spu.setSpuCode("TSPU-" + snowflakeIdUtil.nextId());
        spu.setName(dto.getName().trim());
        spu.setCategoryL1(dto.getCategoryL1());
        spu.setCategoryL2(dto.getCategoryL2());
        spu.setBrand(dto.getBrand());
        spu.setStandardImageUrl(dto.getStandardImageUrl());
        spu.setNote(dto.getNote());
        spu.setSpecSchema(specSchemaSupport.writeSchema(schema));
        spu.setStatus(Spu.STATUS_ACTIVE);
        spu.setCreatedBy(operatorId);
        spuMapper.insert(spu);

        log.info("[P6] operator {} 为商户 {}（tenant {}）创建自建聚合 SPU {}（{} 维规格，{} 组合）",
                operatorId, wholesalerId, wholesaler.getTenantId(), spu.getId(),
                schema.size(), specSchemaSupport.combinationCount(schema));
        return toVo(spu, 0L);
    }

    @Override
    @Transactional
    public SpuVo update(Long spuId, MerchantSpuUpdateDto dto, Long operatorId) {
        Spu spu = requireOwnedForWrite(spuId, operatorId);
        if (!Spu.STATUS_ACTIVE.equals(spu.getStatus())) {
            throw new BizException(ErrorCode.SPU_STATE_INVALID);
        }

        LambdaUpdateWrapper<Spu> uw = new LambdaUpdateWrapper<Spu>().eq(Spu::getId, spuId);
        boolean snapshotDirty = false;

        if (dto.getName() != null) {
            if (dto.getName().isBlank()) {
                throw new BizException(ErrorCode.SPU_NAME_REQUIRED);
            }
            String name = dto.getName().trim();
            spu.setName(name);
            uw.set(Spu::getName, name);
            snapshotDirty = true;
        }
        if (dto.getCategoryL1() != null || dto.getCategoryL2() != null) {
            // 品类必须成对满足平台字典（只传一个时另一维沿用原值）
            String l1 = dto.getCategoryL1() != null ? dto.getCategoryL1() : spu.getCategoryL1();
            String l2 = dto.getCategoryL2() != null ? dto.getCategoryL2() : spu.getCategoryL2();
            if (!SpuCatalog.validL2(l1, l2)) {
                throw new BizException(ErrorCode.SPU_CATEGORY_INVALID);
            }
            spu.setCategoryL1(l1);
            spu.setCategoryL2(l2);
            uw.set(Spu::getCategoryL1, l1).set(Spu::getCategoryL2, l2);
            snapshotDirty = true;
        }
        if (dto.getBrand() != null) {
            uw.set(Spu::getBrand, dto.getBrand());
            spu.setBrand(dto.getBrand());
        }
        if (dto.getStandardImageUrl() != null) {
            uw.set(Spu::getStandardImageUrl, dto.getStandardImageUrl());
            spu.setStandardImageUrl(dto.getStandardImageUrl());
        }
        if (dto.getNote() != null) {
            uw.set(Spu::getNote, dto.getNote());
            spu.setNote(dto.getNote());
        }
        List<SpecDimensionDto> schema = null;
        if (dto.getSpecSchema() != null) {
            schema = specSchemaSupport.normalize(dto.getSpecSchema());
            String json = specSchemaSupport.writeSchema(schema);
            spu.setSpecSchema(json);
            uw.set(Spu::getSpecSchema, json);
        }

        LocalDateTime now = LocalDateTime.now();
        uw.set(Spu::getUpdatedAt, now);
        spuMapper.update(null, uw);

        // 名称/品类变更同步存量 SKU 快照（免 join 列表展示口径与 D56 合并一致）
        if (snapshotDirty) {
            int affected = skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                    .eq(Sku::getSpuId, spuId)
                    .set(Sku::getSpuName, spu.getName())
                    .set(Sku::getSpuCategoryL1, spu.getCategoryL1())
                    .set(Sku::getSpuCategoryL2, spu.getCategoryL2())
                    .set(Sku::getUpdatedAt, now));
            log.info("[P6] 自建 SPU {} 名称/品类变更，刷新 {} 个 SKU 快照", spuId, affected);
        }
        log.info("[P6] operator {} 更新自建聚合 SPU {}", operatorId, spuId);
        return toVo(spu, countRef(spuId));
    }

    @Override
    @Transactional
    public List<SkuVo> generateSkus(Long spuId, SkuGenerateDto dto, Long operatorId) {
        Spu spu = requireOwnedForWrite(spuId, operatorId);
        if (!Spu.STATUS_ACTIVE.equals(spu.getStatus())) {
            throw new BizException(ErrorCode.SPU_STATE_INVALID);
        }
        List<SpecDimensionDto> schema = specSchemaSupport.readSchema(spu.getSpecSchema());
        if (schema.isEmpty()) {
            throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_INVALID);
        }

        // 1) 组合来源：显式 items（逐组合覆盖价格）或完整笛卡尔积
        List<SpuSpecItemDto> items = dto.getItems();
        boolean explicit = items != null && !items.isEmpty();
        List<LinkedHashMap<String, String>> combos = new ArrayList<>();
        if (explicit) {
            if (items.size() > SpuSpecSchemaSupport.MAX_COMBINATIONS) {
                throw new BizException(ErrorCode.SPU_SPEC_SCHEMA_LIMIT);
            }
            for (SpuSpecItemDto item : items) {
                combos.add(specSchemaSupport.matchCombo(item.getOptions(), schema));
            }
        } else {
            combos = specSchemaSupport.expand(schema);
        }
        if (combos.isEmpty()) {
            throw new BizException(ErrorCode.SPU_SKU_GEN_EMPTY);
        }

        // 2) 默认价 + 不变量
        BigDecimal defaultUnitPrice = dto.getUnitPrice();
        BigDecimal defaultMoqPrice = dto.getMoqPrice() != null ? dto.getMoqPrice() : BigDecimal.ZERO;
        Integer defaultMoqQty = dto.getMoqQty() != null ? dto.getMoqQty() : 1;
        validatePrice(defaultUnitPrice, defaultMoqPrice, defaultMoqQty);

        // 3) 存量组合（同 SPU 同商户；显式双 eq 保证不受 TenantContext 缺失影响）
        Set<String> existing = new HashSet<>();
        List<Sku> siblings = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getTenantId, spu.getTenantId())
                .eq(Sku::getWholesalerId, spu.getWholesalerId()));
        for (Sku s : siblings) {
            if (s.getSpecKey() != null) {
                existing.add(s.getSpecKey());
            }
        }

        // 4) 逐组合落库（重复 → 50733，整单回滚）
        boolean listed = dto.getListed() == null || dto.getListed();
        String mainImage = dto.getMainImage() != null ? dto.getMainImage() : spu.getStandardImageUrl();
        List<SkuVo> created = new ArrayList<>(combos.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < combos.size(); i++) {
            LinkedHashMap<String, String> combo = combos.get(i);
            String specKey = specSchemaSupport.toSpecKey(combo);
            if (existing.contains(specKey) || !seen.add(specKey)) {
                throw new BizException(ErrorCode.SPU_SPEC_COMBO_DUPLICATED);
            }

            SpuSpecItemDto item = explicit ? items.get(i) : null;
            BigDecimal unitPrice = item != null && item.getUnitPrice() != null ? item.getUnitPrice() : defaultUnitPrice;
            BigDecimal moqPrice = item != null && item.getMoqPrice() != null ? item.getMoqPrice() : defaultMoqPrice;
            Integer moqQty = item != null && item.getMoqQty() != null ? item.getMoqQty() : defaultMoqQty;
            validatePrice(unitPrice, moqPrice, moqQty);

            String summary = specSchemaSupport.summary(combo);
            Sku sku = new Sku();
            sku.setId(snowflakeIdUtil.nextId());
            sku.setTenantId(spu.getTenantId());
            sku.setWholesalerId(spu.getWholesalerId());
            sku.setSpuId(spu.getId());
            sku.setSpuName(spu.getName());
            sku.setSpuCategoryL1(spu.getCategoryL1());
            sku.setSpuCategoryL2(spu.getCategoryL2());
            sku.setName(buildSkuName(spu.getName(), summary));
            sku.setSpec(summary);
            sku.setSpecKey(specKey);
            sku.setUnitPrice(unitPrice);
            sku.setMoqPrice(moqPrice);
            sku.setMoqQty(moqQty);
            sku.setListed(listed);
            sku.setMainImage(mainImage);
            sku.setCreatedBy(operatorId);
            try {
                skuMapper.insert(sku);
            } catch (DuplicateKeyException e) {
                // V42 uk_sku_spu_spec 并发兜底：与 service 层预检同语义
                log.warn("[P6] 规格组合并发重复，SPU {} specKey {}", spuId, specKey);
                throw new BizException(ErrorCode.SPU_SPEC_COMBO_DUPLICATED);
            }
            created.add(toSkuVo(sku));
        }
        log.info("[P6] operator {} 为自建 SPU {} 批量生成 SKU {} 个", operatorId, spuId, created.size());
        return created;
    }

    @Override
    @Transactional
    public void offline(Long spuId, Long operatorId) {
        Spu spu = requireOwnedForWrite(spuId, operatorId);
        if (!Spu.STATUS_ACTIVE.equals(spu.getStatus())) {
            throw new BizException(ErrorCode.SPU_STATE_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        spuMapper.update(null, new LambdaUpdateWrapper<Spu>()
                .eq(Spu::getId, spuId)
                .set(Spu::getStatus, Spu.STATUS_OFFLINE)
                .set(Spu::getUpdatedAt, now));
        // 级联下架该聚合 SPU 下全部在售 SKU（存量保留可查，RT 不再可见；恢复需逐个上架）
        int affected = skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getWholesalerId, spu.getWholesalerId())
                .eq(Sku::getListed, true)
                .set(Sku::getListed, false)
                .set(Sku::getUpdatedAt, now));
        log.info("[P6] operator {} 下架自建聚合 SPU {}（级联下架 SKU {} 行）", operatorId, spuId, affected);
    }

    // ==================================================================
    // 读：列表 / 详情
    // ==================================================================

    @Override
    public List<SpuCategoryGroupVo> categories() {
        return SpuCatalog.L1_L2S.entrySet().stream()
                .map(e -> SpuCategoryGroupVo.builder().l1(e.getKey()).l2s(e.getValue()).build())
                .toList();
    }

    @Override
    public List<SpuVo> list(Long wholesalerId, Long operatorId) {
        WholesalerVo wholesaler = requireWholesaler(wholesalerId);
        requireWkOrWaOrTa(wholesaler, operatorId);

        List<Spu> list = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getOwnerType, Spu.OWNER_TENANT)
                .eq(Spu::getTenantId, wholesaler.getTenantId())
                .eq(Spu::getWholesalerId, wholesalerId)
                .orderByDesc(Spu::getCreatedAt));
        Map<Long, Long> refs = countRefs(list.stream().map(Spu::getId).toList());
        return list.stream().map(s -> toVo(s, refs.getOrDefault(s.getId(), 0L))).toList();
    }

    @Override
    public SpuVo detail(Long spuId, Long operatorId) {
        Spu spu = requireOwnedForRead(spuId, operatorId);
        return toVo(spu, countRef(spuId));
    }

    // ==================================================================
    // 私有：鉴权 / 隔离
    // ==================================================================

    private record OwnedSpu(Spu spu, WholesalerVo wholesaler) {}

    private WholesalerVo requireWholesaler(Long wholesalerId) {
        if (wholesalerId == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        WholesalerVo wholesaler = wholesalerService.getById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        return wholesaler;
    }

    /**
     * 加载自建聚合 SPU 并做双查隔离：① owner_type 必须 TENANT（PLATFORM 走 OPS 通道，
     * 以 50735 假装不存在）；② 其所属商户须在当前可信租户可见（wholesalers 受 TenantLine 过滤，
     * 跨租户返回 null）。失败统一 50735，不泄漏存在性。
     */
    private OwnedSpu loadOwnedTenantSpu(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || !Spu.OWNER_TENANT.equals(spu.getOwnerType())) {
            throw new BizException(ErrorCode.MERCHANT_SPU_NOT_FOUND);
        }
        WholesalerVo wholesaler = wholesalerService.getById(spu.getWholesalerId());
        if (wholesaler == null || spu.getTenantId() == null
                || !spu.getTenantId().equals(wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.MERCHANT_SPU_NOT_FOUND);
        }
        return new OwnedSpu(spu, wholesaler);
    }

    /** 写路径：该商户 WA 或该租户 TA。 */
    private Spu requireOwnedForWrite(Long spuId, Long operatorId) {
        OwnedSpu owned = loadOwnedTenantSpu(spuId);
        requireWaOrTa(owned.wholesaler(), operatorId);
        return owned.spu();
    }

    /** 读路径：WA/TA + WK（只读放宽，SkuServiceImpl.listByWholesaler 先例）。 */
    private Spu requireOwnedForRead(Long spuId, Long operatorId) {
        OwnedSpu owned = loadOwnedTenantSpu(spuId);
        requireWkOrWaOrTa(owned.wholesaler(), operatorId);
        return owned.spu();
    }

    private void requireWaOrTa(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, ROLE_WA, wholesaler.getId())) {
            return;
        }
        if (!authService.hasRole(userId, ROLE_TA, wholesaler.getTenantId())) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    private void requireWkOrWaOrTa(WholesalerVo wholesaler, Long userId) {
        if (authService.hasWholesalerRole(userId, ROLE_WA, wholesaler.getId())) {
            return;
        }
        if (authService.hasRole(userId, ROLE_TA, wholesaler.getTenantId())
                || authService.hasRole(userId, ROLE_WK, wholesaler.getTenantId())) {
            return;
        }
        throw new BizException(ErrorCode.PERMISSION_TENANT_001);
    }

    // ==================================================================
    // 私有：工具
    // ==================================================================

    /** S2 公开价不变量：unit_price>0、moq_price>=0、moq_qty>=1。 */
    private void validatePrice(BigDecimal unitPrice, BigDecimal moqPrice, Integer moqQty) {
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0
                || moqPrice == null || moqPrice.compareTo(BigDecimal.ZERO) < 0
                || moqQty == null || moqQty < 1) {
            throw new BizException(ErrorCode.SKU_PRICE_INVALID);
        }
    }

    private String buildSkuName(String spuName, String summary) {
        String name = spuName + " " + summary;
        return name.length() > SKU_NAME_MAX_LEN ? name.substring(0, SKU_NAME_MAX_LEN) : name;
    }

    private long countRef(Long spuId) {
        Map<Long, Long> refs = countRefs(List.of(spuId));
        return refs.getOrDefault(spuId, 0L);
    }

    /** 平台级批量统计引用 SKU 数（SpuMapper.countSkuRefs；列别名大小写两库不一，按候选键取首个非空）。 */
    private Map<Long, Long> countRefs(List<Long> spuIds) {
        Map<Long, Long> refs = new HashMap<>();
        if (spuIds == null || spuIds.isEmpty()) {
            return refs;
        }
        List<Map<String, Object>> rows = spuMapper.countSkuRefs(spuIds);
        for (Map<String, Object> row : rows) {
            Object idObj = firstValue(row, "spuId", "spuid", "SPUID", "spu_id");
            Object cntObj = firstValue(row, "cnt", "CNT");
            if (idObj != null && cntObj != null) {
                refs.put(Long.valueOf(idObj.toString()), Long.parseLong(cntObj.toString()));
            }
        }
        return refs;
    }

    private static Object firstValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private SpuVo toVo(Spu s, long refCount) {
        return SpuVo.builder()
                .id(s.getId())
                .ownerType(s.getOwnerType())
                .tenantId(s.getTenantId())
                .wholesalerId(s.getWholesalerId())
                .specSchema(specSchemaSupport.toVo(specSchemaSupport.readSchema(s.getSpecSchema())))
                .spuCode(s.getSpuCode())
                .name(s.getName())
                .categoryL1(s.getCategoryL1())
                .categoryL2(s.getCategoryL2())
                .brand(s.getBrand())
                .standardImageUrl(s.getStandardImageUrl())
                .note(s.getNote())
                .status(s.getStatus())
                .mergedToSpuId(s.getMergedToSpuId())
                .referencedSkuCount(refCount)
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt())
                .build();
    }

    private SkuVo toSkuVo(Sku s) {
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
                .specKey(s.getSpecKey())
                .unitPrice(s.getUnitPrice())
                .moqPrice(s.getMoqPrice())
                .moqQty(s.getMoqQty())
                .listed(s.getListed())
                .mainImage(s.getMainImage())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
