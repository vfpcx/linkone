package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.BlacklistAddDto;
import com.cangchu.tenant.entity.Blacklist;
import com.cangchu.tenant.mapper.BlacklistMapper;
import com.cangchu.tenant.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 平台黑名单服务实现（P2 Wave1）。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：所有管理操作 Service 内显式 {@code authService.hasRole(userId,"OPS")} 校验
 *       （现有 requireOpsRole 模式），不信任客户端。</li>
 *   <li>平台级表：不做租户隔离（决策 O-6，未入 TenantLine 白名单），全平台申请/代建共检。</li>
 *   <li>S6 唯一性：uk_blacklist_type_value 兜底 + 应用层先查（ACTIVE 拒绝 / REMOVED 复活），
 *       并发撞唯一约束转语义码 50310。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistServiceImpl implements BlacklistService {

    private static final Set<String> VALID_TYPES = Set.of("PHONE", "LICENSE_NO");

    private final BlacklistMapper blacklistMapper;
    private final AuthService authService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    public List<Blacklist> list(Long opsUserId, String status) {
        requireOpsRole(opsUserId);
        return blacklistMapper.selectList(new LambdaQueryWrapper<Blacklist>()
                .eq(status != null && !status.isBlank(), Blacklist::getStatus, status)
                .orderByDesc(Blacklist::getCreatedAt));
    }

    @Override
    @Transactional
    public Blacklist add(Long opsUserId, BlacklistAddDto dto) {
        requireOpsRole(opsUserId);

        String type = dto.getTargetType().trim().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "黑名单类型仅支持 PHONE/LICENSE_NO");
        }
        String value = dto.getTargetValue().trim();

        // 先查：ACTIVE 重复拒绝；REMOVED 复活（uk 约束在，无法重插同键新行）
        Blacklist existing = blacklistMapper.selectOne(new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getTargetType, type)
                .eq(Blacklist::getTargetValue, value)
                .last("LIMIT 1"));
        if (existing != null) {
            if ("ACTIVE".equals(existing.getStatus())) {
                throw new BizException(ErrorCode.BLACKLIST_ENTRY_EXISTS);
            }
            existing.setStatus("ACTIVE");
            existing.setReason(dto.getReason());
            existing.setOperatorUserId(opsUserId);
            existing.setRemovedAt(null);
            blacklistMapper.updateById(existing);
            log.info("[黑名单] OPS {} 复活条目 {} {}={}", opsUserId, existing.getId(), type, value);
            return existing;
        }

        Blacklist entry = new Blacklist();
        entry.setId(snowflakeIdUtil.nextId());
        entry.setTargetType(type);
        entry.setTargetValue(value);
        entry.setReason(dto.getReason());
        entry.setOperatorUserId(opsUserId);
        entry.setStatus("ACTIVE");
        try {
            blacklistMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 并发撞 uk_blacklist_type_value → 语义码
            throw new BizException(ErrorCode.BLACKLIST_ENTRY_EXISTS);
        }
        log.info("[黑名单] OPS {} 加黑 {}={} 原因={}", opsUserId, type, value, dto.getReason());
        return entry;
    }

    @Override
    @Transactional
    public void remove(Long opsUserId, Long entryId) {
        requireOpsRole(opsUserId);
        Blacklist entry = blacklistMapper.selectById(entryId);
        if (entry == null || !"ACTIVE".equals(entry.getStatus())) {
            throw new BizException(ErrorCode.BLACKLIST_ENTRY_NOT_FOUND);
        }
        entry.setStatus("REMOVED");
        entry.setRemovedAt(LocalDateTime.now());
        blacklistMapper.updateById(entry);
        log.info("[黑名单] OPS {} 解除条目 {} {}={}", opsUserId, entryId,
                entry.getTargetType(), entry.getTargetValue());
    }

    @Override
    public boolean isBlacklisted(String phone, String license) {
        if (phone != null && !phone.isBlank()) {
            long hit = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                    .eq(Blacklist::getTargetType, "PHONE")
                    .eq(Blacklist::getTargetValue, phone.trim())
                    .eq(Blacklist::getStatus, "ACTIVE"));
            if (hit > 0) return true;
        }
        if (license != null && !license.isBlank()) {
            long hit = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                    .eq(Blacklist::getTargetType, "LICENSE_NO")
                    .eq(Blacklist::getTargetValue, license.trim())
                    .eq(Blacklist::getStatus, "ACTIVE"));
            return hit > 0;
        }
        return false;
    }

    /** D-02(c) 角色鉴权：黑名单管理仅限 OPS（user_roles 登录态为唯一可信来源）。 */
    private void requireOpsRole(Long userId) {
        if (!authService.hasRole(userId, "OPS")) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }
}
