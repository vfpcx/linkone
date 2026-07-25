package com.cangchu.document.enums;

import lombok.Getter;

/**
 * 单据类型（phase-1 文档号前缀）。
 *
 * <p>{@code prefix} 为单据号业务前缀（2 位字母），由 {@link com.cangchu.document.service.DocumentNumberService}
 * 拼入最终 docNo。phase-1 仅 INBOUND/OUTBOUND/INQUIRY 在用，其余为后续批次预留。
 */
@Getter
public enum DocType {

    /** 入库单（C1） */
    INBOUND("WK"),
    /** 出库单（C2） */
    OUTBOUND("CK"),
    /** 询价单（C2） */
    INQUIRY("XJ"),
    /** 入库异议仲裁单（P3 BE-W1 启用，12 §1.6/§4.1） */
    DISPUTE_ARBITRATION("YY"),
    /** 出库客诉单（P3 BE-W2 启用） */
    COMPLAINT("KS"),
    /** 退货单（T3 波预留，前缀按产品 05 §7.1 修订版） */
    RETURN("RTN"),
    /** 盘点单（T3 波预留） */
    STOCKTAKE("PD"),
    /** 清库单（T4 波预留） */
    CLEARANCE("QK");

    private final String prefix;

    DocType(String prefix) {
        this.prefix = prefix;
    }
}
