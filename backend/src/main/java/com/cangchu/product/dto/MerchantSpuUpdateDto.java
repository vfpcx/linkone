package com.cangchu.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新商户自建聚合 SPU（P6 商品规格模型，V42）。partial update 语义：
 * 字段为 null 表示保持不变（安全规约 §10 先例）。
 *
 * <p>规格模板变更不影响已生成 SKU 的 {@code spec_key}（存量组合保留）；
 * 但存量组合会成为「历史组合」，重新生成时按新模板判定唯一性。
 */
@Data
public class MerchantSpuUpdateDto {

    @Size(max = 128, message = "聚合商品名称长度不能超过128")
    private String name;

    @Size(max = 64, message = "一级品类长度不能超过64")
    private String categoryL1;

    @Size(max = 64, message = "二级品类长度不能超过64")
    private String categoryL2;

    @Size(max = 64, message = "品牌长度不能超过64")
    private String brand;

    @Size(max = 512, message = "主图地址过长")
    private String standardImageUrl;

    @Size(max = 256, message = "备注长度不能超过256")
    private String note;

    /** 规格模板（非空时整体替换；不可传入空数组） */
    @Valid
    private List<SpecDimensionDto> specSchema;
}
