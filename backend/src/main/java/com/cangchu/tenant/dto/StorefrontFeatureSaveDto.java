package com.cangchu.tenant.dto;

import lombok.Data;

import java.util.List;

/**
 * 店铺撮合配置保存请求（P5-A W4，18-p5-design §4.3）。
 *
 * <p>覆盖保存语义：以本次传入列表为最终态（先删后插，同事务）。
 * 校验：mainSkuIds ≤ 20（50711）、pinWaIds ≤ 5（50712）、重复项（50713）、
 * 引用须为本店/本租户在售 SKU 或本店入驻 ACTIVE 批发商（50714），由 Service 统一写前校验（幂等）。
 */
@Data
public class StorefrontFeatureSaveDto {

    /** 主推商品 id 有序列表（MAIN_SKU，按数组顺序落 sort_order） */
    private List<Long> mainSkuIds;

    /** 置顶批发商 id 有序列表（PIN_WA，按数组顺序落 sort_order） */
    private List<Long> pinWaIds;
}
