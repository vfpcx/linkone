package com.cangchu.document.dto;

import lombok.Data;

/**
 * 盘点审批入参（P3b T3-W2，13 §5.2：POST /tenant/count-sheets/{id}/decide，TA）。
 * conclusion=APPROVED|REJECTED；REJECTED 时 remark（驳回理由）必填。
 * APPROVED 逐 SKU 锁内 GAIN/LOSS（盘亏 D-10 按审批时刻在库封顶，差额备注+通知，不驳回重盘）。
 */
@Data
public class CountSheetDecideDto {
    private String conclusion;
    private String remark;
}
