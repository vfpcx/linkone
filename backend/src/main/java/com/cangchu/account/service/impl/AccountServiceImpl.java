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
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private final RedissonClient redissonClient;

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

    /** 合法注册角色白名单（D-07） */
    private static final Set<String> VALID_ROLES = Set.of("OPS", "TA", "WK", "ST", "WA", "WE", "RT");

    /** 登录失败计数 Redis key 前缀（按手机号 hash 维度） */
    private static final String LOGIN_FAIL_KEY_PREFIX = "login:fail:";

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

        // D-07: 角色枚举白名单校验，非法角色返回 40001（不得放行返回 0）
        String role = dto.getRole() != null ? dto.getRole() : "TA";
        if (!VALID_ROLES.contains(role)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "非法的角色类型: " + role);
        }

        // 创建角色绑定
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

        // D-04: 锁定优先于一切（即便用户不存在也按手机号维度统一处理，防枚举 + 防爆破）
        if (isLoginLocked(phoneHash)) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_002);
        }

        // D-10 防账号枚举：手机号未注册不单独暴露，统一走"账号或密码错误"。
        // 注意——验证码登录场景下若手机号不存在则无对应验证码，verifySmsCode 会以 AUTH_SMS_* 失败，
        // 与密码登录路径区分；此处只对密码登录路径做统一防枚举处理。
        if (user == null) {
            if (dto.getSmsCode() != null && !dto.getSmsCode().isEmpty()) {
                // 验证码登录：先校验验证码（不存在的号码必然失败），不暴露账号存在性
                verifySmsCode(phone, "LOGIN", dto.getSmsCode());
            }
            // 走到这里说明验证码"通过"但账号不存在，或为密码登录——统一返回账号或密码错误
            recordLoginFailureAndThrow(phoneHash);
        }

        // 检查账号状态
        checkUserStatus(user);

        // 验证码登录
        if (dto.getSmsCode() != null && !dto.getSmsCode().isEmpty()) {
            verifySmsCode(phone, "LOGIN", dto.getSmsCode());
        } else if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            // 密码登录
            if (user.getPasswordHash() == null
                    || !PASSWORD_ENCODER.matches(dto.getPassword(), user.getPasswordHash())) {
                // D-04 + D-10：累加失败计数、达阈值锁定，返回真实剩余次数，不区分账号是否存在
                recordLoginFailureAndThrow(phoneHash);
            }
        } else {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_001, "请输入密码或验证码");
        }

        // D-04: 登录成功清零失败计数
        clearLoginFailures(phoneHash);

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
                .roles(List.of(LoginVo.RoleInfo.builder().role("RT").tenantId(null).wholesalerId(null).priority(60).build()))
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
        // D-03: mock 万能验证码短路仅 dev 生效；prod 下 isMockEnabled()=false，永不放行 888888。
        if (smsUtil.isMockEnabled() && smsUtil.getMockCode().equals(code)) {
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

    // ==================== 登录失败锁定（D-04，Redisson 原子计数 + TTL）====================

    /** 登录失败计数 key（按手机号 hash，避免明文手机号入 Redis key） */
    private String loginFailKey(String phoneHash) {
        return LOGIN_FAIL_KEY_PREFIX + phoneHash;
    }

    /** 是否已锁定：达到最大失败次数即视为锁定（计数 key 自带 TTL，过期即解锁） */
    private boolean isLoginLocked(String phoneHash) {
        RAtomicLong counter = redissonClient.getAtomicLong(loginFailKey(phoneHash));
        return counter.isExists() && counter.get() >= MAX_LOGIN_FAILURES;
    }

    /**
     * 记录一次登录失败并抛出统一错误（D-04 + D-10）。
     * <p>原子自增失败计数，首次失败时设置 {@link #LOCKOUT_MINUTES} 的 TTL（滑动窗口的锁定期）。
     * 达阈值返回账号锁定错误码 {@code AUTH_ACCOUNT_002}；否则返回"账号或密码错误"并附带真实剩余次数。
     * <p>不区分"账号不存在"与"密码错误"，防止账号枚举。
     */
    private void recordLoginFailureAndThrow(String phoneHash) {
        RAtomicLong counter = redissonClient.getAtomicLong(loginFailKey(phoneHash));
        long failures = counter.incrementAndGet();
        if (failures == 1L) {
            // 首次失败启动锁定窗口 TTL；窗口内不重置，达阈值即锁满 LOCKOUT_MINUTES
            counter.expire(Duration.ofMinutes(LOCKOUT_MINUTES));
        }
        if (failures >= MAX_LOGIN_FAILURES) {
            throw new BizException(ErrorCode.AUTH_ACCOUNT_002);
        }
        long remaining = MAX_LOGIN_FAILURES - failures;
        throw new BizException(ErrorCode.AUTH_ACCOUNT_001, "账号或密码错误，剩余尝试 " + remaining + " 次");
    }

    /** 登录成功后清零失败计数 */
    private void clearLoginFailures(String phoneHash) {
        redissonClient.getAtomicLong(loginFailKey(phoneHash)).delete();
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
                .roles(roleList)
                .primaryRouter(router)
                .expireAt(LocalDateTime.now().plusSeconds(StpUtil.getTokenSessionTimeout()))
                .build();
    }

    /** 角色到路由的映射 */
    private String resolveRouter(String role) {
        return switch (role) {
            case "OPS" -> "/ops/dashboard";
            case "TA" -> "/ta/dashboard";
            case "ST" -> "/st/dashboard";
            case "WK" -> "/ta/dashboard";
            case "WA" -> "/ta/dashboard";
            case "WE" -> "/ta/dashboard";
            // RT(二批/终端) 是 H5/小程序买家，admin 后台不承载 RT 页面（前端无 /rt/* 路由）。
            // 与前端 defaultRouterFor(RT) 对齐，回兜底工作台，避免 admin 内 404。
            case "RT" -> "/ta/dashboard";
            default -> "/ta/dashboard";
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
