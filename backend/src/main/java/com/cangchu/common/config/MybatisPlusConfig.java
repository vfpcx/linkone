package com.cangchu.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.cangchu.common.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MyBatis Plus 配置：分页插件 + 字段自动填充
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 仅对真正按租户隔离的业务表自动追加 tenant_id 条件（兜底防越权，G-2.2）。
     * 全局表（tenants/users 无 tenant_id 列）、跨租户访问表（user_roles 注册期 tenant_id 为 null、
     * sms_codes/login_sessions/password_history 账号域、tenant_applications/invite_codes/capacity_publish
     * 存在 OPS 跨租户或公开查询）一律忽略，避免误伤现有正常流程。
     */
    private static final Set<String> TENANT_FILTER_TABLES = Set.of(
            "stores",
            "tenant_settings",
            "wholesalers",
            "skus",
            "inventories",
            "stock_movements",
            "inbound_requests",
            "inquiry_requests",
            "outbound_requests"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户行级隔离：必须在分页插件之前
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.getTenantId();
                // 登录态推导出可信租户才追加条件；无可信租户（OPS 跨租户/公开）不注入
                return tenantId != null ? new LongValue(tenantId) : new NullValue();
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                if (tableName == null) {
                    return true;
                }
                // 仅过滤白名单内的租户业务表；且仅在存在可信租户上下文时过滤
                String name = tableName.replace("`", "");
                if (!TENANT_FILTER_TABLES.contains(name)) {
                    return true;
                }
                return TenantContext.getTenantId() == null;
            }
        }));

        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                // 自动填充 tenant_id（若实体有此字段）
                Long tenantId = TenantContext.getTenantId();
                if (tenantId != null && metaObject.hasSetter("tenantId")) {
                    this.strictInsertFill(metaObject, "tenantId", Long.class, tenantId);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
