package com.cangchu.common.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 短信工具（MVP mock 模式：打印控制台 + 返回固定验证码）
 *
 * <p>安全约束（D-03）：mock 万能验证码（默认 888888）由配置开关 {@code cangchu.sms.mock} 驱动，
 * 仅 dev/test 等非生产环境在配置开启时生效；**prod profile 下无论配置如何一律强制禁用**，
 * {@link #isMockEnabled()} 返回 false、{@link #getMockCode()} 返回 null，
 * 杜绝 prod 误开 mock 导致 888888 接管任意账号。
 */
@Slf4j
@Component
public class SmsUtil {

    private final Environment environment;

    @Value("${cangchu.sms.mock:false}")
    private boolean mockConfig;

    @Value("${cangchu.sms.mock-code:}")
    private String mockCode;

    /** 解析后的最终 mock 开关：配置开启 + 非 prod profile 时才为 true */
    private boolean mockEnabled;

    public SmsUtil(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        // 安全兜底：prod profile 强制禁用 mock，无视配置；其余环境（dev/test/默认）由配置开关决定
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        this.mockEnabled = !prodProfile && mockConfig
                && mockCode != null && !mockCode.isEmpty();
        if (mockEnabled) {
            log.warn("[SMS MOCK] mock 验证码已启用（非生产环境）。生产环境严禁开启。");
        }
    }

    /**
     * 发送短信验证码
     * @param phone 手机号
     * @param scene 场景: REGISTER/LOGIN/RESET_PWD/CHANGE_PHONE/RT_LOGIN
     * @return 验证码（mock 模式返回固定码，生产返回 null）
     */
    public String sendSmsCode(String phone, String scene) {
        String masked = phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        if (mockEnabled) {
            log.info("[SMS MOCK] 向 {} 发送验证码 [{}], scene={}, code={}", masked, scene, mockCode);
            return mockCode;
        }
        // TODO: v2 对接阿里云短信 SDK
        log.info("[SMS] 向 {} 发送验证码, scene={}", masked, scene);
        return null;
    }

    /**
     * @return mock 是否真正生效（仅 dev profile 且显式开启）
     */
    public boolean isMockEnabled() {
        return mockEnabled;
    }

    /**
     * @return mock 验证码；mock 未生效时返回 null（避免被当万能码短路）
     */
    public String getMockCode() {
        return mockEnabled ? mockCode : null;
    }
}
