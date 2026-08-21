package com.cangchu.common.pii;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * PII 密码学单入口（15-pii-hardening-v2 §3 / PII-W1）。
 *
 * <p>阶段 0 提供 HMAC-SHA256 盲索引（{@link #phoneHmac}），供 users.phone_hmac /
 * blacklist.target_value_hmac 双写与回填使用。<b>读路径不使用本类</b>——登录/黑名单命中
 * 仍走旧 phone_hash / 明文列，秒级回滚不受影响。
 *
 * <p>启动自检两道闸（均拒绝启动，把事故拦在启动期）：
 * <ol>
 *   <li>密钥缺失/非法 Base64 → fail-fast（prod 由 ${PII_HMAC_KEY} 无默认值兜底，本处兜「值为空」）；</li>
 *   <li>KAT（known-answer test）：固定向量 {@value #KAT_VECTOR} 的 HMAC 结果必须等于
 *       登记的期望值 {@code cangchu.pii.hmac-kat}——防「密钥配错环境」这一比缺失更高频的事故。</li>
 * </ol>
 *
 * <p>规范化单入口（R1 防线）：{@link #normalize} 目前仅 trim（与既有落库口径一致），
 * 双写与回填共用同一入口，杜绝两处各自规范化产生漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiCrypto {

    /** KAT 已知向量（15 §3：HMAC(indexKey, "13800138000") 与部署登记期望值比对）。 */
    static final String KAT_VECTOR = "13800138000";

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final PiiProperties properties;

    private volatile SecretKeySpec hmacKeySpec;

    @PostConstruct
    void init() {
        String keyB64 = properties.getHmacKey();
        if (keyB64 == null || keyB64.isBlank()) {
            throw new IllegalStateException(
                    "[PII] cangchu.pii.hmac-key 缺失——prod 须经环境变量 PII_HMAC_KEY 注入（fail-fast，拒绝启动）");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[PII] cangchu.pii.hmac-key 不是合法 Base64（拒绝启动）", e);
        }
        if (keyBytes.length < 16) {
            throw new IllegalStateException("[PII] cangchu.pii.hmac-key 长度不足（须 >=128-bit，建议 256-bit，拒绝启动）");
        }
        this.hmacKeySpec = new SecretKeySpec(keyBytes, HMAC_ALGO);

        // KAT：错的密钥会让全体用户登录索引不匹配且现象诡异，必须拦在启动期（15 §3）
        String expected = properties.getHmacKat();
        if (expected == null || expected.isBlank()) {
            throw new IllegalStateException(
                    "[PII] cangchu.pii.hmac-kat 缺失——启动 KAT 期望值必须登记（prod 经 PII_HMAC_KAT 注入，拒绝启动）");
        }
        String actual = phoneHmac(KAT_VECTOR);
        if (!expected.trim().equalsIgnoreCase(actual)) {
            throw new IllegalStateException(
                    "[PII] 启动 KAT 自检失败：HMAC 已知向量结果与登记期望值不匹配——疑似密钥配错环境（拒绝启动）");
        }
        log.info("[PII] HMAC 密钥装载完成，启动 KAT 自检通过（write-mode={}）", properties.getWriteMode());
    }

    /**
     * 手机号（或黑名单 PHONE 值）→ HMAC-SHA256 盲索引，64 位小写 hex。
     * 双写与回填的唯一产生点；null/空白入参返回 null（列本身 NULLable）。
     */
    public String phoneHmac(String phone) {
        String normalized = normalize(phone);
        if (normalized == null) {
            return null;
        }
        SecretKeySpec spec = this.hmacKeySpec;
        if (spec == null) {
            throw new IllegalStateException("[PII] HMAC 密钥未初始化");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(spec);
            return toHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("[PII] HMAC 计算失败", e);
        }
    }

    /** 是否双写（透传 {@link PiiProperties#isDualWrite()}，写切点用）。 */
    public boolean isDualWrite() {
        return properties.isDualWrite();
    }

    /** 规范化单入口（R1）：trim；空白视为无值。阶段 0 与既有明文落库口径保持一致，不做激进归一。 */
    static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String t = phone.trim();
        return t.isEmpty() ? null : t;
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
