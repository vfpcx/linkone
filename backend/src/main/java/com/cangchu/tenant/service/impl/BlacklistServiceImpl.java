package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.pii.PiiHmacQueries;
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
import java.util.List;
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

    @Override
    public Map<String, Object> page(Long opsUserId, int page, int size, String status, String keyword) {
        requireOpsRole(opsUserId);
        // DEF-6：全量返回改分页（page>=1，size 1..100），结构对齐 wholesaler-applications 的
        // PageRecords 契约 {records,total,page,size}；keyword 模糊匹配 target_value（手机号/执照号）。
        String kw = keyword != null ? keyword.trim() : null;
        // PII-W7（15 §4 阶段2-2）：LIKE 模糊查号下线——完整 11 位 → hmac/明文精确查；
        // 其他（last4 尾号 / 执照号）→ RIGHT(target_value,4)=kw 精确尾号，执照号行保留 LIKE（非 PII）
        LambdaQueryWrapper<Blacklist> qw = new LambdaQueryWrapper<Blacklist>()
                .eq(status != null && !status.isBlank(), Blacklist::getStatus, status);
        if (kw != null && !kw.isEmpty()) {
            if (kw.matches("\\d{11}")) {
                // W8（16 §3.5）：V34 后 PHONE 行 target_value 已是摘要，11 位完整号只按 hmac 精确查
                String hmacKw = piiCrypto.phoneHmac(kw);
                qw.and(w -> w.eq(Blacklist::getTargetValueHmac, hmacKw));
            } else {
                qw.and(w -> w.apply("RIGHT(target_value, 4) = {0}", kw)
                        .or(x -> x.like(Blacklist::getTargetValue, kw)));
            }
        }
        qw.orderByDesc(Blacklist::getCreatedAt).orderByDesc(Blacklist::getId);
        Page<Blacklist> p = blacklistMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), qw);

        // W8（16 §1.5/§3.5）：V34 后 PHONE 行 target_value 已是摘要 PHONE_****{last4}，原样返回；
        // LICENSE_NO 非手机号 PII 原样
        List<Blacklist> masked = p.getRecords();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", masked);
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
        // W8 收口（16 §2.5）：V34 后 PHONE 行 target_value 已是摘要、仅 hmac 盲索引可精确命中；
        // LICENSE_NO 行 hmac 恒 NULL，按 target_value 原文查（无 shadow/明文回滚分支）
        Blacklist existing;
        if ("PHONE".equals(type)) {
            existing = blacklistMapper.selectOne(PiiHmacQueries.blacklistEntry(piiCrypto.phoneHmac(value)));
        } else {
            existing = blacklistMapper.selectOne(new LambdaQueryWrapper<Blacklist>()
                    .eq(Blacklist::getTargetType, type)
                    .eq(Blacklist::getTargetValue, value)
                    .last("LIMIT 1"));
        }
        if (existing != null) {
            if ("ACTIVE".equals(existing.getStatus())) {
                throw new BizException(ErrorCode.BLACKLIST_ENTRY_EXISTS);
            }
            existing.setStatus("ACTIVE");
            existing.setReason(dto.getReason());
            existing.setOperatorUserId(opsUserId);
            existing.setRemovedAt(null);
            // W8（16 §3.5）：B2 复活 PHONE 行无条件刷新 hmac + cipher + 摘要（收口后无开关门控）
            if ("PHONE".equals(type)) {
                existing.setTargetValueHmac(piiCrypto.phoneHmac(value));
                existing.setTargetValueCipher(piiCrypto.encrypt(value));
                existing.setTargetValue(summarizePhoneValue(value));
            }
            blacklistMapper.updateById(existing);
            log.info("[黑名单] OPS {} 复活条目 {} {}={}", opsUserId, existing.getId(), type, value);
            return existing;
        }

        Blacklist entry = new Blacklist();
        entry.setId(snowflakeIdUtil.nextId());
        entry.setTargetType(type);
        // W8（16 §3.5）：PHONE 行 target_value 按 §1.5 摘要格式 + 无条件写 hmac/cipher；
        // LICENSE_NO 行原样保留、hmac/cipher 恒 NULL（15 §2-1）
        if ("PHONE".equals(type)) {
            entry.setTargetValue(summarizePhoneValue(value));
            entry.setTargetValueHmac(piiCrypto.phoneHmac(value));
            entry.setTargetValueCipher(piiCrypto.encrypt(value));
        } else {
            entry.setTargetValue(value);
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

    /**
     * W8（16 §1.5）：PHONE 行 target_value 摘要（{@code PHONE_****{last4}}）；基础摘要已被 ACTIVE
     * 行占用时追加 hmac 尾 4 消歧（{@code PHONE_****{last4}:{hmac4}}），与 V34 迁移 8.1/8.2 口径一致。
     */
    private String summarizePhoneValue(String phone) {
        String base = "PHONE_****" + phone.substring(phone.length() - 4);
        boolean baseTaken = blacklistMapper.selectCount(new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getTargetType, "PHONE")
                .eq(Blacklist::getTargetValue, base)
                .eq(Blacklist::getStatus, "ACTIVE")) > 0;
        if (!baseTaken) {
            return base;
        }
        String hmac = piiCrypto.phoneHmac(phone);
        return base + ":" + hmac.substring(hmac.length() - 4);
    }

    @Override
    public boolean isBlacklisted(String phone, String license) {
        if (phone != null && !phone.isBlank()) {
            // W8 收口（16 §2.5）：V34 后唯一读路径——hmac 盲索引数行（无 shadow/明文回滚分支）
            long hit = blacklistMapper.selectCount(
                    PiiHmacQueries.blacklistActiveHit(piiCrypto.phoneHmac(phone.trim())));
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
