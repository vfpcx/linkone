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
     * AES-256-GCM 数据加密密钥（Base64 编码 256-bit，16 §2.3）。用于全号密文列
     * （users.phone_cipher 等）。与 hmacKey 分离：索引键不轮换，数据密钥按需可轮换。
     * prod 经 {@code ${PII_DEK_V1}} 注入，故意【无默认值】——缺失即启动失败（fail-fast）。
     */
    private String dekV1;

    /**
     * 启动 KAT 已知向量（16 §2.3）：启动自检以固定全零 IV 加密 {@code KAT_VECTOR}，结果必须等于
     * 本登记值（确定性 KAT，与 HMAC 同口径）；拦截「数据密钥未装载 / 加解密器与库不匹配」。
     * prod 期望值随部署脚本走，不进 git。
     */
    private String cipherKat;
}
