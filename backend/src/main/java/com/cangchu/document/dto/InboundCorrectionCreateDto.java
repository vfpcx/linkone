package com.cangchu.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * R3 发起登记纠错入参（P3b T1-BE，13 §1.3/§5.1）。
 * 适用 source=WA_SUBMIT ∧ CONFIRMED；登记后 ≤24h（50352）；同单在途至多一张（50353）。
 */
@Data
public class InboundCorrectionCreateDto {

    /** 纠错后件数（≥0；=当前实登值 / 为负 → 50354） */
    @NotNull(message = "缺少纠错件数")
    @Min(value = 0, message = "纠错件数不能为负")
    private Integer newQty;

    @NotBlank(message = "纠错理由不能为空")
    @Size(max = 512, message = "纠错理由最长 512 字")
    private String reason;
}
