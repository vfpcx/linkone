package com.cangchu.tenant.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改店铺设置请求
 */
@Data
public class StoreSettingsDto {

    private String name;
    private String addressText;
    private BigDecimal lng;
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
    private String billingDim;
    private Integer expiryThresholdDays;
    private String displayImageSource;
}
