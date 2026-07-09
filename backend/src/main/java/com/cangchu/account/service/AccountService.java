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

    /**
     * 按用户 id 取其手机号（users 表归 account 域，跨域只经此出口，不直连 UserMapper）。
     *
     * <p>供 B2 store-front（P2 定价 Wave 3b）把已登录 RT 的 userId 解析为定价身份 rtPhone
     * （customer_prices.rt_phone 口径）。用户不存在 / 已注销无手机号 → 返回 {@code null}
     * （调用方据此按匿名走公开价，不抛异常）。
     */
    String getPhoneByUserId(Long userId);
}
