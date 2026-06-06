package com.cangchu.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterDto {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).{6,20}$", message = "密码强度不足（6-20位，含字母数字）")
    private String password;

    @NotBlank(message = "验证码不能为空")
    private String smsCode;

    /** 注册入口角色: TA/WK/ST/WA/WE/RT */
    private String role = "TA";

    /** 邀请码（员工注册场景） */
    private String inviteCode;

    /** 昵称 */
    private String nickname;
}
