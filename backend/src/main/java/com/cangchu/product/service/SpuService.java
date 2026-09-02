package com.cangchu.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.product.dto.SpuCreateDto;
import com.cangchu.product.entity.Spu;
import com.cangchu.product.vo.SpuCategoryGroupVo;
import com.cangchu.product.vo.SpuVo;

import java.util.List;

/**
 * 平台标品 SPU 服务（P5-D D56，22 §2/§3；归属 product 域，平台级）。
 *
 * <p>鉴权：OPS 管理端点 requireOps（非 OPS → 42002，公告/黑名单先例）；
 * {@link #requireLinkable} 为 product 域内 SKU 挂接只读出口（无鉴权——调用方 SkuServiceImpl
 * 已完成 S4 归属鉴权，本方法只做 ACTIVE 校验与错误码，遵循 G-S1/G-S2 语义）。
 */
public interface SpuService {

    /** OPS 分页列表（keyword 名称/编码模糊 + 品类/状态过滤 + 引用 SKU 数）。 */
    Page<SpuVo> page(Long operatorId, int page, int size, String keyword,
                     String categoryL1, String categoryL2, String status);

    /** OPS 新增标品（ACTIVE；spuCode 空自动生成 GSPU-xxx，唯一）。 */
    SpuVo create(Long operatorId, SpuCreateDto dto);

    /** OPS 下架：ACTIVE → OFFLINE（存量 SKU 引用保留，仅禁新挂接）。 */
    void offline(Long operatorId, Long spuId);

    /**
     * OPS 合并 source → target（同事务）：
     * source 置 MERGED + merged_to_spu_id=target；全平台引用 source 的 SKU 原子重指 target 并刷新快照。
     */
    void merge(Long operatorId, Long sourceSpuId, Long targetSpuId);

    /**
     * SKU 挂接只读出口（product 域内，供 SkuServiceImpl.createSku）：
     * 返回存在的标品且 ACTIVE；不存在 → SPU_NOT_FOUND，非 ACTIVE → SPU_NOT_LINKABLE。
     */
    Spu requireLinkable(Long spuId);

    /** 两级品类字典（OPS 新增弹窗下拉同源，22 §3.4；requireOps）。 */
    List<SpuCategoryGroupVo> categories(Long operatorId);

    /**
     * 标品目录只读搜索（登录态可见，不限 OPS）：仅 ACTIVE，供 TA/WA 建 SKU「选择标品」
     * 与各端只读目录（22 §3.1 契约扩展 /api/v1/catalog/spus）。引用数不返回。
     */
    Page<SpuVo> searchActive(int page, int size, String keyword);
}
