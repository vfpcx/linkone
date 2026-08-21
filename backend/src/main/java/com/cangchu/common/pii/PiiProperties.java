package com.cangchu.common.pii;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PII 硬化配置（15-pii-hardening-v2 §3）。
 *
 * <p>命名空间 {@code cangchu.pii.*}（沿用 cangchu.sms.* 先例，字段多于 2 个故升格
 * {@code @ConfigurationProperties}）。密钥落点：
 * <ul>
 *   <li>prod：application-prod.yml 经 {@code ${PII_HMAC_KEY}} / {@code ${PII_HMAC_KAT}}
 *       注入，故意【无默认值】——缺失即启动失败（fail-fast，复刻 MYSQL_PASSWORD 先例）。</li>
 *   <li>dev/test：固定测试密钥写在 application-dev.yml / 测试 application.yml，仅供测试。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cangchu.pii")
public class PiiProperties {

    /** HMAC-SHA256 盲索引密钥（Base64 编码 256-bit）。索引密钥不轮换（15 §3）。 */
    private String hmacKey;

    /**
     * 启动 KAT（known-answer test）期望值：{@code hex(HMAC(hmacKey, "13800138000"))}。
     * Spring 占位符只对「缺失」fail-fast，对「错值」不 fail——KAT 把配错密钥拦在启动期。
     * prod 期望值随部署脚本走，不进 git。
     */
    private String hmacKat;

    /**
     * 写模式：{@code legacy}（只写旧列）| {@code dual}（同写旧列 + hmac 列）。
     * 阶段 0 回滚口径：拨回 legacy 即止血（新列数据留存无害）。
     */
    private String writeMode = "legacy";

    /**
     * 是否在启动时跑一次存量回填（阶段 0 一次性动作）。默认 false——跑完请拨回，
     * 否则每次重启都空扫全表。见 {@link PiiBackfillRunner}。
     */
    private boolean backfillOnStartup = false;

    /** 存量回填单批行数（批内逐行 CAS 更新，不长事务持锁）。 */
    private int backfillBatchSize = 500;

    /** 是否双写（阶段 0 唯一分叉点；读路径与本开关无关，一律走旧列）。 */
    public boolean isDualWrite() {
        return "dual".equalsIgnoreCase(writeMode);
    }
}
