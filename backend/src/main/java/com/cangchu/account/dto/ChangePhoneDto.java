package com.cangchu.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 换绑手机号请求
 */
@Data
public class ChangePhoneDto {

    @NotBlank(message = "原手机号验证码不能为空")
    private String oldSmsCode;

    @NotBlank(message = "新手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String newPhone;

    @NotBlank(message = "新手机号验证码不能为空")
    private String newSmsCode;

    @NotBlank(message = "密码不能为空")
    private String password;
}
