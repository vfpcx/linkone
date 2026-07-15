package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * TA 审批批发商入驻申请（P2 Wave1，仿 TenantAuditDto）。
 * action 仅 APPROVED/REJECTED；REJECTED 时 remark 必填（服务层校验）。
 */
@Data
public class WholesalerApplicationAuditDto {

    @NotBlank(message = "审核结果不能为空: APPROVED/REJECTED")
    private String action;

    @Size(max = 512, message = "审核意见最多 512 字")
    private String remark;
}
