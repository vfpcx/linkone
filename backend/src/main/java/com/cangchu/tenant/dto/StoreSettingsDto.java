package com.cangchu.tenant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改店铺设置请求
 */
@Data
public class StoreSettingsDto {

    private String name;
    private String addressText;

    /** D-08 经度范围校验 [-180, 180] */
    @DecimalMin(value = "-180.0", message = "经度超出有效范围[-180,180]")
    @DecimalMax(value = "180.0", message = "经度超出有效范围[-180,180]")
    private BigDecimal lng;

    /** D-08 纬度范围校验 [-90, 90] */
    @DecimalMin(value = "-90.0", message = "纬度超出有效范围[-90,90]")
    @DecimalMax(value = "90.0", message = "纬度超出有效范围[-90,90]")
    private BigDecimal lat;
    private Integer totalCapacityQty;
    private Integer totalCapacityPallet;
    private String capacityVisibility;
    private String capacityPrecision;
    private String businessHours;
    private String intro;

    // TA 级开关
    private Integer batchEnabled;
    private String photoMode;
    /**
     * @deprecated P4 W1（14 §2.3 契约断裂修复）：计费维度改由计费规则 API 版本化管理，
     * billing_dim 转只读镜像。本字段仅为旧客户端兼容保留——后端收到即忽略并 log warn，不落库。
     */
    @Deprecated
    private String billingDim;
    private Integer expiryThresholdDays;
    private String displayImageSource;
}
