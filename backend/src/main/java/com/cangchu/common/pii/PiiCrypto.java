package com.cangchu.common.pii;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * PII 密码学单入口（15-pii-hardening-v2 §3 / PII-W1；W8 起扩展 AES-GCM，16 §2.3）。
 *
 * <p>阶段 0 提供 HMAC-SHA256 盲索引（{@link #phoneHmac}），供 users.phone_hmac /
 * blacklist.target_value_hmac 双写与回填使用。W8-B1 起同门扩展数据加密：
 * <ul>
 *   <li>{@link #encrypt} / {@link #decrypt}：AES-256-GCM，输出 Base64(iv‖ct‖tag)，
 *       V31 起写切点与回填同时落 cipher 列（明文列原样保留）；</li>
 *   <li>{@link #last4}：尾号 4 位摘要（users/sms_codes 双写 last4 列，免解密打码）；</li>
 *   <li>{@link #decryptOrNull}：展示层降级解密（B3 消费），失败回落 null 而非抛错。</li>
 * </ul>
 * <b>读路径不使用本类</b>——登录/黑名单命中经 {@link PiiHmacQueries} 走 hmac 盲索引（V34 明文列已删）。
 *
 * <p>启动自检两道闸（均拒绝启动，把事故拦在启动期）：
 * <ol>
 *   <li>密钥缺失/非法 Base64 → fail-fast（prod 由 ${PII_HMAC_KEY} / ${PII_DEK_V1} 无默认值兜底，
 *       本处兜「值为空」）；</li>
 *   <li>KAT（known-answer test）：HMAC 固定向量 {@value #KAT_VECTOR} 的结果必须等于登记的
 *       {@code cangchu.pii.hmac-kat}；cipher 固定全零 IV 加密 {@value #KAT_VECTOR} 的密文必须等于
 *       登记的 {@code cangchu.pii.cipher-kat}（确定性可比对）——防「密钥配错环境」这一比缺失更高频的事故。</li>
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
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final PiiProperties properties;

    private volatile SecretKeySpec hmacKeySpec;
    private volatile SecretKeySpec dekKeySpec;

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

        // W8-B1（16 §2.3）：AES-GCM 数据密钥 fail-fast + cipher 往返 KAT
        String dekB64 = properties.getDekV1();
        if (dekB64 == null || dekB64.isBlank()) {
            throw new IllegalStateException(
                    "[PII] cangchu.pii.dek-v1 缺失——prod 须经环境变量 PII_DEK_V1 注入（fail-fast，拒绝启动）");
        }
        byte[] dekBytes;
        try {
            dekBytes = Base64.getDecoder().decode(dekB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[PII] cangchu.pii.dek-v1 不是合法 Base64（拒绝启动）", e);
        }
        if (dekBytes.length < 16) {
            throw new IllegalStateException("[PII] cangchu.pii.dek-v1 长度不足（须 >=128-bit，建议 256-bit，拒绝启动）");
        }
        this.dekKeySpec = new SecretKeySpec(dekBytes, "AES");

        String cipherKat = properties.getCipherKat();
        if (cipherKat == null || cipherKat.isBlank()) {
            throw new IllegalStateException(
                    "[PII] cangchu.pii.cipher-kat 缺失——启动 KAT 向量必须登记（prod 经 PII_CIPHER_KAT 注入，拒绝启动）");
        }
        try {
            // 确定性 KAT（16 §2.3，main 口径）：固定全零 IV 加密 KAT_VECTOR，结果必须等于登记值
            byte[] katIv = new byte[GCM_IV_BYTES];
            String actualCipher = encryptWithIv(KAT_VECTOR, katIv);
            if (!cipherKat.trim().equals(actualCipher)) {
                throw new IllegalStateException(
                        "[PII] 启动 KAT 自检失败：AES-GCM 确定性 KAT 与登记期望值不匹配（拒绝启动）");
            }
        } catch (Exception e) {
            throw new IllegalStateException("[PII] 启动 KAT 自检失败：AES-GCM 确定性 KAT 异常（拒绝启动）", e);
        }
        log.info("[PII] HMAC 密钥装载完成，启动 KAT 自检通过");
        log.info("[PII] AES-GCM 数据密钥装载完成，cipher 确定性 KAT 自检通过");
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

    /**
     * AES-256-GCM 加密（16 §2.3）：输出 {@code Base64(iv ‖ ct ‖ tag)}（12 字节随机 IV + 128-bit tag）。
     * null/空白入参返回 null（列本身 NULLable）。
     */
    public String encrypt(String plain) {
        String normalized = normalize(plain);
        if (normalized == null) {
            return null;
        }
        SecretKeySpec spec = this.dekKeySpec;
        if (spec == null) {
            throw new IllegalStateException("[PII] AES-GCM 数据密钥未初始化");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            return encryptWithIv(normalized, iv);
        } catch (Exception e) {
            throw new IllegalStateException("[PII] AES-GCM 加密失败", e);
        }
    }

    /**
     * 指定 IV 的 AES-256-GCM 加密（确定性 KAT 用），输出 {@code Base64(iv ‖ ct ‖ tag)}。
     * KAT 固定全零 IV 保证密文确定可比对；业务加密走随机 IV（见 {@link #encrypt}）。
     */
    private String encryptWithIv(String normalized, byte[] iv) {
        SecretKeySpec spec = this.dekKeySpec;
        if (spec == null) {
            throw new IllegalStateException("[PII] AES-GCM 数据密钥未初始化");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, spec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("[PII] AES-GCM 加密失败", e);
        }
    }

    /**
     * AES-256-GCM 解密：输入 {@code Base64(iv ‖ ct ‖ tag)}，返回明文。
     * null/空白返回 null；密文非法/被篡改/tag 校验失败抛 {@link ErrorCode#PII_DECRYPT_ERROR}（语义 50403）。
     */
    public String decrypt(String cipherText) {
        String normalized = normalize(cipherText);
        if (normalized == null) {
            return null;
        }
        SecretKeySpec spec = this.dekKeySpec;
        if (spec == null) {
            throw new IllegalStateException("[PII] AES-GCM 数据密钥未初始化");
        }
        try {
            byte[] all = Base64.getDecoder().decode(normalized);
            if (all.length < GCM_IV_BYTES + 1) {
                throw new IllegalArgumentException("[PII] 密文长度非法");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, GCM_IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, GCM_IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, spec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PII_DECRYPT_ERROR);
        }
    }

    /**
     * 展示层降级解密（16 §2.3，B3 消费）：解密失败返回 null（不抛 50403），
     * 由调用方回落 last4/占位符——VO 打码处解密失败不应 500。
     */
    public String decryptOrNull(String cipherText) {
        try {
            return decrypt(cipherText);
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.PII_DECRYPT_ERROR) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 尾号 4 位摘要（V31 起 users/sms_codes 双写 last4 列）：空→null。
     * 与黑名单 PHONE_**** 摘要同源，供列表/日志/展示层免解密打码（16 §1.6）。
     */
    public String last4(String phone) {
        String normalized = normalize(phone);
        if (normalized == null) {
            return null;
        }
        return normalized.length() >= 4 ? normalized.substring(normalized.length() - 4) : normalized;
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
