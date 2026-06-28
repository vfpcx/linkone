package com.cangchu.tenant.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerUpdateDto;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批发商商户服务实现。
 *
 * <p>安全规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：createSelfOperated/updateProfile 均以 user_roles 登录态推导的 TA 角色为唯一可信来源
 *       （{@link #requireTaRole}，参考 TenantServiceImpl.requireOpsRole 写法），不信任客户端传参。</li>
 *   <li>租户隔离：wholesalers 已纳入 MybatisPlusConfig TenantLine 白名单（兜底注入 tenant_id 条件），
 *       service 内再以 operator 的可信 tenantId 显式 eq 校验归属（双保险，G-2.2）。</li>
 *   <li>S2/S6 唯一性：name 必填（DTO @NotBlank）+ (tenant_id,name) 唯一索引；捕获 DuplicateKeyException
 *       转语义码 WHOLESALER_NAME_DUPLICATED，避免把数据库异常直接暴露。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WholesalerServiceImpl implements WholesalerService {

    private final WholesalerMapper wholesalerMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    @Override
    @Transactional
    public WholesalerVo createSelfOperated(Long tenantId, WholesalerCreateDto dto, Long operatorUserId) {
        // S4：operator 必须是该 tenant 的 TA（user_roles 登录态推导，不信任客户端）
        requireTaRole(tenantId, operatorUserId);

        // S2：name 必填由 DTO @NotBlank 兜底；此处再防御性 trim 校验
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "批发商名称不能为空");
        }

        Wholesaler wholesaler = new Wholesaler();
        wholesaler.setId(snowflakeIdUtil.nextId());
        // tenant_id 由 MetaObjectHandler 自动填充（登录态可信租户）；显式再设一次保证与入参一致
        wholesaler.setTenantId(tenantId);
        wholesaler.setName(dto.getName().trim());
        wholesaler.setOwnerUserId(operatorUserId);
        wholesaler.setLicense(dto.getLicense());
        wholesaler.setIntro(dto.getIntro());
        wholesaler.setStatus("ACTIVE");
        wholesaler.setSource("SELF_OPERATED");
        wholesaler.setCreatedBy(operatorUserId);

        try {
            wholesalerMapper.insert(wholesaler);
        } catch (DuplicateKeyException e) {
            // S6：命中 uk_tenant_id_name 唯一约束 → 转语义码
            throw new BizException(ErrorCode.WHOLESALER_NAME_DUPLICATED);
        }

        // WA 账号开通（最小实现）：传了手机号才开通；按手机号建/绑一个 WA 角色并写 wholesaler_id
        Long waUserId = null;
        if (dto.getWaPhone() != null && !dto.getWaPhone().isBlank()) {
            waUserId = ensureWaAccount(tenantId, wholesaler.getId(), dto.getWaPhone().trim(), operatorUserId);
        }

        log.info("[A1] TA {} 自营创建批发商 {}（tenant {}），WA 角色 userRoleId={}",
                operatorUserId, wholesaler.getId(), tenantId, waUserId);

        return toVo(wholesaler, waUserId);
    }

    @Override
    @Transactional
    public WholesalerVo updateProfile(Long wholesalerId, Long operatorUserId, WholesalerUpdateDto dto) {
        Wholesaler wholesaler = wholesalerMapper.selectById(wholesalerId);
        if (wholesaler == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND);
        }
        // S4 + 租户隔离：operator 必须是该商户所属租户的 TA，且商户归属同租户
        requireTaRole(wholesaler.getTenantId(), operatorUserId);

        if (dto.getIntro() != null) wholesaler.setIntro(dto.getIntro());
        if (dto.getLicense() != null) wholesaler.setLicense(dto.getLicense());
        wholesaler.setUpdatedAt(LocalDateTime.now());
        wholesalerMapper.updateById(wholesaler);

        return toVo(wholesaler, null);
    }

    @Override
    public List<WholesalerVo> listByTenant(Long tenantId) {
        // 显式 eq(tenantId)（与 TenantLine 白名单兜底叠加），只列本租户商户
        List<Wholesaler> list = wholesalerMapper.selectList(new LambdaQueryWrapper<Wholesaler>()
                .eq(Wholesaler::getTenantId, tenantId)
                .orderByDesc(Wholesaler::getCreatedAt));
        return list.stream().map(w -> toVo(w, null)).toList();
    }

    // ==================== 私有方法 ====================

    /**
     * S4 角色鉴权：校验用户在指定租户下具备有效 TA 角色，否则抛越权。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参。
     * （写法参考 {@code TenantServiceImpl.requireOpsRole}，增加 tenant 维度。）
     */
    private void requireTaRole(Long tenantId, Long userId) {
        long taCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getStatus, "ACTIVE"));
        if (taCount == 0) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * WA 账号开通（phase-1 最小实现）：
     * 按手机号查/建 User，再确保存在一条 (role=WA, tenantId, wholesalerId) 的 user_roles 绑定。
     * 注意：本切片只做角色绑定，不发临时密码短信、不做完整入驻流程——见交付说明，由后续切片完善。
     *
     * @return 该 WA 绑定的 user_roles.id
     */
    private Long ensureWaAccount(Long tenantId, Long wholesalerId, String waPhone, Long operatorUserId) {
        String phoneHash = DigestUtil.sha256Hex(waPhone);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhoneHash, phoneHash));
        if (user == null) {
            user = new User();
            user.setId(snowflakeIdUtil.nextId());
            user.setPhone(waPhone);
            user.setPhoneHash(phoneHash);
            String tempPwd = RandomUtil.randomString(8);
            user.setPasswordHash(PASSWORD_ENCODER.encode(tempPwd));
            user.setNickname(waPhone.substring(waPhone.length() - 4));
            user.setStatus("ACTIVE");
            user.setRegisterSource("WA_PROVISION");
            userMapper.insert(user);
            // TODO（后续切片）：发送短信临时密码 + 首登强制改密；当前仅日志占位
            log.info("[A1][WA开通] 新建 WA 用户 phone={} 临时密码={}", waPhone, tempPwd);
        }

        UserRole existing = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, user.getId())
                .eq(UserRole::getRole, "WA")
                .eq(UserRole::getWholesalerId, wholesalerId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }

        UserRole waRole = new UserRole();
        waRole.setId(snowflakeIdUtil.nextId());
        waRole.setUserId(user.getId());
        waRole.setRole("WA");
        waRole.setTenantId(tenantId);
        waRole.setWholesalerId(wholesalerId);
        waRole.setStatus("ACTIVE");
        waRole.setPriority(5);
        waRole.setCreatedBy(operatorUserId);
        userRoleMapper.insert(waRole);
        return waRole.getId();
    }

    private WholesalerVo toVo(Wholesaler w, Long waUserId) {
        return WholesalerVo.builder()
                .id(w.getId())
                .tenantId(w.getTenantId())
                .name(w.getName())
                .ownerUserId(w.getOwnerUserId())
                .license(w.getLicense())
                .intro(w.getIntro())
                .status(w.getStatus())
                .source(w.getSource())
                .waUserId(waUserId)
                .createdAt(w.getCreatedAt())
                .build();
    }
}
