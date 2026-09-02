package com.cangchu.storefront.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RT「我的价目」单行（C1，23-p5-c-c1 §4.1）。
 *
 * <p>价格口径：{@code customerPrice}=专属价现值（价目行主价）；{@code unitPrice}/{@code moqPrice}/
 * {@code moqQty}=公开价对照（与 {@link StoreSkuVo} 同源）。{@code listed}=false 表示 SKU 已下架
 * （行仍返回、前端置灰禁勾选提交）；库存 0 不拦询价（仅提示）。
 */
@Data
@Builder
public class RtPriceItemVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    private String name;

    private String spec;

    private String mainImage;

    /** 公开价：单价（对照） */
    private BigDecimal unitPrice;

    /** 公开价：起批价（对照） */
    private BigDecimal moqPrice;

    /** 公开价：起批量（对照） */
    private Integer moqQty;

    /** 当前库存量（无在库行=0） */
    private Integer stockQty;

    /** 专属价现值（价目行主价） */
    private BigDecimal customerPrice;

    /** 专属价失效时间（空=永久） */
    private LocalDateTime expireAt;

    /** 专属价来源 manual/from_inquiry（前端徽标：商户设定/议价沉淀） */
    private String source;

    /** false=SKU 已下架（置灰禁提交） */
    private Boolean listed;
}
