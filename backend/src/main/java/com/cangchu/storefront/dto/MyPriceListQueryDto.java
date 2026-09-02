package com.cangchu.storefront.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RT「我的价目」查询入参（C1，23-p5-c-c1 §4.1）。
 *
 * <p>手机号放 POST body（<b>不放 GET query</b>，防明文手机号落访问日志）。
 * storeId 与 code 至少传一个（service 内校验，同 /rt/store 口径）。
 */
@Data
public class MyPriceListQueryDto {

    private Long storeId;

    /** 店铺码 = 租户简码 tenantSimpleCode */
    private String code;

    @NotBlank(message = "RT手机号不能为空")
    private String rtPhone;
}
