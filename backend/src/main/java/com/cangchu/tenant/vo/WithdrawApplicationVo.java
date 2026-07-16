package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 退驻申请视图（P2 Wave2 R13：TA 审批列表 / WA mine 查询共用）。
 */
@Data
@Builder
public class WithdrawApplicationVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** 商户名称（列表展示冗余） */
    private String wholesalerName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantUserId;

    private String reason;

    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    /** 审核时间（通过时刻=60 天恢复倒计时起点，前端据此计算） */
    private LocalDateTime auditedAt;

    private String auditRemark;

    private LocalDateTime createdAt;
}
