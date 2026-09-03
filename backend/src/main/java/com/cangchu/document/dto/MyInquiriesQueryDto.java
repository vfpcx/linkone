package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RT「我的意向单」查询入参（F2 · US-RT-04）。
 *
 * <p>手机号放 POST body（不放 GET query——防明文手机号落访问日志，与 MyPriceListQueryDto 同口径）；
 * 服务内 hmac 盲查，响应仅尾号 4 位归属提示。
 */
@Data
public class MyInquiriesQueryDto {

    private Long storeId;

    private String code;

    @NotBlank(message = "RT手机号不能为空")
    private String rtPhone;
}
