package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;

/**
 * TA 自助注册仓库请求
 */
@Data
public class TenantApplyDto {

    @NotBlank(message = "仓库名称不能为空")
    private String name;

    private String legalName;
    private String licenseNo;
    private String licenseUrl;

    @NotBlank(message = "联系手机号不能为空")
    private String contactPhone;

    private String addressText;
    private BigDecimal lng;
    private BigDecimal lat;
}
