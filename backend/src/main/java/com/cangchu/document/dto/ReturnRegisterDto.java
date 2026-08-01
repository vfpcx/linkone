package com.cangchu.document.dto;

import lombok.Data;

/**
 * WK 退货登记（D-7 登记时扣；13 §5.2）。
 * 三项均可空：actualQty 缺省=申请件数；palletRelease 缺省=比例建议值（13 §2.4-2），
 * 传入值为 WK 覆盖（含 0），落库前对在库托盘封顶不打负。
 */
@Data
public class ReturnRegisterDto {

    /** 实退件数（可空=按申请件数；覆写时 remark 自动留痕） */
    private Integer actualQty;

    /** 释放托盘覆盖值（可空=默认建议值 ceil(池 pallet × 件数 / 池 qty)；0 合法=托盘未腾空） */
    private Integer palletRelease;

    /** 登记备注（选填 ≤512） */
    private String remark;
}
