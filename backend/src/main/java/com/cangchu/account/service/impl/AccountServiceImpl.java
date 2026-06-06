package com.cangchu.account.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.dto.*;
import com.cangchu.account.entity.*;
import com.cangchu.account.mapper.*;
import com.cangchu.account.service.AccountService;
import com.cangchu.account.vo.LoginVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.common.util.SnowflakeIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账号服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final LoginSessionMapper loginSessionMapper;
    private final PasswordHistoryMapper passwordHistoryMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final SmsUtil smsUtil;

    /** BCrypt cost >= 10 (per PRD 05 Section 16.2) */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    /** 密码错误最大次数 */
    private static final int MAX_LOGIN_FAILURES = 5;
    /** 锁定时长（分钟） */
    private static final int LOCKOUT_MINUTES = 30;
    /** 短信验证码有效期（分钟） */
    private static final int SMS_EXPIRE_MINUTES = 5;
    /** 验证码最大验证次数 */
    private static final int SMS_MAX_VERIFY = 5;
    /** 单日最大短信发送次数 */
    private static final int SMS_DAILY_MAX = 10;

    // ==================== 短信验证码 ====================

    @Override
    @Transactional
    public void sendSmsCode(SmsCodeSendDto dto, String clientIp) {
        String phone = dto.getPhone();
        String scene = dto.getScene();

        // 60s 防重
        SmsCode lastCode = smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getScene, scene)
                .orderByDesc(SmsCode::getCreatedAt)
                .last("LIMIT 1"));
        if (lastCode != null && lastCode.getCreatedAt().plusSeconds(60).isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.AUTH_SMS_004);
        }

        // 单日 10 次上限
        long todayCount = smsCodeMapper.selectCount(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getScene, scene)
                .ge(SmsCode::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
        if (todayCount >= SMS_DAILY_MAX) {
            throw new BizException(ErrorCode.AUTH_SMS_005);
        }

        // 发送验证码（MVP mock）
        String code = smsUtil.sendSmsCode(phone, scene);

        // 存入 sms_codes 表
        SmsCode smsCode = new SmsCode();
        smsCode.setId(snowflakeIdUtil.nextId());
        smsCode.setPhone(phone);
        smsCode.setScene(scene);
        smsCode.setCode(code);
        smsCode.setExpireAt(LocalDateTime.now().plusMinutes(SMS_EXPIRE_MINUTES));
        smsCode.setVerifyCount(0);
        smsCode.setRequestIp(clientIp);
        smsCode.setCreatedAt(LocalDateTime.now());
        smsCodeMapper.insert(smsCode);
    }

    // ==================== 注册 ====================

    @Override
    @Transactional
    public LoginVo register(RegisterDto dto) {
        String phone = dto.getPhone();
        String phoneHash = DigestUtil.sha256Hex(phone);

        // 验证验证码
        verifySmsCode(phone, "REGISTER", dto.getSmsCode());

        // 检查手机号是否已注册
        User existUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash));
        if (existUser != null) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_004);
        }

        // 创建用户
        User user = createUser(phone, phoneHash, dto.getPassword(), dto.getNickname(), "SELF");

        // 创建角色绑定
        String role = dto.getRole() != null ? dto.getRole() : "TA";
        UserRole userRole = new UserRole();
        userRole.setId(snowflakeIdUtil.nextId());
        userRole.setUserId(user.getId());
        userRole.setRole(role);
        userRole.setStatus("ACTIVE");
        userRole.setPriority(getRolePriority(role));
        userRole.setCreatedAt(LocalDateTime.now());
        userRole.setUpdatedAt(LocalDateTime.now());
        userRole.setCreatedBy(user.getId());
        userRoleMapper.insert(userRole);

        // 注册后自动登录
        return doLogin(user, role, "PC", "SMS_CODE", null);
    }

    // ==================== 登录 ====================

    @Override
    @Transactional
    public LoginVo login(LoginDto dto, String clientIp) {
        String phone = dto.getPhone();
        String phoneHash = DigestUtil.sha256Hex(phone);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash));
        if (user == null) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_003);
        }

        // 检查账号状态
        checkUserStatus(user);

        // 验证码登录
        if (dto.getSmsCode() != null && !dto.getSmsCode().isEmpty()) {
            verifySmsCode(phone, "LOGIN", dto.getSmsCode());
        } else if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            // 密码登录
            if (user.getPasswordHash() == null) {
                throw new BizException(ErrorCode.AUTH_ACCOUNT_001);
            }
            if (!PASSWORD_ENCODER.matches(dto.getPassword(), user.getPasswordHash())) {
                // TODO: Redis 记录错误次数; MVP 简化
                throw new BizException(ErrorCode.AUTH_ACCOUNT_001, "账号或密码错误，剩余尝试 4 次");
            }
        } else {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_001, "请输入密码或验证码");
        }

        // 更新登录信息
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        userMapper.updateById(user);

        // 解析角色
        String primaryRole = resolvePrimaryRole(user.getId());
        return doLogin(user, primaryRole, dto.getDevice() != null ? dto.getDevice() : "PC", "PASSWORD", null);
    }

    // ==================== 修改密码 ====================

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordDto dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_003);
        }

        // 验证旧密码
        if (user.getPasswordHash() == null || !PASSWORD_ENCODER.matches(dto.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_007);
        }

        // 新旧密码不能相同
        if (PASSWORD_ENCODER.matches(dto.getNewPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_005);
        }

        // 检查最近3次密码历史
        List<PasswordHistory> histories = passwordHistoryMapper.selectList(
                new LambdaQueryWrapper<PasswordHistory>()
                        .eq(PasswordHistory::getUserId, userId)
                        .orderByDesc(PasswordHistory::getCreatedAt)
                        .last("LIMIT 3"));
        for (PasswordHistory h : histories) {
            if (PASSWORD_ENCODER.matches(dto.getNewPassword(), h.getPasswordHash())) {
                throw new BizException(ErrorCode.AUTH_ACCOUNT_006);
            }
        }

        // 保存密码历史
        PasswordHistory history = new PasswordHistory();
        history.setId(snowflakeIdUtil.nextId());
        history.setUserId(userId);
        history.setPasswordHash(user.getPasswordHash());
        history.setCreatedAt(LocalDateTime.now());
        passwordHistoryMapper.insert(history);

        // 清理旧历史（保留最近5条）
        long total = passwordHistoryMapper.selectCount(
                new LambdaQueryWrapper<PasswordHistory>().eq(PasswordHistory::getUserId, userId));
        if (total > 5) {
            List<PasswordHistory> oldList = passwordHistoryMapper.selectList(
                    new LambdaQueryWrapper<PasswordHistory>()
                            .eq(PasswordHistory::getUserId, userId)
                            .orderByAsc(PasswordHistory::getCreatedAt)
                            .last("LIMIT " + (total - 5)));
            for (PasswordHistory old : oldList) {
                passwordHistoryMapper.deleteById(old.getId());
            }
        }

        // 更新密码
        user.setPasswordHash(PASSWORD_ENCODER.encode(dto.getNewPassword()));
        userMapper.updateById(user);

        // 踢掉所有 token
        StpUtil.kickout(userId);

        log.info("User {} changed password, all tokens kicked", userId);
    }

    // ==================== 找回密码 ====================

    @Override
    @Transactional
    public void resetPassword(ResetPasswordDto dto) {
        String phone = dto.getPhone();
        String phoneHash = DigestUtil.sha256Hex(phone);

        // 验证验证码
        verifySmsCode(phone, "RESET_PWD", dto.getSmsCode());

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash));
        if (user == null) {
            // 不暴露账号存在性
            return;
        }

        // 检查新密码不与最近3次相同
        List<PasswordHistory> histories = passwordHistoryMapper.selectList(
                new LambdaQueryWrapper<PasswordHistory>()
                        .eq(PasswordHistory::getUserId, user.getId())
                        .orderByDesc(PasswordHistory::getCreatedAt)
                        .last("LIMIT 3"));
        for (PasswordHistory h : histories) {
            if (PASSWORD_ENCODER.matches(dto.getNewPassword(), h.getPasswordHash())) {
                throw new BizException(ErrorCode.AUTH_ACCOUNT_006);
            }
        }

        // 保存密码历史
        PasswordHistory history = new PasswordHistory();
        history.setId(snowflakeIdUtil.nextId());
        history.setUserId(user.getId());
        history.setPasswordHash(user.getPasswordHash() != null ? user.getPasswordHash() : "");
        history.setCreatedAt(LocalDateTime.now());
        passwordHistoryMapper.insert(history);

        // 更新密码
        user.setPasswordHash(PASSWORD_ENCODER.encode(dto.getNewPassword()));
        userMapper.updateById(user);

        // 踢掉所有 token
        StpUtil.kickout(user.getId());

        log.info("User {} reset password, all tokens kicked", user.getId());
    }

    // ==================== 换绑手机号 ====================

    @Override
    @Transactional
    public void changePhone(Long userId, ChangePhoneDto dto) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_003);
        }

        // 验证密码
        if (user.getPasswordHash() == null || !PASSWORD_ENCODER.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_007);
        }

        // 验证旧手机号验证码
        verifySmsCode(user.getPhone(), "CHANGE_PHONE", dto.getOldSmsCode());

        // 检查新手机号是否已被注册
        String newPhoneHash = DigestUtil.sha256Hex(dto.getNewPhone());
        User existUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, newPhoneHash));
        if (existUser != null && !existUser.getId().equals(userId)) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_004, "新手机号已被注册");
        }

        // 验证新手机号验证码
        verifySmsCode(dto.getNewPhone(), "CHANGE_PHONE", dto.getNewSmsCode());

        // 更新手机号
        user.setPhone(dto.getNewPhone());
        user.setPhoneHash(newPhoneHash);
        userMapper.updateById(user);

        // 踢掉所有 token
        StpUtil.kickout(userId);

        log.info("User {} changed phone, all tokens kicked", userId);
    }

    // ==================== RT 免密验证码登录 ====================

    @Override
    @Transactional
    public LoginVo rtSmsLogin(String phone, String code) {
        // 验证验证码
        verifySmsCode(phone, "RT_LOGIN", code);

        String phoneHash = DigestUtil.sha256Hex(phone);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash));

        boolean isNew = false;
        if (user == null) {
            // 自动注册 RT 账号
            user = createUser(phone, phoneHash, null, "用户" + phone.substring(phone.length() - 4), "RT_CODE");
            isNew = true;

            // 创建 RT 角色
            UserRole userRole = new UserRole();
            userRole.setId(snowflakeIdUtil.nextId());
            userRole.setUserId(user.getId());
            userRole.setRole("RT");
            userRole.setStatus("ACTIVE");
            userRole.setPriority(60);
            userRole.setCreatedAt(LocalDateTime.now());
            userRole.setUpdatedAt(LocalDateTime.now());
            userRole.setCreatedBy(user.getId());
            userRoleMapper.insert(userRole);
        }

        // 登录
        StpUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        return LoginVo.builder()
                .token(tokenInfo.tokenValue)
                .userId(user.getId())
                .primaryRole("RT")
                .roleList(List.of(LoginVo.RoleInfo.builder().role("RT").tenantId(null).wholesalerId(null).priority(60).build()))
                .isNew(isNew)
                .expireAt(LocalDateTime.now().plusSeconds(StpUtil.getTokenSessionTimeout()))
                .build();
    }

    // ==================== 退出登录 ====================

    @Override
    public void logout(Long userId) {
        StpUtil.logout(userId);
        log.info("User {} logged out", userId);
    }

    // ==================== 私有方法 ====================

    /** 验证短信验证码 */
    private void verifySmsCode(String phone, String scene, String code) {
        // MVP mock 验证码
        if (smsUtil.getMockCode().equals(code)) {
            log.info("[SMS VERIFY] Mock code matched for {} scene={}", phone, scene);
            return;
        }

        SmsCode smsCode = smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getScene, scene)
                .eq(SmsCode::getCode, code)
                .isNull(SmsCode::getVerifiedAt)
                .orderByDesc(SmsCode::getCreatedAt)
                .last("LIMIT 1"));
        if (smsCode == null) {
            throw new BizException(ErrorCode.AUTH_SMS_002);
        }
        if (smsCode.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.AUTH_SMS_001);
        }
        if (smsCode.getVerifyCount() >= SMS_MAX_VERIFY) {
            throw new BizException(ErrorCode.AUTH_SMS_003);
        }

        // 更新验证次数
        smsCode.setVerifyCount(smsCode.getVerifyCount() + 1);
        smsCode.setVerifiedAt(LocalDateTime.now());
        smsCodeMapper.updateById(smsCode);
    }

    /** 创建用户 */
    private User createUser(String phone, String phoneHash, String password, String nickname, String source) {
        User user = new User();
        user.setId(snowflakeIdUtil.nextId());
        user.setPhone(phone);
        user.setPhoneHash(phoneHash);
        if (password != null && !password.isEmpty()) {
            user.setPasswordHash(PASSWORD_ENCODER.encode(password));
        }
        user.setNickname(nickname != null ? nickname : "用户" + phone.substring(phone.length() - 4));
        user.setStatus("ACTIVE");
        user.setRegisterSource(source);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    /** 检查用户状态 */
    private void checkUserStatus(User user) {
        if ("FROZEN".equals(user.getStatus())) {
            throw new BizException(ErrorCode.AUTH_BASIC_004);
        }
        if ("CANCELLED".equals(user.getStatus())) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_003, "账号已注销");
        }
    }

    /** 解析用户主角色 */
    private String resolvePrimaryRole(Long userId) {
        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getStatus, "ACTIVE")
                .orderByAsc(UserRole::getPriority));
        if (roles.isEmpty()) {
            return "TA";
        }
        return roles.get(0).getRole();
    }

    /** 执行登录 */
    private LoginVo doLogin(User user, String primaryRole, String device, String loginMethod, Long tenantId) {
        // Sa-Token 登录
        StpUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        // 获取所有角色
        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, user.getId())
                .eq(UserRole::getStatus, "ACTIVE")
                .orderByAsc(UserRole::getPriority));

        List<LoginVo.RoleInfo> roleList = new ArrayList<>();
        for (UserRole r : roles) {
            roleList.add(LoginVo.RoleInfo.builder()
                    .role(r.getRole())
                    .tenantId(r.getTenantId())
                    .wholesalerId(r.getWholesalerId())
                    .priority(r.getPriority())
                    .build());
        }

        // 记录登录会话
        LoginSession session = new LoginSession();
        session.setId(snowflakeIdUtil.nextId());
        session.setUserId(user.getId());
        session.setRole(primaryRole);
        session.setTenantId(tenantId);
        session.setDevice(device);
        session.setLoginMethod(loginMethod);
        session.setCreatedAt(LocalDateTime.now());
        loginSessionMapper.insert(session);

        String router = resolveRouter(primaryRole);

        return LoginVo.builder()
                .token(tokenInfo.tokenValue)
                .userId(user.getId())
                .primaryRole(primaryRole)
                .roleList(roleList)
                .primaryRouter(router)
                .expireAt(LocalDateTime.now().plusSeconds(StpUtil.getTokenSessionTimeout()))
                .build();
    }

    /** 角色到路由的映射 */
    private String resolveRouter(String role) {
        return switch (role) {
            case "OPS" -> "/ops/dashboard";
            case "TA" -> "/tenant/dashboard";
            case "WK" -> "/tenant/wk/dashboard";
            case "ST" -> "/tenant/st/dashboard";
            case "WA" -> "/wholesaler/dashboard";
            case "WE" -> "/wholesaler/we/dashboard";
            case "RT" -> "/rt/home";
            default -> "/";
        };
    }

    /** 角色优先级 */
    private int getRolePriority(String role) {
        return switch (role) {
            case "TA" -> 10;
            case "ST" -> 20;
            case "WK" -> 30;
            case "WA" -> 40;
            case "WE" -> 50;
            case "RT" -> 60;
            default -> 50;
        };
    }
}
