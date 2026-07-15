package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * OPS 代建批发商（P2 Wave1，PRD R3）。
 * authBasis 必填：TA 授权凭据文本或客诉单号（留痕到申请单 auth_basis）。
 */
@Data
public class OpsWholesalerCreateDto {

    @NotBlank(message = "目标租户不能为空")
    private String tenantId;

    @NotBlank(message = "商户名称不能为空")
    @Size(max = 128, message = "商户名称最多 128 字")
    private String name;

    /** WA 负责人手机号（开通/绑定 WA 账号） */
    @NotBlank(message = "WA 负责人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String waPhone;

    @Size(max = 64, message = "联系人姓名最多 64 字")
    private String contactName;

    @Size(max = 512, message = "营业执照最多 512 字")
    private String license;

    /** 授权依据：TA 授权凭据文本或客诉单号（必填，缺失拒绝） */
    @NotBlank(message = "代建必须提供 TA 授权凭据或客诉单号")
    @Size(max = 512, message = "授权依据最多 512 字")
    private String authBasis;
}
