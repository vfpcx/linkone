package com.cangchu.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.tenant.TenantInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 鉴权 + TenantInterceptor 注册
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Autowired
    private TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sa-Token 鉴权拦截器（checkLogin）
        // include：所有需登录的业务前缀（account/common/tenant/admin）
        // exclude：真实存在的公开接口（注册/登录/RT 免密/发短信/找回密码 + 公开容量查询）
        // 注意：改密 /api/v1/account/password (PUT) 与 换绑 /api/v1/account/phone (PUT)、
        //       退出 /api/v1/account/logout 均需登录，故不在 exclude 中。
        //       找回密码 reset 路径为 /api/v1/account/password/reset (POST)，公开；
        //       它是 /password 的子路径，需用精确路径放行，且 include 仍覆盖 /password。
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns(
                        "/api/v1/account/**",
                        "/api/v1/common/**",
                        "/api/v1/tenant/**",
                        "/api/v1/admin/**")
                .excludePathPatterns(
                        "/api/v1/account/register",
                        "/api/v1/account/login",
                        "/api/v1/account/login/rt",
                        "/api/v1/account/sms-code",
                        "/api/v1/account/password/reset",
                        "/api/v1/tenant/capacity");

        // 租户上下文拦截器：覆盖全量业务接口，公开接口（无需登录态）放行不强制租户上下文
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/account/register",
                        "/api/v1/account/login",
                        "/api/v1/account/login/rt",
                        "/api/v1/account/sms-code",
                        "/api/v1/account/password/reset",
                        "/api/v1/tenant/capacity");
    }
}
