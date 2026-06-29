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
    INQUIRY("XJ");

    private final String prefix;

    DocType(String prefix) {
        this.prefix = prefix;
    }
}
