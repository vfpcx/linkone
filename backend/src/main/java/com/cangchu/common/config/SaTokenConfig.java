package com.cangchu.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.tenant.TenantInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Sa-Token 鉴权 + TenantInterceptor 注册
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Autowired
    private TenantInterceptor tenantInterceptor;

    /** 附件本地盘目录（P3 BE-W1，12 §4.4；与 FileStorageServiceImpl 同源） */
    @Value("${app.upload-dir:./data/uploads}")
    private String uploadDir;

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
                        "/api/v1/admin/**",
                        // P2 入驻 Wave1：WA 自助申请 / OPS 黑名单管理均需登录
                        "/api/v1/wholesaler/**",
                        "/api/v1/ops/**",
                        // P3 BE-W1：站内信（收件人=本人）+ 附件上传均需登录（12 §4.3/§4.4）
                        "/api/v1/notifications/**",
                        "/api/v1/files")
                .excludePathPatterns(
                        "/api/v1/account/register",
                        "/api/v1/account/login",
                        "/api/v1/account/login/rt",
                        "/api/v1/account/sms-code",
                        "/api/v1/account/password/reset",
                        "/api/v1/tenant/capacity",
                        // P2 Wave6 DEF-1：公开租户目录（WA 注册页选仓；匿名，服务层 IP 限流防枚举）。
                        // 注意前缀是 tenants（复数），不落在上方 /api/v1/tenant/** include 内；
                        // 此处显式登记以声明鉴权归属（G-1.1/G-1.2），防止后续误纳入登录拦截。
                        "/api/v1/tenants/directory");

        // 租户上下文拦截器：覆盖全量业务接口，公开接口（无需登录态）放行不强制租户上下文
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/account/register",
                        "/api/v1/account/login",
                        "/api/v1/account/login/rt",
                        "/api/v1/account/sms-code",
                        "/api/v1/account/password/reset",
                        "/api/v1/tenant/capacity",
                        "/api/v1/tenants/directory");
    }

    /**
     * P3 BE-W1（12 §4.4）：附件静态映射 /files/** → 本地盘 upload-dir。
     * GET 免登录放行（不在上方 include 前缀内）：URL 含 UUID 不可枚举，试点可接受；
     * P5 换 OSS 签名 URL 时前端字段结构不变。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}
