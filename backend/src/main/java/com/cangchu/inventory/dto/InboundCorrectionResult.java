package com.cangchu.inventory.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * R3 登记纠错库存联动结果（P3b T1-BE，13 §1.3）。
 * 改小封顶口径与 DisputeReversalResult 同构：applied=min(|delta|, max(onhand,0))，
 * shortfall=|delta|−applied（差额写纠错单备注定责，禁止打负）；applied=0 不写流水。
 */
@Getter
@Builder
public class InboundCorrectionResult {

    /** 实际生效变动量（改大=delta；改小=封顶后冲销量） */
    private final int appliedQty;

    /** 改小遇已售差额（改大恒 0） */
    private final int shortfallQty;

    /** 本次托盘变动（正=补占，负=释放；释放侧对在库托盘二次封顶） */
    private final int palletAdjusted;

    /** CORRECTION_IN/OUT 流水 id（applied=0 时为 null） */
    private final Long movementId;

    /** 联动后剩余在库 */
    private final int remainingQty;
}
