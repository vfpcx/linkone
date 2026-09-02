package com.cangchu.storefront.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RT「我的价目」响应（C1 专属价复购，23-p5-c-c1 §4.1）。
 *
 * <p>价目范围 = 当前店全部 ACTIVE wholesaler × 该手机号的有效客户专属价行，
 * 按 wholesaler 分组（某商户无价目行则不出组）。只回尾号 4 位作归属提示，
 * <b>永不返回手机号明文</b>（PII：响应/日志均不落明文）。
 */
@Data
@Builder
public class RtPriceListVo {

    /** 价目归属提示：rt_phone 尾号 4 位（非打码展示，仅作"哪份价目"确认） */
    private String rtPhoneLast4;

    /** 有专属价目的店内批发商（组内 items 按价目行 createdAt 倒序） */
    private List<RtPriceGroupVo> wholesalers;
}
