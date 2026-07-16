package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OPS 视角租户（仓库）列表项（P2 Wave3 顺路补齐；契约先行——
 * 对齐前端 api-types/ops.ts AdminTenantItem，字段一一对应，通过态是 ACTIVE 非 APPROVED）。
 */
@Data
@Builder
public class AdminTenantItemVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 仓库名 */
    private String name;

    /** 主体名称（营业执照上的公司名，可空） */
    private String legalName;

    /** 申请人（TA 账号实名，缺省回落昵称，可空） */
    private String applicantName;

    private String contactPhone;

    /** 仓库地址（文本，取入驻申请单快照，可空） */
    private String addressText;

    /** PENDING | ACTIVE | REJECTED */
    private String status;

    /** 申请时间（tenant 创建时间） */
    private LocalDateTime appliedAt;

    /** 审核时间（未审核为空） */
    private LocalDateTime auditedAt;

    /** 审核备注（驳回理由） */
    private String auditRemark;
}
