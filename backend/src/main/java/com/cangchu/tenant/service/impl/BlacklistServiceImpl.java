package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.pii.PiiShadowReader;
import com.cangchu.common.util.SmsUtil;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
    // PII 硬化阶段 0：HMAC 盲索引双写（切点 B2 加黑/复活；仅 PHONE 行，LICENSE_NO 恒 NULL）
    private final PiiCrypto piiCrypto;
    // PII 阶段 1 Step1：B1 命中检查 / B2 查重的影子双查（命中判定仍以明文列为准）
    private final PiiShadowReader piiShadowReader;

    @Override
    public Map<String, Object> page(Long opsUserId, int page, int size, String status, String keyword) {
        requireOpsRole(opsUserId);
        // DEF-6：全量返回改分页（page>=1，size 1..100），结构对齐 wholesaler-applications 的
        // PageRecords 契约 {records,total,page,size}；keyword 模糊匹配 target_value（手机号/执照号）。
        String kw = keyword != null ? keyword.trim() : null;
        Page<Blacklist> p = blacklistMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<Blacklist>()
                        .eq(status != null && !status.isBlank(), Blacklist::getStatus, status)
                        .like(kw != null && !kw.isEmpty(), Blacklist::getTargetValue, kw)
                        .orderByDesc(Blacklist::getCreatedAt)
                        .orderByDesc(Blacklist::getId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", p.getCurrent());
        result.put("size", p.getSize());
        return result;
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
        piiShadowReader.checkBlacklistEntry("B2-blacklist-add", type, value, existing);
        if (existing != null) {
            if ("ACTIVE".equals(existing.getStatus())) {
                throw new BizException(ErrorCode.BLACKLIST_ENTRY_EXISTS);
            }
            existing.setStatus("ACTIVE");
            existing.setReason(dto.getReason());
            existing.setOperatorUserId(opsUserId);
            existing.setRemovedAt(null);
            // PII 阶段 0 双写（切点 B2 复活）：PHONE 行顺带补齐 hmac（存量行机会性回填）
            if (piiCrypto.isDualWrite() && "PHONE".equals(type)) {
                existing.setTargetValueHmac(piiCrypto.phoneHmac(value));
            }
            blacklistMapper.updateById(existing);
            // PII-W1 B4 收口：PHONE 值脱敏后才可入日志（15 §1.2-B4）
            log.info("[黑名单] OPS {} 复活条目 {} {}={}", opsUserId, existing.getId(), type, maskIfPhone(type, value));
            return existing;
        }

        Blacklist entry = new Blacklist();
        entry.setId(snowflakeIdUtil.nextId());
        entry.setTargetType(type);
        entry.setTargetValue(value);
        // PII 阶段 0 双写（切点 B2 加黑）：仅 PHONE 行写 hmac；legacy 模式不写（回滚口径）
        if (piiCrypto.isDualWrite() && "PHONE".equals(type)) {
            entry.setTargetValueHmac(piiCrypto.phoneHmac(value));
        }
        entry.setReason(dto.getReason());
        entry.setOperatorUserId(opsUserId);
        entry.setStatus("ACTIVE");
        try {
            blacklistMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 并发撞 uk_blacklist_type_value → 语义码
            throw new BizException(ErrorCode.BLACKLIST_ENTRY_EXISTS);
        }
        // PII-W1 B4 收口：PHONE 值脱敏后才可入日志（15 §1.2-B4）
        log.info("[黑名单] OPS {} 加黑 {}={} 原因={}", opsUserId, type, maskIfPhone(type, value), dto.getReason());
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
        // PII-W1 B4 收口：PHONE 值脱敏后才可入日志（15 §1.2-B4）
        log.info("[黑名单] OPS {} 解除条目 {} {}={}", opsUserId, entryId,
                entry.getTargetType(), maskIfPhone(entry.getTargetType(), entry.getTargetValue()));
    }

    /** PII-W1 B4：日志脱敏——PHONE 打码（138****1234），LICENSE_NO 非手机号 PII 原样。 */
    private static String maskIfPhone(String type, String value) {
        return "PHONE".equals(type) ? SmsUtil.maskPhone(value) : value;
    }

    @Override
    public boolean isBlacklisted(String phone, String license) {
        if (phone != null && !phone.isBlank()) {
            long hit = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                    .eq(Blacklist::getTargetType, "PHONE")
                    .eq(Blacklist::getTargetValue, phone.trim())
                    .eq(Blacklist::getStatus, "ACTIVE"));
            piiShadowReader.checkBlacklistHit("B1-blacklist-hit", phone, hit > 0);
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
