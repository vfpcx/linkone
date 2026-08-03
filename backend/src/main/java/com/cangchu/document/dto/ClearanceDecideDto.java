package com.cangchu.document.dto;

import lombok.Data;

/**
 * TA 清库审批（P3b T4-W2，与盘点 decide 同构）：
 * conclusion=APPROVED|REJECTED；REJECTED 时 remark 必填（驳回理由）。
 */
@Data
public class ClearanceDecideDto {

    private String conclusion;

    private String remark;
}
