package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * OPS 加入黑名单（P2 Wave1）。targetType 仅 PHONE/LICENSE_NO（服务层白名单校验）。
 */
@Data
public class BlacklistAddDto {

    @NotBlank(message = "黑名单类型不能为空: PHONE/LICENSE_NO")
    private String targetType;

    @NotBlank(message = "黑名单值不能为空")
    @Size(max = 64, message = "黑名单值最多 64 字")
    private String targetValue;

    @NotBlank(message = "加黑原因不能为空")
    @Size(max = 512, message = "加黑原因最多 512 字")
    private String reason;
}
