package com.cangchu.tenant.service;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * 批发商主体状态机收口（P2 Wave2，PRD 04-core-flows §1.8）。
 *
 * <p>所有入驻生态相关的 wholesalers.status 转换必须经 {@link #assertTransition} 集中校验，
 * 禁止散落在各 Service 内自行 if/else 判断（防不可达转移遗漏）。
 *
 * <pre>
 * ACTIVE ──R13 退驻审批通过──▶ WITHDRAWN ──60 天内恢复──▶ ACTIVE
 *   │                              └──超 60 天归档──▶ ARCHIVED（终态）
 *   └──R14 强制下架──▶ OFFLINE（终态，不可原地恢复，需重新入驻）
 *
 * 不可达（本期明确禁止）：
 *   WITHDRAWN → OFFLINE   已退驻不可再强制下架（50202）
 *   OFFLINE   → ACTIVE    已下架不可原地恢复（50318，无任何端点提供该转换）
 *   OFFLINE   → WITHDRAWN 已下架→争议中→已退驻走 OPS 仲裁（P4 billing，本期不开）
 *   ARCHIVED  → 任意       归档为终态
 * </pre>
 */
public final class WholesalerStateMachine {

    public static final String ACTIVE = "ACTIVE";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String OFFLINE = "OFFLINE";
    public static final String ARCHIVED = "ARCHIVED";

    /** 合法转移表：from → 允许的 to 集合。 */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            ACTIVE, Set.of(WITHDRAWN, OFFLINE),
            WITHDRAWN, Set.of(ACTIVE, ARCHIVED),
            OFFLINE, Set.of(),
            ARCHIVED, Set.of()
    );

    private WholesalerStateMachine() {
    }

    /** 转移是否可达（只判定，不抛错；供测试断言与只读判断）。 */
    public static boolean canTransition(String from, String to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 断言状态转移可达，否则按来源状态抛语义错误码：
     * <ul>
     *   <li>from=WITHDRAWN 的非法转移 → 50202（已退驻）——含「已退驻→已下架」不可达；</li>
     *   <li>其余非法转移 → 50318（状态不允许该操作）——含「已下架→正常」不可原地恢复。</li>
     * </ul>
     */
    public static void assertTransition(String from, String to) {
        if (canTransition(from, to)) {
            return;
        }
        if (WITHDRAWN.equals(from)) {
            throw new BizException(ErrorCode.WHOLESALER_WITHDRAWN,
                    "批发商已退驻，不允许转为 " + to);
        }
        throw new BizException(ErrorCode.WHOLESALER_STATE_TRANSITION_INVALID,
                "批发商状态 " + from + " 不允许转为 " + to);
    }
}
