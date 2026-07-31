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
    public List<Long> listActiveWaUserIdsOfWholesaler(Long wholesalerId) {
        // P3 缺陷修复：「归属 WA」通知收件人推导——仅取 role=WA（owner_user_id 在
        // SELF_OPERATED 商户上是 TA 操作人，不可作为 WA 收件人来源）
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getWholesalerId, wholesalerId)
                        .eq(UserRole::getRole, "WA")
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<Long> listActiveWkUserIdsOfTenant(Long tenantId) {
        // P3b T1-BE（13 §1.4）：「库管」通知收件人推导——与 listActiveWaUserIdsOfWholesaler 同构，
        // 以 user_roles 绑定为唯一可信来源，多 WK 账号全发
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getTenantId, tenantId)
                        .eq(UserRole::getRole, "WK")
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
            // DEF-3 兼容：绑定行已在（如重复审批/重放）时，顺手清掉残留的注册占位行，
            // 保证一个用户对同一商户只有一条有效 WA 角色（登录不再出现重复工作空间）。
            removeUnboundPlaceholders(userId, role);
            return existing.getId();
        }

        // DEF-3 根治：WA 注册（未带码路径）会先落一条无 wholesaler 绑定的占位行
        // (role=WA, tenant_id=NULL, wholesaler_id=NULL)。审批通过/OPS 代建绑定商户时
        // 原实现另插一条绑定行，导致同一账号两条 WA 角色 → 登录出现两条同名工作空间。
        // 改为「就地升级」占位行：回填 tenantId/wholesalerId + priority=5（与插入路径语义一致）。
        UserRole placeholder = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .isNull(UserRole::getWholesalerId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (placeholder != null) {
            placeholder.setTenantId(tenantId);
            placeholder.setWholesalerId(wholesalerId);
            placeholder.setPriority(5);
            placeholder.setUpdatedAt(LocalDateTime.now());
            userRoleMapper.updateById(placeholder);
            return placeholder.getId();
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

    /** DEF-3：软删该用户同角色下无 wholesaler 绑定的 ACTIVE 占位行（逻辑删除保留追溯）。 */
    private void removeUnboundPlaceholders(Long userId, String role) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, role)
                .isNull(UserRole::getWholesalerId)
                .eq(UserRole::getStatus, "ACTIVE"));
    }
}
