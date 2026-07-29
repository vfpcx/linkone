package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.account.service.UserService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.WholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerUpdateDto;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
    private final WholesalerApplicationMapper wholesalerApplicationMapper;
    private final AuthService authService;
    // users 表归 account 域，查/建经 UserService（G-S1/G-S2，2026-07-23 还债）
    private final UserService userService;
    private final SnowflakeIdUtil snowflakeIdUtil;
    // BLK-S1-05：黑名单拦截 TA 自营路径（平台级检查，防绕过）
    private final com.cangchu.tenant.service.BlacklistService blacklistService;

    @Override
    @Transactional
    public WholesalerVo createSelfOperated(Long tenantId, WholesalerCreateDto dto, Long operatorUserId) {
        // S4：operator 必须是该 tenant 的 TA（user_roles 登录态推导，不信任客户端）
        requireTaRole(tenantId, operatorUserId);

        // S2：name 必填由 DTO @NotBlank 兜底；此处再防御性 trim 校验
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "批发商名称不能为空");
        }

        // BLK-S1-05（R-04 防绕过）：黑名单拦全部三条入驻路径——自助申请/OPS 代建/TA 自营。
        // TA 自营同检 waPhone + license，避免自营路径成为黑名单绕过后门。
        if (blacklistService.isBlacklisted(dto.getWaPhone(), dto.getLicense())) {
            throw new BizException(ErrorCode.BLACKLIST_HIT);
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
        WaAccount waAccount = null;
        if (dto.getWaPhone() != null && !dto.getWaPhone().isBlank()) {
            waAccount = provisionWaAccount(tenantId, wholesaler.getId(), dto.getWaPhone().trim(), operatorUserId);
        }

        // P2 入驻 Wave1：统一入驻链路留痕——补一条 APPROVED 申请单（source=TA_SELF_OPERATED），
        // 行为兼容不变（主体仍 ACTIVE/SELF_OPERATED，直接生效无审批），对齐 D15。
        WholesalerApplication trace = new WholesalerApplication();
        trace.setId(snowflakeIdUtil.nextId());
        trace.setTenantId(tenantId);
        trace.setApplicantUserId(waAccount != null ? waAccount.userId() : operatorUserId);
        trace.setName(wholesaler.getName());
        trace.setContactPhone(dto.getWaPhone());
        trace.setLicense(dto.getLicense());
        trace.setStatus("APPROVED");
        trace.setSource("TA_SELF_OPERATED");
        trace.setAuditUserId(operatorUserId);
        trace.setAuditedAt(LocalDateTime.now());
        trace.setWholesalerId(wholesaler.getId());
        wholesalerApplicationMapper.insert(trace);

        Long waRoleId = waAccount != null ? waAccount.userRoleId() : null;
        log.info("[A1] TA {} 自营创建批发商 {}（tenant {}），WA 角色 userRoleId={}，留痕申请单 {}",
                operatorUserId, wholesaler.getId(), tenantId, waRoleId, trace.getId());

        return toVo(wholesaler, waRoleId);
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

    @Override
    public WholesalerVo getById(Long wholesalerId) {
        // 只读跨域出口（G-S1/G-S2）：内部同经 wholesalerMapper.selectById，隔离行为与原直连一致
        // （受 TenantLine 兜底），跨租户不可见返回 null；存在性判断留给调用方。
        Wholesaler wholesaler = wholesalerMapper.selectById(wholesalerId);
        return wholesaler == null ? null : toVo(wholesaler, null);
    }

    // ==================== 私有方法 ====================

    /**
     * S4 角色鉴权：校验用户在指定租户下具备有效 TA 角色，否则抛越权。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参。
     * （写法参考 {@code TenantServiceImpl.requireOpsRole}，增加 tenant 维度。）
     */
    private void requireTaRole(Long tenantId, Long userId) {
        // user_roles 归 account 域，走 AuthService（语义等价：role=TA & tenant_id & ACTIVE）
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * 幂等查/建 WA 用户（按手机号，不绑角色）。P2 Wave1 公开为复用出口：
     * OPS 代建需先取得负责人用户 id 才能插 wholesalers（owner_user_id NOT NULL）。
     */
    @Override
    @Transactional
    public Long ensureWaUser(String waPhone) {
        String phone = waPhone.trim();
        // users 表归 account 域：幂等查/建（含临时密码生成）经 UserService（G-S1/G-S2，2026-07-23 还债），
        // 语义与原直连逐一等价：命中 phone_hash 即返回；未命中新建 ACTIVE / WA_PROVISION。
        UserService.EnsuredUser ensured = userService.ensureUserByPhone(phone, "WA_PROVISION");
        if (ensured.isNew()) {
            // TODO（后续切片）：发送短信临时密码 + 首登强制改密。
            // F7：日志严禁明文密码与完整手机号（临时密码只存在于短信通道，日志仅留脱敏号码）
            log.info("[A1][WA开通] 新建 WA 用户 phone={}（临时密码已生成，待短信通道下发）",
                    com.cangchu.common.util.SmsUtil.maskPhone(phone));
        }
        return ensured.userId();
    }

    /**
     * WA 账号开通（原私有 ensureWaAccount，P2 Wave1 公开复用出口）：
     * 按手机号查/建 User，再确保存在一条 (role=WA, tenantId, wholesalerId, ACTIVE) 的 user_roles 绑定。
     * 注意：本切片只做角色绑定，不发临时密码短信、不做完整入驻流程。
     */
    @Override
    @Transactional
    public WaAccount provisionWaAccount(Long tenantId, Long wholesalerId, String waPhone, Long operatorUserId) {
        Long userId = ensureWaUser(waPhone);
        // WA 角色绑定（user_roles 归 account 域）走 AuthService；幂等语义等价：
        // 已有 (WA, wholesaler_id, ACTIVE) → 返回其 id；否则新建 (priority=5) 返回新 id。
        Long roleId = authService.ensureWholesalerRole(userId, "WA", tenantId, wholesalerId, operatorUserId);
        return new WaAccount(userId, roleId);
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
