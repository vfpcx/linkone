package com.cangchu.common.tenant;

import com.cangchu.account.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TA 一账号多仓收敛支持（20-p5-ta-multi-warehouse.md §2）。
 *
 * <p>「当前仓」唯一可信来源：前端 X-Tenant-Id → TenantInterceptor 校验归属后写入
 * {@link TenantContext}。各 TA 端 gate 统一改为：
 * <ol>
 *   <li>TenantContext 优先：并二次校验该用户在该仓确有目标角色（AuthService#hasRole 三参）——
 *       防「A 仓 TA + B 仓 WA 带 X-Tenant-Id=B 借 TA 端接口操作 B 仓」的跨仓角色组合越权；</li>
 *   <li>无上下文回退 {@link AuthService#findBoundTenantId}：单仓 TA / 场景测试 / 内部调用兼容
 *       （与 WA 多仓 {@code listActiveWholesalerIds(userId, role, tenantId)} 的 null 回退同构）。</li>
 * </ol>
 * 返回 {@code null} 仅表示「无上下文且登录态无该角色绑定」，调用方沿用原错误码语义区分
 * （50210 未绑定租户 / 42001 无角色 / 42004 WE 专属文案）。
 */
@Component
@RequiredArgsConstructor
public class TenantScopeAuthSupport {

    private final AuthService authService;

    /** TA 当前仓解析（gate 用；TenantContext 优先 + 该仓 TA 校验，无则回退登录态）。 */
    public Long scopedTaTenantId(Long userId) {
        return scoped(userId, "TA");
    }

    /** TA 或 ST 当前仓解析（gate 用；requireStOrTa 同构：该仓 TA 或 ST 放行）。 */
    public Long scopedTaOrStTenantId(Long userId) {
        return scopedAny(userId, "TA", "ST");
    }

    /** TA 或 WK 当前仓解析（gate 用；TA 工作台 requireTaOrWk 同构：该仓 TA 或 WK 放行）。 */
    public Long scopedTaOrWkTenantId(Long userId) {
        return scopedAny(userId, "TA", "WK");
    }

    private Long scoped(Long userId, String role) {
        Long scoped = TenantContext.getTenantId();
        if (scoped != null) {
            // 该仓无目标角色 → 返回 null：TenantInterceptor 已保证用户确属该仓（任一角色），
            // 由调用方按原错误码语义区分（WE→42004、无 TA 绑定→50210/42001），
            // 保证既有单仓 WE 权限矩阵测试语义不变（回归 143 例中 4 例曾因抛 42001 破坏 42004）。
            return authService.hasRole(userId, role, scoped) ? scoped : null;
        }
        return authService.findBoundTenantId(userId, role);
    }

    private Long scopedAny(Long userId, String... roles) {
        Long scoped = TenantContext.getTenantId();
        if (scoped != null) {
            for (String role : roles) {
                if (authService.hasRole(userId, role, scoped)) {
                    return scoped;
                }
            }
            return null;
        }
        for (String role : roles) {
            Long tenantId = authService.findBoundTenantId(userId, role);
            if (tenantId != null) {
                return tenantId;
            }
        }
        return null;
    }
}
