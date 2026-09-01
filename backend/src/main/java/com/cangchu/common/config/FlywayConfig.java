package com.cangchu.common.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 配置：注册 database-specific 解析器。
 *
 * <p>配合 {@link FlywayDatabaseSpecificResolver} 使用——Flyway Community 11.7.2 不识别
 * {@code __mysql}/{@code __h2} 后缀，必须跳过默认解析器（skipDefaultResolvers），否则
 * 两个变体同时被解析会报 "Found more than one migration with version 33"。
 * 本仓库当前无 Java 迁移（V35 已随 blacklist 改写内联进 V34 而删除），故跳过默认解析器无副作用；
 * 若日后新增 Java 迁移，需在 {@link FlywayDatabaseSpecificResolver} 中一并委托
 * {@code JavaMigrationResolver}。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer databaseSpecificFlywayCustomizer() {
        return configuration -> configuration
                .resolvers(new FlywayDatabaseSpecificResolver())
                .skipDefaultResolvers(true);
    }
}
