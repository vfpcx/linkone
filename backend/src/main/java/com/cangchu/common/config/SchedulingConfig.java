package com.cangchu.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（P2 Wave2 引入：退驻归档 WholesalerArchiveJob）。
 * 独立配置类而非加在启动类上，便于测试上下文按需排除。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
