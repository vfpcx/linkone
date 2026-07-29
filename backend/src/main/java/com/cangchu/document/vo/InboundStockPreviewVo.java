package com.cangchu.document.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 入库单异议前在库预览（M3，PRD 09 §6.2）。
 *
 * <p>WA 提交异议前展示三个实时数字，帮助 WA 了解冲销口径：
 * <ul>
 *   <li>{@code onhand}：当前在库件数（轻量只读，允许轻微过期，无锁语义要求）；</li>
 *   <li>{@code expectedReversal}：预计冲销量 = min(registeredQty, max(onhand, 0))；</li>
 *   <li>{@code expectedShortfall}：预计差额 = registeredQty − expectedReversal（进 TA 仲裁）。</li>
 * </ul>
 * 注意：实际冲销以 {@code reverseInboundForDispute} 锁内计算为准，此处仅供展示参考。
 */
@Data
@Builder
public class InboundStockPreviewVo {

    /** 当前在库件数（轻量只读快照，无锁） */
    private int onhand;

    /** 预计冲销量 = min(registeredQty, max(onhand, 0)) */
    private int expectedReversal;

    /** 预计差额 = registeredQty − expectedReversal（进 TA 仲裁） */
    private int expectedShortfall;
}
