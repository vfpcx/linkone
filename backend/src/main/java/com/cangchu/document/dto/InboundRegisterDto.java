package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * WK 入库登记入参（phase-1 C1）。
 *
 * <p>tenantId 不由客户端传入——由 wholesaler 真实归属推导（S4 隔离，G-2.1）。
 */
@Data
public class InboundRegisterDto {

    /** 批发商商户 id（必填） */
    @NotNull(message = "缺少批发商商户")
    private Long wholesalerId;

    /** 商品 SKU id（必填） */
    @NotNull(message = "缺少商品 SKU")
    private Long skuId;

    /** 入库数量（>0） */
    @NotNull(message = "缺少入库数量")
    @Min(value = 1, message = "入库数量必须大于0")
    private Integer qty;

    /** 本次托盘数（可空，默认 0；>=0） */
    @Min(value = 0, message = "托盘数不能为负")
    private Integer palletQty;

    // ==================== P3b T4-W1 批次三字段（代建=提交即登记，按当刻开关状态校验必填） ====================

    /** 批次号（租户批次开关启用时必填 ≤64；重复 50362） */
    @jakarta.validation.constraints.Size(max = 64, message = "批次号最长 64 字")
    private String batchNo;

    /** 生产日期（开关启用必填；≤今天，40205） */
    private java.time.LocalDate productionDate;

    /** 到效期（开关启用必填；>生产日期，40206；≤今天须 expiredConfirmed=true，50364） */
    private java.time.LocalDate expiryDate;

    /** 过期批次强警告二次确认凭据（13 §3.1） */
    private Boolean expiredConfirmed;
}
