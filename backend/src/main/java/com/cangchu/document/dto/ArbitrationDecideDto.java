package com.cangchu.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 仲裁裁决入参（P3 BE-W1，12 §2.6 / §6.1）。
 * conclusion 取值域由仲裁单 biz_type 决定（入库：APPROVED/REJECTED）；
 * remark：REJECTED 必填（结论是线下赔偿唯一依据）；
 * liability：仅 REJECTED∧shortfall_qty>0 必填，其余必空（50342 双向校验）。
 */
@Data
public class ArbitrationDecideDto {

    @NotBlank(message = "结论不能为空")
    private String conclusion;

    @Size(max = 512, message = "结论备注最长 512 字")
    private String remark;

    private String liability;
}
