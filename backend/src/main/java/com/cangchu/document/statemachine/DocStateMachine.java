package com.cangchu.document.statemachine;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.entity.OutboundRequest;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 单据状态机引擎（P3 12 §1.1：转换表 + CAS，不换存储模型）。
 *
 * <p>BE-W1 据实现备注 2 兑现：引擎随 BE-W2 出库迁移矩阵落地。只做三件事——
 * <ol>
 *   <li>每单据类型一张静态迁移表（{@link DocKind}），不可达迁移统一抛 50330；</li>
 *   <li>{@link #assertCanGo} 前置校验（终态/跳变红线由矩阵天然覆盖）；</li>
 *   <li>{@link #casTransition} 条件更新 CAS（UPDATE...WHERE id=? AND status=from），
 *       affected!=1 返回 false，由调用方语义化（50331 或业务码，先例：InboundRequestServiceImpl.casConflict）。</li>
 * </ol>
 * 不引入 Spring StateMachine / 事件总线：副作用由调用方同事务直调（P1/P2 先例）。
 */
public final class DocStateMachine {

    /** 单据类型（每类一张迁移表）。 */
    public enum DocKind {
        /** 出库单（12 §1.2 矩阵，BE-W2 启用） */
        OUTBOUND,
        /** 入库单（12 §2.1 矩阵；BE-W1 直写 CAS 先落地，表在此冻结备查/供断言） */
        INBOUND,
        /** 退货单（13-p3b §2.1 矩阵，T3-W1 启用；无审批） */
        RETURN,
        /** 盘点单（13-p3b §2.2 矩阵，T3-W2 启用；TA 全量审批，D23 无自动通过） */
        STOCKTAKE
    }

    /**
     * 出库迁移矩阵（12 §1.2，行=from）：
     * PENDING_ACCEPT → PRINTED（打印）| WITHDRAWN（R4 直撤）| CANCELLED（R8 联动）；
     * PRINTED → PENDING_ACCEPT（WK 重新核对回退）| COMPLETED（登记出库）| CANCELLED（R4 二次确认/R8 联动）；
     * COMPLETED → COMPLAINED（30d 客诉，WK_CREATED）；
     * COMPLAINED → COMPLETED（OPS 裁决，任何结论）；
     * WITHDRAWN / CANCELLED 终态。补打不迁移状态（PRINTED→PRINTED 不走状态机）。
     */
    private static final Map<String, Set<String>> OUTBOUND_TRANSITIONS = Map.of(
            OutboundRequest.STATUS_PENDING_ACCEPT, Set.of(
                    OutboundRequest.STATUS_PRINTED,
                    OutboundRequest.STATUS_WITHDRAWN,
                    OutboundRequest.STATUS_CANCELLED),
            OutboundRequest.STATUS_PRINTED, Set.of(
                    OutboundRequest.STATUS_PENDING_ACCEPT,
                    OutboundRequest.STATUS_COMPLETED,
                    OutboundRequest.STATUS_CANCELLED),
            OutboundRequest.STATUS_COMPLETED, Set.of(OutboundRequest.STATUS_COMPLAINED),
            OutboundRequest.STATUS_COMPLAINED, Set.of(OutboundRequest.STATUS_COMPLETED),
            OutboundRequest.STATUS_WITHDRAWN, Set.of(),
            OutboundRequest.STATUS_CANCELLED, Set.of());

    /**
     * 入库迁移矩阵（12 §2.1 代建链 + 13-p3b §1.1 正向链，两链共表）：
     * 正向链：SUBMITTED → WITHDRAWN(R1) | REJECTED(R2) | ACCEPTED(受理锁单)；ACCEPTED → CONFIRMED(登记
     * 才 addStock，D-3 直落，不复活 REGISTERED)。不可达红线：REJECTED→ACCEPTED ❌、CONFIRMED→WITHDRAWN ❌、
     * SUBMITTED→CONFIRMED ❌（必须经受理）。代建链一字不动。
     */
    private static final Map<String, Set<String>> INBOUND_TRANSITIONS = Map.of(
            InboundRequest.STATUS_PENDING_WA_CONFIRM, Set.of(
                    InboundRequest.STATUS_CONFIRMED,
                    InboundRequest.STATUS_DISPUTED),
            InboundRequest.STATUS_DISPUTED, Set.of(
                    InboundRequest.STATUS_CONFIRMED,
                    InboundRequest.STATUS_REVOKED),
            InboundRequest.STATUS_CONFIRMED, Set.of(),
            InboundRequest.STATUS_REVOKED, Set.of(),
            InboundRequest.STATUS_SUBMITTED, Set.of(
                    InboundRequest.STATUS_WITHDRAWN,
                    InboundRequest.STATUS_REJECTED,
                    InboundRequest.STATUS_ACCEPTED),
            InboundRequest.STATUS_ACCEPTED, Set.of(InboundRequest.STATUS_CONFIRMED),
            InboundRequest.STATUS_WITHDRAWN, Set.of(),
            InboundRequest.STATUS_REJECTED, Set.of());

    /**
     * 退货迁移矩阵（13-p3b §2.1，D-7 登记时扣）：
     * PENDING_ACCEPT → WITHDRAWN（WA 撤回，reason 必填）| ACCEPTED（WK 受理锁单）；
     * ACCEPTED → COMPLETED（WK 现场出货登记——此刻才 returnStock 扣件数+释放托盘）。
     * WITHDRAWN / COMPLETED 终态。红线：受理后不可撤回（ACCEPTED→WITHDRAWN ❌）、
     * 待受理不可直登（PENDING_ACCEPT→COMPLETED ❌，必须经受理）。
     */
    private static final Map<String, Set<String>> RETURN_TRANSITIONS = Map.of(
            com.cangchu.document.entity.ReturnRequest.STATUS_PENDING_ACCEPT, Set.of(
                    com.cangchu.document.entity.ReturnRequest.STATUS_WITHDRAWN,
                    com.cangchu.document.entity.ReturnRequest.STATUS_ACCEPTED),
            com.cangchu.document.entity.ReturnRequest.STATUS_ACCEPTED, Set.of(
                    com.cangchu.document.entity.ReturnRequest.STATUS_COMPLETED),
            com.cangchu.document.entity.ReturnRequest.STATUS_WITHDRAWN, Set.of(),
            com.cangchu.document.entity.ReturnRequest.STATUS_COMPLETED, Set.of());

    /**
     * 盘点迁移矩阵（13-p3b §2.2，D-10 盘亏封顶）：
     * DRAFT → PENDING_APPROVAL（提交，明细 system_qty 快照定格）；
     * PENDING_APPROVAL → REJECTED（驳回，保留记录不动账）| APPROVED（逐 SKU 锁内 GAIN/LOSS）；
     * REJECTED → DRAFT（重新编辑重提，pending_flag 回置 1 撞唯一 → 50356）；APPROVED 终态不可逆。
     * 红线：DRAFT→APPROVED ❌（必须经提交）、APPROVED→任意 ❌（已通过不可逆）、
     * PENDING_APPROVAL→DRAFT ❌（提交后不可自行撤回，只能等审批——快照定格语义）。
     */
    private static final Map<String, Set<String>> STOCKTAKE_TRANSITIONS = Map.of(
            com.cangchu.document.entity.CountSheet.STATUS_DRAFT, Set.of(
                    com.cangchu.document.entity.CountSheet.STATUS_PENDING_APPROVAL),
            com.cangchu.document.entity.CountSheet.STATUS_PENDING_APPROVAL, Set.of(
                    com.cangchu.document.entity.CountSheet.STATUS_REJECTED,
                    com.cangchu.document.entity.CountSheet.STATUS_APPROVED),
            com.cangchu.document.entity.CountSheet.STATUS_REJECTED, Set.of(
                    com.cangchu.document.entity.CountSheet.STATUS_DRAFT),
            com.cangchu.document.entity.CountSheet.STATUS_APPROVED, Set.of());

    private static final Map<DocKind, Map<String, Set<String>>> TABLES = Map.of(
            DocKind.OUTBOUND, OUTBOUND_TRANSITIONS,
            DocKind.INBOUND, INBOUND_TRANSITIONS,
            DocKind.RETURN, RETURN_TRANSITIONS,
            DocKind.STOCKTAKE, STOCKTAKE_TRANSITIONS);

    private DocStateMachine() {
    }

    /** 迁移是否合法（矩阵查表；from/to 未知值一律不可达）。 */
    public static boolean canGo(DocKind kind, String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return TABLES.get(kind).getOrDefault(from, Set.of()).contains(to);
    }

    /** 前置校验：不可达迁移统一抛 {@code DOC_STATE_TRANSITION_INVALID}(50330)。 */
    public static void assertCanGo(DocKind kind, String from, String to) {
        if (!canGo(kind, from, to)) {
            throw new BizException(ErrorCode.DOC_STATE_TRANSITION_INVALID);
        }
    }

    /**
     * CAS 迁移：先 {@link #assertCanGo}（不可达 50330），再
     * {@code UPDATE ... SET status=to WHERE id=? AND status=from}（+extraSet 附加列）。
     *
     * @return affected==1（false=并发被抢占/状态漂移，调用方语义化为 50331 或业务码）
     */
    public static <T> boolean casTransition(BaseMapper<T> mapper, DocKind kind, Long id,
                                            SFunction<T, ?> idCol, SFunction<T, String> statusCol,
                                            String from, String to,
                                            Consumer<LambdaUpdateWrapper<T>> extraSet) {
        assertCanGo(kind, from, to);
        LambdaUpdateWrapper<T> uw = new LambdaUpdateWrapper<T>()
                .eq(idCol, id)
                .eq(statusCol, from)
                .set(statusCol, to);
        if (extraSet != null) {
            extraSet.accept(uw);
        }
        return mapper.update(null, uw) == 1;
    }
}
