package com.cangchu.account.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.account.dto.*;
import com.cangchu.account.service.AccountService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.response.R;
import com.cangchu.common.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账号 Controller（7 个 API + 短信发送）
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** 发送短信验证码 */
    @PostMapping("/sms-code")
    public R<Void> sendSmsCode(@Valid @RequestBody SmsCodeSendDto dto, HttpServletRequest request) {
        accountService.sendSmsCode(dto, IpUtil.getClientIp(request));
        return R.ok();
    }

    /** 注册（角色感知入口） */
    @PostMapping("/register")
    public R<LoginVo> register(@Valid @RequestBody RegisterDto dto) {
        LoginVo vo = accountService.register(dto);
        return R.ok(vo);
    }

    /** 登录（密码 + 验证码二选一） */
    @PostMapping("/login")
    public R<LoginVo> login(@Valid @RequestBody LoginDto dto, HttpServletRequest request) {
        LoginVo vo = accountService.login(dto, IpUtil.getClientIp(request));
        return R.ok(vo);
    }

    /** 修改密码（登录态） */
    @PutMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        accountService.changePassword(userId, dto);
        return R.ok();
    }

    /** 找回密码 */
    @PostMapping("/password/reset")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordDto dto) {
        accountService.resetPassword(dto);
        return R.ok();
    }

    /** 换绑手机号 */
    @PutMapping("/phone")
    public R<Void> changePhone(@Valid @RequestBody ChangePhoneDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        accountService.changePhone(userId, dto);
        return R.ok();
    }

    /** RT 免密验证码登录（首次自动注册） */
    @PostMapping("/login/rt")
    public R<LoginVo> rtSmsLogin(@RequestParam String phone, @RequestParam String code) {
        LoginVo vo = accountService.rtSmsLogin(phone, code);
        return R.ok(vo);
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public R<Void> logout() {
        Long userId = StpUtil.getLoginIdAsLong();
        accountService.logout(userId);
        return R.ok();
    }
}
