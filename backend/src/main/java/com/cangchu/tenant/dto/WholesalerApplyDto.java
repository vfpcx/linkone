package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * WA 自助入驻申请（P2 Wave1）。
 * targetTenantId 用字符串承载雪花 ID（与 RegisterDto.targetTenantId 契约一致，避免 JS 精度丢失）。
 */
@Data
public class WholesalerApplyDto {

    @NotBlank(message = "目标仓库不能为空")
    private String targetTenantId;

    @NotBlank(message = "商户名称不能为空")
    @Size(max = 128, message = "商户名称最多 128 字")
    private String name;

    @Size(max = 64, message = "联系人姓名最多 64 字")
    private String contactName;

    @Size(max = 20, message = "联系电话最多 20 字")
    private String contactPhone;

    /** 营业执照号/凭证（黑名单键之一，可空） */
    @Size(max = 512, message = "营业执照最多 512 字")
    private String license;
}
