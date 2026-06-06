package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * OPS 代建租户请求
 */
@Data
public class TenantCreateDto {

    @NotBlank(message = "仓库名称不能为空")
    private String name;

    private String legalName;
    private String licenseNo;
    private String licenseUrl;

    @NotBlank(message = "TA手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    private String contactPhone;

    private String addressText;
    private BigDecimal lng;
    private BigDecimal lat;

    private String remark;
}
