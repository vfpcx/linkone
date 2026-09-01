package com.cangchu.common.pii;

import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PiiCrypto 单测：AES-GCM 扩展（W8-B1，16 §2.3）+ 既有 HMAC/规范化回归。
 * 纯 POJO 构造（与 Spring 上下文解耦），但 KAT 向量与 test resources application.yml 保持一致。
 */
class PiiCryptoTest {

    /** 与 test resources application.yml 的 hmac-key/dek-v1 同源（Base64 256-bit 测试密钥）。 */
    private static final String TEST_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    /** 与 test resources application.yml 的 hmac-kat 一致。 */
    private static final String REGISTERED_HMAC_KAT =
            "2aff2a1ede191cf2fd4af900d10f48d1e55f31e69dae59d7938c1ac349641534";
    /** 与 test resources application.yml 的 cipher-kat 一致（固定全零 IV 加密 KAT_VECTOR 的确定性密文）。 */
    private static final String CIPHER_KAT = "AAAAAAAAAAAAAAAA/0CZMSIybGDfHbECcMUWmD/fMuCvrrY7Ab6B";

    private PiiCrypto newCrypto() {
        PiiProperties props = new PiiProperties();
        props.setHmacKey(TEST_KEY_B64);
        props.setHmacKat(REGISTERED_HMAC_KAT);
        props.setDekV1(TEST_KEY_B64);
        props.setCipherKat(CIPHER_KAT);
        PiiCrypto crypto = new PiiCrypto(props);
        crypto.init();
        return crypto;
    }

    @Test
    @DisplayName("cipher KAT：decrypt(登记的 cipher-kat) 还原已知向量，算法/密钥被锚定")
    void cipher_kat_decryptsBackToKnownVector() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.decrypt(CIPHER_KAT))
                .as("cipher-kat 是固定全零 IV 加密 KAT_VECTOR 的确定性密文，必须能解回已知向量")
                .isEqualTo(PiiCrypto.KAT_VECTOR);
    }

    @Test
    @DisplayName("cipher：null/空白入参返回 null（列本身 NULLable）")
    void cipher_nullAndBlankAreNull() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isNull();
        assertThat(crypto.encrypt("   ")).isNull();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.decrypt("")).isNull();
        assertThat(crypto.decrypt("   ")).isNull();
    }

    @Test
    @DisplayName("cipher：随机 IV，同明文两次加密输出不同（GCM 语义，防确定性重放）")
    void cipher_randomIvMakesCiphertextNonDeterministic() {
        PiiCrypto crypto = newCrypto();
        String a = crypto.encrypt("13800138000");
        String b = crypto.encrypt("13800138000");
        assertThat(a).isNotEqualTo(b);
        // 且两段都能解开成原号
        assertThat(crypto.decrypt(a)).isEqualTo("13800138000");
        assertThat(crypto.decrypt(b)).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("cipher：多组手机号往返解密还原原号")
    void cipher_roundTrip_matchesOriginal() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.decrypt(crypto.encrypt("13800138000"))).isEqualTo("13800138000");
        assertThat(crypto.decrypt(crypto.encrypt("13612345678"))).isEqualTo("13612345678");
        assertThat(crypto.decrypt(crypto.encrypt(" 13911112222 ")))
                .as("encrypt 入参同走 normalize（trim），往返按规范化后的值比")
                .isEqualTo("13911112222");
    }

    @Test
    @DisplayName("cipher：非 Base64 密文抛 PII_DECRYPT_ERROR（语义 50403，不落 500）")
    void cipher_invalidBase64_throwsPiiDecryptError() {
        PiiCrypto crypto = newCrypto();
        assertThatThrownBy(() -> crypto.decrypt("not-a-valid-base64!!!"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PII_DECRYPT_ERROR));
    }

    @Test
    @DisplayName("cipher：篡改密文任何一字节 GCM tag 校验失败，同样抛 50403")
    void cipher_tamperedCipher_throwsPiiDecryptError() {
        PiiCrypto crypto = newCrypto();
        String cipherText = crypto.encrypt("13800138000");
        byte[] all = Base64.getDecoder().decode(cipherText);
        all[all.length - 1] ^= 0x01; // 翻转 tag 末字节
        String tampered = Base64.getEncoder().encodeToString(all);
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PII_DECRYPT_ERROR));
    }

    @Test
    @DisplayName("decryptOrNull：解密失败回落 null（展示层降级入口，B3 消费）")
    void decryptOrNull_returnsNullOnFailure() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.decryptOrNull("garbage")).isNull();
        assertThat(crypto.decryptOrNull(null)).isNull();
        assertThat(crypto.decryptOrNull(crypto.encrypt("13800138000"))).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("last4：尾号 4 位摘要，空/空白返回 null")
    void last4_extractsLastFourDigits() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.last4("13800138000")).isEqualTo("8000");
        assertThat(crypto.last4(" 13800138000 ")).as("入参同走 normalize（trim）").isEqualTo("8000");
        assertThat(crypto.last4("1234")).isEqualTo("1234");
        assertThat(crypto.last4("12")).as("不足 4 位按原样返回，避免数组越界").isEqualTo("12");
        assertThat(crypto.last4(null)).isNull();
        assertThat(crypto.last4("   ")).isNull();
        assertThat(crypto.last4("")).isNull();
    }

    @Test
    @DisplayName("启动自检：dek-v1 缺失 fail-fast（prod 靠 PII_DEK_V1 无默认值兜底）")
    void init_missingDek_failsFast() {
        PiiProperties props = new PiiProperties();
        props.setHmacKey(TEST_KEY_B64);
        props.setHmacKat(REGISTERED_HMAC_KAT);
        props.setCipherKat(CIPHER_KAT);
        // dekV1 故意不设
        PiiCrypto crypto = new PiiCrypto(props);
        assertThatThrownBy(crypto::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dek-v1");
    }

    @Test
    @DisplayName("启动自检：cipher-kat 缺失 fail-fast")
    void init_missingCipherKat_failsFast() {
        PiiProperties props = new PiiProperties();
        props.setHmacKey(TEST_KEY_B64);
        props.setHmacKat(REGISTERED_HMAC_KAT);
        props.setDekV1(TEST_KEY_B64);
        // cipherKat 故意不设
        PiiCrypto crypto = new PiiCrypto(props);
        assertThatThrownBy(crypto::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cipher-kat");
    }

    @Test
    @DisplayName("启动自检：hmac-kat 配错密钥 fail-fast（拦截密钥配错环境，先例回归）")
    void init_wrongHmacKat_failsFast() {
        PiiProperties props = new PiiProperties();
        props.setHmacKey(TEST_KEY_B64);
        props.setHmacKat("0".repeat(64)); // 故意配错
        props.setDekV1(TEST_KEY_B64);
        props.setCipherKat(CIPHER_KAT);
        PiiCrypto crypto = new PiiCrypto(props);
        assertThatThrownBy(crypto::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAT");
    }

    @Test
    @DisplayName("启动自检：cipher-kat 配错 DEK 向量 fail-fast（拦截密钥配错环境）")
    void init_wrongCipherKat_failsFast() {
        PiiProperties props = new PiiProperties();
        props.setHmacKey(TEST_KEY_B64);
        props.setHmacKat(REGISTERED_HMAC_KAT);
        props.setDekV1(TEST_KEY_B64);
        props.setCipherKat("13800138000"); // 故意配成明文而非固定 IV 密文向量
        PiiCrypto crypto = new PiiCrypto(props);
        assertThatThrownBy(crypto::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAT");
    }

    @Test
    @DisplayName("回归：phoneHmac 已知向量与登记期望值一致")
    void phoneHmac_knownAnswer_regression() {
        PiiCrypto crypto = newCrypto();
        assertThat(crypto.phoneHmac(PiiCrypto.KAT_VECTOR))
                .isEqualTo(REGISTERED_HMAC_KAT);
    }
}
