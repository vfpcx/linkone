package com.cangchu.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建商户自建聚合 SPU（P6 商品规格模型，V42）。
 *
 * <p>归属 {@code owner_type='TENANT'}：租户内某商户（wholesalerId 走查询参数）自建，
 * 仅本租户可见；品类沿用平台预置两级字典（SpuCatalog）以保持与平台标品同口径。
 * 规格模板必填（≥1 维度，≤5 维）——聚合 SPU 的 SKU 均由模板笛卡尔积批量生成。
 */
@Data
public class MerchantSpuCreateDto {

    @NotBlank(message = "聚合商品名称不能为空")
    @Size(max = 128, message = "聚合商品名称长度不能超过128")
    private String name;

    @NotBlank(message = "一级品类不能为空")
    @Size(max = 64, message = "一级品类长度不能超过64")
    private String categoryL1;

    @NotBlank(message = "二级品类不能为空")
    @Size(max = 64, message = "二级品类长度不能超过64")
    private String categoryL2;

    @Size(max = 64, message = "品牌长度不能超过64")
    private String brand;

    @Size(max = 512, message = "主图地址过长")
    private String standardImageUrl;

    @Size(max = 256, message = "备注长度不能超过256")
    private String note;

    /**
     * 规格模板（≥1 维度；缺失/空由 service 语义化拒绝 50730，
     * 维度内部校验仍由 {@code @Valid} 兜底 40001）
     */
    @Valid
    private List<SpecDimensionDto> specSchema;
}
