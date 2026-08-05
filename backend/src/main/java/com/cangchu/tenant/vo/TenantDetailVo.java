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
    /** 计费维度只读镜像（P4 W1，14 §2.3）：QTY/PALLET/BOTH——由计费规则保存事务同步，此处仅供展示 */
    private String billingDim;
    private Integer expiryThresholdDays;
    private String displayImageSource;

    private LocalDateTime createdAt;
}
