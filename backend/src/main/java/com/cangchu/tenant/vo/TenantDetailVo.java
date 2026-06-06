package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TenantDetailVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String tenantSimpleCode;
    private String name;
    private String legalName;
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    private String storeName;
    private String addressText;
    private BigDecimal lng;
    private BigDecimal lat;
    private Integer totalCapacityQty;
    private Integer totalCapacityPallet;
    private String capacityVisibility;
    private String capacityPrecision;
    private String businessHours;
    private String intro;

    // 5 开关
    private Integer batchEnabled;
    private String photoMode;
    private String billingDim;
    private Integer expiryThresholdDays;
    private String displayImageSource;

    private LocalDateTime createdAt;
}
