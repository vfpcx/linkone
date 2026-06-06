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
        // Sa-Token 鉴权拦截器
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/common/**", "/api/v1/tenant/**")
                .excludePathPatterns("/api/v1/public/**");

        // 租户上下文拦截器
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/public/account/register",
                        "/api/v1/public/account/login",
                        "/api/v1/public/account/password/reset",
                        "/api/v1/public/account/sms-code",
                        "/api/v1/account/login/rt");
    }
}
