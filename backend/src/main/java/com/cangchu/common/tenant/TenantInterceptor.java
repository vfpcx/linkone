package com.cangchu.common.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器：从请求头或 Sa-Token 登录态提取 tenant_id 注入 TenantContext
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();

        // 公开接口不强制租户上下文
        if (path.startsWith("/api/v1/public/")) {
            return true;
        }

        // 已登录用户从 Sa-Token 获取 userId
        if (StpUtil.isLogin()) {
            Long userId = StpUtil.getLoginIdAsLong();
            String tenantIdHeader = request.getHeader("X-Tenant-Id");

            TenantContext.TenantInfo info = new TenantContext.TenantInfo();
            info.setUserId(userId);

            if (tenantIdHeader != null && !tenantIdHeader.isEmpty()) {
                info.setTenantId(Long.valueOf(tenantIdHeader));
            }

            // 从 Sa-Token session 取角色
            Object roleObj = StpUtil.getSession().get("actorRole");
            if (roleObj != null) {
                info.setActorRole(roleObj.toString());
            }

            TenantContext.set(info);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        TenantContext.clear();
    }
}
