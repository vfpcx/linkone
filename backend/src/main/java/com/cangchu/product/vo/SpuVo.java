package com.cangchu.product.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台标品视图（P5-D D56，22 §3.1）。
 * referencedSkuCount = 全平台引用该标品的在库 SKU 数（合并影响面展示）。
 */
@Data
@Builder
public class SpuVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String spuCode;

    private String name;

    private String categoryL1;

    private String categoryL2;

    private String brand;

    private String standardImageUrl;

    private String note;

    /** ACTIVE / OFFLINE / MERGED */
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long mergedToSpuId;

    /** 引用该标品的在库 SKU 数 */
    private long referencedSkuCount;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    private LocalDateTime createdAt;
}
