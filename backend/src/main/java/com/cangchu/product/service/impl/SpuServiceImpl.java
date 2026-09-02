package com.cangchu.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.product.catalog.SpuCatalog;
import com.cangchu.product.dto.SpuCreateDto;
import com.cangchu.product.entity.Sku;
import com.cangchu.product.entity.Spu;
import com.cangchu.product.mapper.SkuMapper;
import com.cangchu.product.mapper.SpuMapper;
import com.cangchu.product.service.SpuService;
import com.cangchu.product.vo.SpuCategoryGroupVo;
import com.cangchu.product.vo.SpuVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台标品 SPU 服务实现（P5-D D56，22 §2/§3）。
 *
 * <p>安全规约：OPS 端点 requireOps（hasRole(OPS)，非 OPS → 42002，公告/黑名单先例）；
 * spus 平台级表无 TenantLine → 平台级读写，OPS 无租户上下文。
 * 合并为同事务 read-check-write：source=ACTIVE、target=ACTIVE 且非自身 → 置 MERGED +
 * 引用 SKU 单条 UPDATE 原子重指并刷新快照（OPS 低频操作，不加乐观锁，公告/黑名单同构先例）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpuServiceImpl implements SpuService {

    private static final String ROLE_OPS = "OPS";
    private static final int MAX_PAGE_SIZE = 100;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final AuthService authService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    public Page<SpuVo> page(Long operatorId, int page, int size, String keyword,
                            String categoryL1, String categoryL2, String status) {
        requireOps(operatorId);
        Page<Spu> p = spuMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)),
                new LambdaQueryWrapper<Spu>()
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(Spu::getName, keyword.trim())
                                .or().like(Spu::getSpuCode, keyword.trim()))
                        .eq(categoryL1 != null && !categoryL1.isBlank(), Spu::getCategoryL1, categoryL1)
                        .eq(categoryL2 != null && !categoryL2.isBlank(), Spu::getCategoryL2, categoryL2)
                        .eq(status != null && !status.isBlank(), Spu::getStatus, status)
                        .orderByDesc(Spu::getCreatedAt));

        // 本页引用 SKU 计数（spu_id 批量分组，平台级统计）
        Map<Long, Long> refs = countRefs(p.getRecords().stream().map(Spu::getId).toList());

        Page<SpuVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(s -> toVo(s, refs.getOrDefault(s.getId(), 0L))).toList());
        return out;
    }

    @Override
    @Transactional
    public SpuVo create(Long operatorId, SpuCreateDto dto) {
        requireOps(operatorId);
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCode.SPU_NAME_REQUIRED);
        }
        if (!SpuCatalog.validL2(dto.getCategoryL1(), dto.getCategoryL2())) {
            throw new BizException(ErrorCode.SPU_CATEGORY_INVALID);
        }
        Spu spu = new Spu();
        spu.setId(snowflakeIdUtil.nextId());
        spu.setName(dto.getName().trim());
        spu.setCategoryL1(dto.getCategoryL1());
        spu.setCategoryL2(dto.getCategoryL2());
        spu.setBrand(dto.getBrand());
        spu.setStandardImageUrl(dto.getStandardImageUrl());
        spu.setNote(dto.getNote());
        // 编码：OPS 填 → 查重（50724）；空 → 自动 GSPU-<雪花>（唯一性由雪花保证）
        if (dto.getSpuCode() != null && !dto.getSpuCode().isBlank()) {
            String code = dto.getSpuCode().trim();
            if (existsByCode(code)) {
                throw new BizException(ErrorCode.SPU_CODE_DUPLICATED);
            }
            spu.setSpuCode(code);
        } else {
            spu.setSpuCode("GSPU-" + snowflakeIdUtil.nextId());
        }
        spu.setStatus(Spu.STATUS_ACTIVE);
        spu.setCreatedBy(operatorId);
        spuMapper.insert(spu);
        log.info("[D56] OPS {} 新增标品 {} ({})", operatorId, spu.getId(), spu.getSpuCode());
        return toVo(spu, 0L);
    }

    @Override
    @Transactional
    public void offline(Long operatorId, Long spuId) {
        requireOps(operatorId);
        Spu spu = getOrThrow(spuId);
        if (!Spu.STATUS_ACTIVE.equals(spu.getStatus())) {
            throw new BizException(ErrorCode.SPU_STATE_INVALID);
        }
        // 只置 status + updated_at（§10 partial update 先例），不覆盖其它列
        spuMapper.update(null, new LambdaUpdateWrapper<Spu>()
                .eq(Spu::getId, spuId)
                .set(Spu::getStatus, Spu.STATUS_OFFLINE));
        log.info("[D56] OPS {} 下架标品 {}（OFFLINE）", operatorId, spuId);
    }

    @Override
    @Transactional
    public void merge(Long operatorId, Long sourceSpuId, Long targetSpuId) {
        requireOps(operatorId);
        Spu source = getOrThrow(sourceSpuId);
        if (!Spu.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new BizException(ErrorCode.SPU_STATE_INVALID);
        }
        if (targetSpuId == null || targetSpuId.equals(sourceSpuId)) {
            throw new BizException(ErrorCode.SPU_MERGE_TARGET_INVALID);
        }
        Spu target = getOrThrow(targetSpuId);
        if (!Spu.STATUS_ACTIVE.equals(target.getStatus())) {
            throw new BizException(ErrorCode.SPU_MERGE_TARGET_INVALID);
        }
        // 1) 源标品 → MERGED + 指向新主
        spuMapper.update(null, new LambdaUpdateWrapper<Spu>()
                .eq(Spu::getId, sourceSpuId)
                .set(Spu::getStatus, Spu.STATUS_MERGED)
                .set(Spu::getMergedToSpuId, targetSpuId));
        // 2) 引用源标品的全部在库 SKU 原子重指新主 + 刷新快照（同事务；无 TenantContext 不注入租户条件）
        int affected = skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getSpuId, sourceSpuId)
                .set(Sku::getSpuId, targetSpuId)
                .set(Sku::getSpuName, target.getName())
                .set(Sku::getSpuCategoryL1, target.getCategoryL1())
                .set(Sku::getSpuCategoryL2, target.getCategoryL2()));
        log.info("[D56] OPS {} 合并标品 {} → {}（{} 个 SKU 引用重指）", operatorId, sourceSpuId, targetSpuId, affected);
    }

    @Override
    public Spu requireLinkable(Long spuId) {
        Spu spu = getOrThrow(spuId);
        if (!Spu.STATUS_ACTIVE.equals(spu.getStatus())) {
            throw new BizException(ErrorCode.SPU_NOT_LINKABLE);
        }
        return spu;
    }

    @Override
    public Page<SpuVo> searchActive(int page, int size, String keyword) {
        Page<Spu> p = spuMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)),
                new LambdaQueryWrapper<Spu>()
                        .eq(Spu::getStatus, Spu.STATUS_ACTIVE)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(Spu::getName, keyword.trim())
                                .or().like(Spu::getSpuCode, keyword.trim()))
                        .orderByDesc(Spu::getCreatedAt));
        Page<SpuVo> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(s -> toVo(s, 0L)).toList());
        return out;
    }

    @Override
    public List<SpuCategoryGroupVo> categories(Long operatorId) {
        requireOps(operatorId);
        return SpuCatalog.L1_L2S.entrySet().stream()
                .map(e -> SpuCategoryGroupVo.builder().l1(e.getKey()).l2s(e.getValue()).build())
                .toList();
    }

    // ==================== 内部 ====================

    private void requireOps(Long operatorId) {
        if (!authService.hasRole(operatorId, ROLE_OPS)) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }

    private Spu getOrThrow(Long id) {
        Spu spu = spuMapper.selectById(id);
        if (spu == null) {
            throw new BizException(ErrorCode.SPU_NOT_FOUND);
        }
        return spu;
    }

    private boolean existsByCode(String code) {
        Long cnt = spuMapper.selectCount(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getSpuCode, code));
        return cnt != null && cnt > 0;
    }

    private Map<Long, Long> countRefs(List<Long> spuIds) {
        Map<Long, Long> refs = new HashMap<>();
        if (spuIds == null || spuIds.isEmpty()) {
            return refs;
        }
        List<Map<String, Object>> rows = spuMapper.countSkuRefs(spuIds);
        for (Map<String, Object> row : rows) {
            // H2/MySQL 对列别名大小写处理不同（引号/大小写折叠），按候选键取首个非空
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
}
