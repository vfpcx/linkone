package com.cangchu.common.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 雪花 ID 工具（基于 Hutool）
 */
@Component
public class SnowflakeIdUtil {

    @Value("${cangchu.snowflake.worker-id:1}")
    private long workerId;

    @Value("${cangchu.snowflake.datacenter-id:1}")
    private long datacenterId;

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
