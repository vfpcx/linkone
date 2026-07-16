package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批发商入驻申请视图（P2 Wave1，TA 审批列表用）。
 */
@Data
@Builder
public class WholesalerApplicationVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantUserId;

    private String name;

    private String contactName;

    private String contactPhone;

    private String license;

    private String status;

    private String source;

    private String authBasis;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    private LocalDateTime auditedAt;

    private String auditRemark;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private LocalDateTime createdAt;
}
