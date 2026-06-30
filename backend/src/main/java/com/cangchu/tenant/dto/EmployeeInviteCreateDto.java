package com.cangchu.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * TA 生成员工注册码入参（phase-1 员工注册码：解锁 WK 入库）。
 *
 * <p>tenant_id 不由客户端传入——由登录态(TA 绑定的租户)推导（G-2.1 隔离）。
 * role 仅允许 WK/ST（仓库内部员工角色），服务层白名单二次校验。
 */
@Data
public class EmployeeInviteCreateDto {

    /** 目标内部角色，仅 WK / ST */
    @NotBlank(message = "员工角色不能为空")
    private String role;

    /** 最大可用次数（默认 1，>=1） */
    @Min(value = 1, message = "可用次数至少为 1")
    @Max(value = 999, message = "可用次数过大")
    private Integer maxUses;

    /** 有效天数（默认 7，>=1）；用于计算 expireAt */
    @Min(value = 1, message = "有效天数至少为 1")
    @Max(value = 365, message = "有效天数过大")
    private Integer expiresInDays;
}
