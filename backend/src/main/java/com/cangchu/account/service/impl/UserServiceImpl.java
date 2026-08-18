package com.cangchu.account.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.UserService;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.util.SnowflakeIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号域用户查询/开通实现（G-S1/G-S2）。
 *
 * <p>逻辑逐一等价搬运自原 tenant 模块直连点（WholesalerServiceImpl.ensureWaUser /
 * TenantServiceImpl.createByOps 建 TA 用户 / pageTenantsForAdmin 批量取显示名）：
 * 字段、幂等语义（先查 phone_hash 命中即返回）、临时密码生成（随机 8 位 + BCrypt cost 12）均不变。
 * 仅依赖本域 UserMapper——无跨域依赖，不参与任何 Bean 循环。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;
    // PII 硬化阶段 0：HMAC 盲索引双写（切点 A6 代建幂等开号；读路径仍走 phone_hash）
    private final PiiCrypto piiCrypto;

    /** BCrypt cost 12（与 AccountServiceImpl / 原 tenant 直连点一致） */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    @Override
    @Transactional
    public EnsuredUser ensureUserByPhone(String phone, String registerSource) {
        String p = phone.trim();
        String phoneHash = DigestUtil.sha256Hex(p);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHash, phoneHash));
        if (user != null) {
            return new EnsuredUser(user.getId(), false);
        }
        user = new User();
        user.setId(snowflakeIdUtil.nextId());
        user.setPhone(p);
        user.setPhoneHash(phoneHash);
        // PII 阶段 0 双写（切点 A6 WA/OPS 代建开号）：legacy 模式不写（回滚口径）
        if (piiCrypto.isDualWrite()) {
            user.setPhoneHmac(piiCrypto.phoneHmac(p));
        }
        // 临时密码只进 BCrypt 落库与（后续）短信通道；不返回、不打日志（F7）
        String tempPwd = RandomUtil.randomString(8);
        user.setPasswordHash(PASSWORD_ENCODER.encode(tempPwd));
        user.setNickname(p.substring(p.length() - 4));
        user.setStatus("ACTIVE");
        user.setRegisterSource(registerSource);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return new EnsuredUser(user.getId(), true);
    }

    @Override
    public String getPhone(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getPhone();
    }

    @Override
    public Map<Long, String> getDisplayNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (User u : userMapper.selectBatchIds(userIds)) {
            String name = u.getRealName() != null && !u.getRealName().isBlank()
                    ? u.getRealName() : u.getNickname();
            result.put(u.getId(), name);
        }
        return result;
    }
}
