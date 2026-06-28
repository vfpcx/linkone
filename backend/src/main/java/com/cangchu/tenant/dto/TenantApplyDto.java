package com.cangchu.tenant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    /** D-08 经度范围校验 [-180, 180] */
    @DecimalMin(value = "-180.0", message = "经度超出有效范围[-180,180]")
    @DecimalMax(value = "180.0", message = "经度超出有效范围[-180,180]")
    private BigDecimal lng;

    /** D-08 纬度范围校验 [-90, 90] */
    @DecimalMin(value = "-90.0", message = "纬度超出有效范围[-90,90]")
    @DecimalMax(value = "90.0", message = "纬度超出有效范围[-90,90]")
    private BigDecimal lat;
}
