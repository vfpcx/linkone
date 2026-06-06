package com.cangchu.account.service;

import com.cangchu.account.dto.*;
import com.cangchu.account.vo.LoginVo;

/**
 * 账号服务接口
 */
public interface AccountService {

    /** 发送短信验证码 */
    void sendSmsCode(SmsCodeSendDto dto, String clientIp);

    /** 注册（角色感知入口） */
    LoginVo register(RegisterDto dto);

    /** 登录（密码+验证码二选一） */
    LoginVo login(LoginDto dto, String clientIp);

    /** 修改密码（登录态） */
    void changePassword(Long userId, ChangePasswordDto dto);

    /** 找回密码 */
    void resetPassword(ResetPasswordDto dto);

    /** 换绑手机号 */
    void changePhone(Long userId, ChangePhoneDto dto);

    /** RT 免密验证码登录（首次自动注册） */
    LoginVo rtSmsLogin(String phone, String code);

    /** 退出登录 */
    void logout(Long userId);
}
