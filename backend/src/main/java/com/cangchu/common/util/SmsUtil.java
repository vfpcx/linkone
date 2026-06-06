package com.cangchu.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 短信工具（MVP mock 模式：打印控制台 + 返回固定验证码）
 */
@Slf4j
@Component
public class SmsUtil {

    @Value("${cangchu.sms.mock:true}")
    private boolean mock;

    @Value("${cangchu.sms.mock-code:888888}")
    private String mockCode;

    /**
     * 发送短信验证码
     * @param phone 手机号
     * @param scene 场景: REGISTER/LOGIN/RESET_PWD/CHANGE_PHONE/RT_LOGIN
     * @return 验证码（mock 模式返回固定码，生产返回 null）
     */
    public String sendSmsCode(String phone, String scene) {
        String masked = phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        if (mock) {
            log.info("[SMS MOCK] 向 {} 发送验证码 [{}], scene={}, code={}", masked, scene, mockCode);
            return mockCode;
        }
        // TODO: v2 对接阿里云短信 SDK
        log.info("[SMS] 向 {} 发送验证码, scene={}", masked, scene);
        return null;
    }

    /**
     * @return mock 验证码
     */
    public String getMockCode() {
        return mockCode;
    }
}
