package com.cangchu.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增平台标品请求（P5-D D56，22 §3.1）。
 * categoryL1/L2 必须在平台预置字典（SpuCatalog），由 Service 层校验（SPU_CATEGORY_INVALID）。
 */
@Data
public class SpuCreateDto {

    @NotBlank(message = "标品名称不能为空")
    @Size(max = 128, message = "标品名称长度不能超过128")
    private String name;

    @NotBlank(message = "一级品类不能为空")
    @Size(max = 64, message = "一级品类长度不能超过64")
    private String categoryL1;

    @NotBlank(message = "二级品类不能为空")
    @Size(max = 64, message = "二级品类长度不能超过64")
    private String categoryL2;

    @Size(max = 64, message = "品牌长度不能超过64")
    private String brand;

    @Size(max = 512, message = "标准图地址过长")
    private String standardImageUrl;

    @Size(max = 256, message = "备注长度不能超过256")
    private String note;

    /** 平台编码（可选≤32；空则自动生成 GSPU-xxx；全局唯一） */
    @Size(max = 32, message = "平台编码长度不能超过32")
    private String spuCode;
}
