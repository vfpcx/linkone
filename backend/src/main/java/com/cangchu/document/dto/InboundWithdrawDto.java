package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * R1 撤回入库申请入参（P3b T1-BE）。仅 SUBMITTED 可撤（50350），理由必填。
 */
@Data
public class InboundWithdrawDto {

    @NotBlank(message = "撤回理由不能为空")
    @Size(max = 512, message = "撤回理由最长 512 字")
    private String reason;
}
