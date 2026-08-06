package com.cangchu.billing;

import com.cangchu.billing.entity.Bill;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.document.statemachine.DocStateMachine;
import com.cangchu.document.statemachine.DocStateMachine.DocKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4 W3 账单六态矩阵逐格测试（14 §3.1，任务关卡「BILL 矩阵逐格（6×6 含不可达）」）。
 *
 * <p>纯单元（无 Spring）：合法边全过、不可达边全拒（50330）、DISPUTED 无出边（P5 OPS 解冻）、
 * 未知状态防御。生成落点（DRAFT/应收 0 即 PAID/OFFLINE 直落 DISPUTED）为插入非迁移，不在矩阵内。
 */
class BillStateMachineMatrixTest {

    private static final List<String> ALL = List.of(
            Bill.STATUS_DRAFT, Bill.STATUS_DISPATCHED, Bill.STATUS_PENDING_PAYMENT,
            Bill.STATUS_PARTIAL_PAID, Bill.STATUS_PAID, Bill.STATUS_DISPUTED);

    /** 14 §3.1 定稿矩阵（唯一话术源） */
    private static final Map<String, Set<String>> EXPECTED = Map.of(
            Bill.STATUS_DRAFT, Set.of(Bill.STATUS_DISPATCHED),
            Bill.STATUS_DISPATCHED, Set.of(Bill.STATUS_DRAFT, Bill.STATUS_PENDING_PAYMENT,
                    Bill.STATUS_DISPUTED),
            Bill.STATUS_PENDING_PAYMENT, Set.of(Bill.STATUS_PARTIAL_PAID, Bill.STATUS_PAID,
                    Bill.STATUS_DISPUTED),
            Bill.STATUS_PARTIAL_PAID, Set.of(Bill.STATUS_PAID, Bill.STATUS_PENDING_PAYMENT,
                    Bill.STATUS_DISPUTED),
            Bill.STATUS_PAID, Set.of(Bill.STATUS_PARTIAL_PAID, Bill.STATUS_PENDING_PAYMENT),
            Bill.STATUS_DISPUTED, Set.of());

    @Test
    @DisplayName("BILL-SM-01 6×6 逐格：合法边恰 12 条，其余全部不可达")
    void fullMatrix() {
        int legal = 0;
        for (String from : ALL) {
            for (String to : ALL) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(DocStateMachine.canGo(DocKind.BILL, from, to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
                if (expected) {
                    legal++;
                }
            }
        }
        assertThat(legal).as("合法边数（14 §3.1）").isEqualTo(12);
    }

    @Test
    @DisplayName("BILL-SM-02 DISPUTED 冻结：无任何出边（P4 无解冻路径，OPS 闭环 P5）")
    void disputedHasNoOutgoingEdges() {
        for (String to : ALL) {
            assertThat(DocStateMachine.canGo(DocKind.BILL, Bill.STATUS_DISPUTED, to))
                    .as("DISPUTED → %s 必须不可达", to)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("BILL-SM-03 不可达红线抛 50330；未知状态/null 防御不可达")
    void unreachableThrows50330() {
        // 红线抽查：DRAFT 直跳待回款/已结清（必须经下发）、PAID→DISPUTED（已结清非未结）
        for (String[] edge : new String[][]{
                {Bill.STATUS_DRAFT, Bill.STATUS_PENDING_PAYMENT},
                {Bill.STATUS_DRAFT, Bill.STATUS_PAID},
                {Bill.STATUS_DRAFT, Bill.STATUS_DISPUTED},
                {Bill.STATUS_PAID, Bill.STATUS_DISPUTED},
                {Bill.STATUS_PENDING_PAYMENT, Bill.STATUS_DRAFT}}) {
            assertThatThrownBy(() -> DocStateMachine.assertCanGo(DocKind.BILL, edge[0], edge[1]))
                    .as("%s → %s", edge[0], edge[1])
                    .isInstanceOfSatisfying(BizException.class, ex ->
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ErrorCode.DOC_STATE_TRANSITION_INVALID));
        }
        assertThat(DocStateMachine.canGo(DocKind.BILL, "GENERATING", Bill.STATUS_DRAFT)).isFalse();
        assertThat(DocStateMachine.canGo(DocKind.BILL, null, Bill.STATUS_DRAFT)).isFalse();
        assertThat(DocStateMachine.canGo(DocKind.BILL, Bill.STATUS_DRAFT, null)).isFalse();
    }
}
