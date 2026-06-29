package com.cangchu.tenant.dto;

import lombok.Data;

/**
 * 批发商商户资料修改请求（phase-1 仅 intro / license）。
 */
@Data
public class WholesalerUpdateDto {

    private String license;

    private String intro;
}
