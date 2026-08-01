package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * WA 撤回退货申请（仅 PENDING_ACCEPT，R1 同构 reason 必填）。
 */
@Data
public class ReturnWithdrawDto {

    @NotBlank(message = "缺少撤回理由")
    private String reason;
}
