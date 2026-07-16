package com.cangchu.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.account.vo.UserRoleView;
import com.cangchu.common.util.SnowflakeIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号域鉴权/角色查询实现（G-S1/G-S2）。
 *
 * <p>收敛此前各业务域对 {@code UserRoleMapper} 的跨域直连；每个方法与原调用点的
 * 查询/写入逐一等价（角色 / 维度 / status=ACTIVE / 字段 / 幂等语义均不变），
 * 仅把访问点从他域挪回 user_roles 的归属域 account。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRoleMapper userRoleMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    public boolean hasRole(Long userId, String role, Long tenantId) {
        return userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getStatus, "ACTIVE")) > 0;
    }

    @Override
    public boolean hasRole(Long userId, String role) {
        return userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getStatus, "ACTIVE")) > 0;
    }

    @Override
    public boolean hasWholesalerRole(Long userId, String role, Long wholesalerId) {
        return userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getStatus, "ACTIVE")) > 0;
    }

    @Override
    public Long findBoundTenantId(Long userId, String role) {
        UserRole r = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        return r == null ? null : r.getTenantId();
    }

    @Override
    public List<UserRoleView> listActiveRoles(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(r -> new UserRoleView(r.getRole(), r.getTenantId(), r.getWholesalerId(), r.getPriority()))
                .toList();
    }

    @Override
    public List<Long> listActiveWholesalerIds(Long userId, String role) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRole, role)
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getWholesalerId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<Long> listActiveUserIdsOfWholesaler(Long wholesalerId) {
        // WDR-S1-02：不加 role 条件——该商户下 WA 与 WE 一并返回（漏踢 WE 是高危漏点）
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getWholesalerId, wholesalerId)
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public boolean hasWholesalerPermission(Long userId, Long wholesalerId, String permission) {
        UserRole we = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "WE")
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        return we != null && com.cangchu.common.util.WePermissions.has(we.getPermissions(), permission);
    }

    @Override
    public List<Long> listActiveWeWholesalerIds(Long userId) {
        return listActiveWholesalerIds(userId, "WE");
    }

    @Override
    public void bindOrCreateTenantRole(Long userId, String role, Long tenantId, Long createdBy) {
        UserRole existing = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .isNull(UserRole::getTenantId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTenantId(tenantId);
            existing.setUpdatedAt(LocalDateTime.now());
            userRoleMapper.updateById(existing);
        } else {
            UserRole newRole = new UserRole();
            newRole.setId(snowflakeIdUtil.nextId());
            newRole.setUserId(userId);
            newRole.setRole(role);
            newRole.setTenantId(tenantId);
            newRole.setStatus("ACTIVE");
            newRole.setPriority(10);
            newRole.setCreatedAt(LocalDateTime.now());
            newRole.setUpdatedAt(LocalDateTime.now());
            newRole.setCreatedBy(createdBy);
            userRoleMapper.insert(newRole);
        }
    }

    @Override
    public void ensureTenantRole(Long userId, String role, Long tenantId, Long createdBy) {
        boolean already = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getTenantId, tenantId)) > 0;
        if (!already) {
            UserRole newRole = new UserRole();
            newRole.setId(snowflakeIdUtil.nextId());
            newRole.setUserId(userId);
            newRole.setRole(role);
            newRole.setTenantId(tenantId);
            newRole.setStatus("ACTIVE");
            newRole.setPriority(10);
            newRole.setCreatedAt(LocalDateTime.now());
            newRole.setUpdatedAt(LocalDateTime.now());
            newRole.setCreatedBy(createdBy);
            userRoleMapper.insert(newRole);
        }
    }

    @Override
    public Long ensureWholesalerRole(Long userId, String role, Long tenantId, Long wholesalerId, Long createdBy) {
        UserRole existing = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        UserRole newRole = new UserRole();
        newRole.setId(snowflakeIdUtil.nextId());
        newRole.setUserId(userId);
        newRole.setRole(role);
        newRole.setTenantId(tenantId);
        newRole.setWholesalerId(wholesalerId);
        newRole.setStatus("ACTIVE");
        newRole.setPriority(5);
        newRole.setCreatedBy(createdBy);
        userRoleMapper.insert(newRole);
        return newRole.getId();
    }
}
