package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * OPS 审核入驻请求
 */
@Data
public class TenantAuditDto {

    @NotBlank(message = "审核结果不能为空: APPROVED/REJECTED")
    private String action;

    private String remark;
}
