package com.cangchu.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginDto {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 密码登录时必填 */
    private String password;

    /** 验证码登录时必填 */
    private String smsCode;

    private String device = "PC";

    private String deviceInfo;
}
