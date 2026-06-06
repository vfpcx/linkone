package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class CapacityVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long storeId;

    private String visibility;
    private String precision;
    private Integer usedQty;
    private Integer usedPallet;
    private Integer totalQty;
    private Integer totalPallet;
    private BigDecimal utilization;
    private String tier;
    private String tierLabel;
    private LocalDateTime snapshotAt;
    private LocalDateTime expectedNextRefresh;
}
