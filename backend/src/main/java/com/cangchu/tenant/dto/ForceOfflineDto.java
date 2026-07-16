package com.cangchu.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * TA 强制下架（P2 Wave2 R14）。reason 必填留痕。
 */
@Data
public class ForceOfflineDto {

    @NotBlank(message = "强制下架必须填写原因")
    @Size(max = 512, message = "下架原因最长512字")
    private String reason;
}
