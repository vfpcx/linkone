package com.cangchu.tenant.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * WA 发起退驻申请（P2 Wave2 R13）。reason 选填。
 */
@Data
public class WithdrawApplyDto {

    @Size(max = 512, message = "退驻原因最长512字")
    private String reason;
}
