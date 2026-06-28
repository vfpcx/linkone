package com.cangchu.common.config;

import org.redisson.config.SingleServerConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 */
@Configuration
public class RedisConfig {

    /**
     * Redisson 单机连接稳态参数。
     *
     * <p>背景：本机用 Memurai(Windows, localhost:6379)，会在空闲一段时间后主动关闭连接。
     * Redisson 默认 pingConnectionInterval=0（不主动探活），空闲连接被对端 RST 后，
     * Redisson 仍把它留在池里，下一次 Sa-Token 写 session 时命中这条已死连接 →
     * io.netty.channel.StacklessClosedChannelException → WriteRedisConnectionException →
     * RedisConnectionFailureException「Unable to write command into connection」→ 业务侧 90001。
     *
     * <p>核心修复是开启 pingConnectionInterval（定期 PING 保活+剔除死连接）。
     * 其余参数收紧重试/超时/池子，避免偶发抖动放大成业务失败。
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonCustomizer() {
        return config -> {
            // netty 线程数（日志提示 "Try to increase nettyThreads"）
            config.setNettyThreads(32);

            SingleServerConfig single = config.useSingleServer();
            // 核心：每 30s 对连接发 PING 保活，并剔除被对端关闭的死连接
            single.setPingConnectionInterval(30000);
            // 空闲超过 60s 的连接回收，避免长期持有可能已被 Memurai 关闭的连接
            single.setIdleConnectionTimeout(60000);
            // TCP keepalive + 关闭 Nagle，降低写延迟与半开连接概率
            single.setKeepAlive(true);
            single.setTcpNoDelay(true);
            // 命令重试：3 次、间隔 1.5s（探活+重连后通常一次即可恢复）
            single.setRetryAttempts(3);
            single.setRetryInterval(1500);
            // 单命令超时 3s，与 spring.data.redis.timeout 对齐
            single.setTimeout(3000);
            single.setConnectTimeout(10000);
            // 连接池：保持至少 8 条热连接，最多 32 条，避免冷启动放大首包延迟
            single.setConnectionMinimumIdleSize(8);
            single.setConnectionPoolSize(32);
        };
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
