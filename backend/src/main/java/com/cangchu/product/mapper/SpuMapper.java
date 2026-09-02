package com.cangchu.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cangchu.product.entity.Spu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 平台标品 Spu Mapper（P5-D D56，22 §2）。
 * spus 平台级表（无 tenant_id，不在 TenantLine 白名单）→ 天然平台级语义，OPS 管辖。
 */
@Mapper
public interface SpuMapper extends BaseMapper<Spu> {

    /**
     * 平台级批量统计：引用各标品的在库 SKU 数（skus.spu_id 分组）。
     * 显式 deleted_at IS NULL（skus 逻辑删除，软删行不计引用）。
     * OPS 无 TenantContext → TenantLine 不注入，平台级统计；返回 {spuId, cnt}。
     */
    @Select("<script>" +
            "SELECT spu_id AS spuId, COUNT(*) AS cnt FROM skus " +
            "WHERE deleted_at IS NULL AND spu_id IN " +
            "<foreach collection='spuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY spu_id" +
            "</script>")
    List<Map<String, Object>> countSkuRefs(@Param("spuIds") Collection<Long> spuIds);
}
