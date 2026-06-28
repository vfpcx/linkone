package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * TA 自营创建批发商商户请求。
 */
@Data
public class WholesalerCreateDto {

    @NotBlank(message = "批发商名称不能为空")
    private String name;

    /** 营业资质（phase-1 占位，可空） */
    private String license;

    private String intro;

    /**
     * WA 账号开通：商户负责人手机号（可空）。
     * 传入则按手机号建/绑一个 WA 角色并写入 wholesaler_id（最小实现，见交付说明）。
     */
    private String waPhone;
}
