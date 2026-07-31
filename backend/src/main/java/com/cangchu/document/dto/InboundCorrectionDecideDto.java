package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * TA 审批登记纠错入参（P3b T1-BE，13 §5.1）。
 * conclusion=APPROVED（走 §1.3 封顶事务）/ REJECTED（remark 必填，零库存影响）。
 */
@Data
public class InboundCorrectionDecideDto {

    @NotBlank(message = "缺少审批结论")
    private String conclusion;

    /** 结论备注（REJECTED 必填 ≤512） */
    @Size(max = 512, message = "结论备注最长 512 字")
    private String remark;
}
