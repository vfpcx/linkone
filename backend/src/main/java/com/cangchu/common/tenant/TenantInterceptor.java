package com.cangchu.common.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 租户拦截器：从 Sa-Token 登录态推导可信 tenant_id 注入 TenantContext。
 *
 * <p>安全规约 G-2.1：tenantId 的唯一可信来源是登录用户的角色绑定（user_roles），
 * 请求头 X-Tenant-Id 仅用于「多租户用户」主动切换，且必须校验该用户确属目标租户，
 * 否则视为越权（42101）。绝不无条件信任客户端传入的 X-Tenant-Id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private final UserRoleMapper userRoleMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 未登录（公开接口或匿名访问）不强制租户上下文
        if (!StpUtil.isLogin()) {
            return true;
        }

        Long userId = StpUtil.getLoginIdAsLong();

        // 查询该用户全部有效角色绑定，得到其可信租户集合
        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getStatus, "ACTIVE"));

        TenantContext.TenantInfo info = new TenantContext.TenantInfo();
        info.setUserId(userId);

        // 主角色（按 priority 取最高，缺省取第一条）
        UserRole primary = roles.stream()
                .max((a, b) -> {
                    int pa = a.getPriority() != null ? a.getPriority() : 0;
                    int pb = b.getPriority() != null ? b.getPriority() : 0;
                    return Integer.compare(pa, pb);
                })
                .orElse(null);
        if (primary != null) {
            info.setActorRole(primary.getRole());
        }

        String tenantIdHeader = request.getHeader("X-Tenant-Id");
        if (tenantIdHeader != null && !tenantIdHeader.isEmpty()) {
            // 显式切换租户：必须校验该用户确属目标租户，否则越权
            Long requested;
            try {
                requested = Long.valueOf(tenantIdHeader);
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.PERMISSION_TENANT_001, "X-Tenant-Id 非法");
            }
            boolean belongs = roles.stream()
                    .anyMatch(r -> requested.equals(r.getTenantId()));
            if (!belongs) {
                log.warn("用户 {} 试图以 X-Tenant-Id={} 越权访问非绑定租户", userId, requested);
                throw new BizException(ErrorCode.PERMISSION_TENANT_001);
            }
            info.setTenantId(requested);
            // 修正主角色为该租户下的绑定角色
            roles.stream().filter(r -> requested.equals(r.getTenantId())).findFirst()
                    .ifPresent(r -> info.setActorRole(r.getRole()));
        } else {
            // 未显式切换：以登录态推导的可信租户为准（取唯一非空绑定租户）
            List<Long> tenantIds = roles.stream()
                    .map(UserRole::getTenantId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (tenantIds.size() == 1) {
                info.setTenantId(tenantIds.get(0));
            }
            // size>1（多租户）未指定 X-Tenant-Id 时不强行选定，留给业务接口按 userId 自行解析
        }

        TenantContext.set(info);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        TenantContext.clear();
    }
}
