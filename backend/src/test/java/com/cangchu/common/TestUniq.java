package com.cangchu.common;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试造数唯一值工具（仅测试侧使用）。
 *
 * <p>背景：多条场景链共用 "T" + (snowflakeId % 1_000_000) 生成租户简码，
 * 雪花 ID 低 6 位十进制在同一 JVM（共享 H2）内累计数百租户后按生日悖论
 * 约有 1%~2% 概率撞 uk_simple_code 唯一键，造成回归偶发红（W5 已知抖动①）。
 *
 * <p>方案：JVM 全局单调计数器，前缀 "Z"（与现存 T/S/R/B/Q/W 前缀互斥），
 * 产出 8 字符简码（tenant_simple_code VARCHAR(8)），跨测试类绝对无碰撞。
 */
public final class TestUniq {

    private static final AtomicInteger TENANT_CODE_SEQ = new AtomicInteger(0);

    private TestUniq() {
    }

    /** 全局唯一租户简码，形如 Z0000001（8 字符，命中 VARCHAR(8) 上限）。 */
    public static String tenantSimpleCode() {
        return "Z" + String.format("%07d", TENANT_CODE_SEQ.incrementAndGet());
    }
}
