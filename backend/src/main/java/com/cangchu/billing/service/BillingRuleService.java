package com.cangchu.billing.service;

import com.cangchu.billing.dto.BillingRuleSaveDto;
import com.cangchu.billing.vo.BillingRuleVo;
import com.cangchu.billing.vo.BillingRulesVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 计费规则服务（P4 W1，14 §2）。
 *
 * <p>权限切点（14 §2.4）：写=仓库管理员（TA）专属；读=结算员（ST）或 TA
 * （billing 域新 gate，requireWkOrTa 同构先例）。WE 拒 42004、WK/WA 拒 42001。
 */
public interface BillingRuleService {

    /**
     * 读当前规则 + 历史版本（TA/ST）。
     * 空态契约：无任何规则时 {@code current=null, history=[]}。
     */
    BillingRulesVo getRules(Long userId);

    /**
     * 保存规则（TA；R20 变更事务，14 §2.1）：
     * <ol>
     *   <li>校验：至少一维启用且启用维单价非空 ≥0（违者 50379）；</li>
     *   <li>与当前规则完全相同 → 幂等空转（不计版本、不通知、无需 confirmed）；</li>
     *   <li>真实变更需 confirmed=true 凭据（缺失 40003）；</li>
     *   <li>当日已有版本行 → 覆写（同日最后一次生效）；否则关旧行（to=今日−1）+ 插新行（from=今日，version+1）；</li>
     *   <li>首版：effective_from=首次保存日、version=1、无需 confirmed；历史不补出账（D-P4-4）；</li>
     *   <li>同事务镜像 tenant_settings.billing_dim（QTY/PALLET/BOTH）+ 通知全部在驻批发商管理员。</li>
     * </ol>
     *
     * @return 保存后的当前生效版本
     */
    BillingRuleVo saveRule(Long userId, BillingRuleSaveDto dto);

    // ==================== W2 回放引擎读取出口（跨波契约，14 §1.1 规则段(D)） ====================

    /**
     * 只读：租户全量规则版本链，effective_from 升序（W2 计费引擎按日期取段；W3 出账分段）。
     * 段区间语义：[effective_from, effective_to]，effective_to=null 为开区间（当前生效）。
     * 无规则返回空列表（引擎侧对应「规则前天数不出账」，D-P4-4）。
     *
     * @param untilInclusive 只取 effective_from ≤ 该日的版本；null=全量
     */
    List<BillingRuleVo> listRuleChain(Long tenantId, LocalDate untilInclusive);
}
