package com.cangchu.tenant.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SmsUtil;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.*;
import com.cangchu.tenant.entity.*;
import com.cangchu.tenant.mapper.*;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.CapacityVo;
import com.cangchu.tenant.vo.EmployeeInviteVo;
import com.cangchu.tenant.vo.TenantDetailVo;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;
    private final StoreMapper storeMapper;
    private final TenantSettingsMapper tenantSettingsMapper;
    private final InviteCodeMapper inviteCodeMapper;
    private final CapacityPublishMapper capacityPublishMapper;
    private final TenantApplicationMapper tenantApplicationMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final SnowflakeIdUtil snowflakeIdUtil;
    private final SmsUtil smsUtil;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    @Override
    @Transactional
    public Map<String, Object> apply(Long userId, TenantApplyDto dto) {
        // D-06 唯一性（应用层）：同一申请人不可重复提交同名仓库（避免每次新建重复租户）。
        // DB 侧 stores 已有 UNIQUE KEY uk_tenant_id_name(tenant_id, name) 兜底，但 apply 每次新建
        // tenant 故 (tenant_id,name) 不会撞，必须在应用层按 applicant + name 拦截重复申请。
        long dup = tenantApplicationMapper.selectCount(new LambdaQueryWrapper<TenantApplication>()
                .eq(TenantApplication::getApplicantUserId, userId)
                .eq(TenantApplication::getName, dto.getName())
                .ne(TenantApplication::getStatus, "REJECTED"));
        if (dup > 0) {
            throw new BizException(ErrorCode.TENANT_ALREADY_EXISTS, "您已提交过同名仓库申请，请勿重复注册");
        }

        // D-16 对齐：若注册阶段已为该 TA 建了 PENDING 租户壳（user_roles 已绑定 tenantId），
        // 则 apply 视为「完善资料」——更新既有 PENDING 租户 + store，绝不再新建第二个租户（避免重复建仓）。
        UserRole boundTa = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (boundTa != null && boundTa.getTenantId() != null) {
            Tenant existing = tenantMapper.selectById(boundTa.getTenantId());
            if (existing != null && "PENDING".equals(existing.getStatus())) {
                return completePendingTenant(userId, existing, dto);
            }
        }

        // 创建入驻申请
        TenantApplication application = new TenantApplication();
        application.setId(snowflakeIdUtil.nextId());
        application.setApplicantUserId(userId);
        application.setName(dto.getName());
        application.setLegalName(dto.getLegalName());
        application.setLicenseNo(dto.getLicenseNo());
        application.setLicenseUrl(dto.getLicenseUrl());
        application.setContactPhone(dto.getContactPhone());
        application.setAddressText(dto.getAddressText());
        application.setLng(dto.getLng());
        application.setLat(dto.getLat());
        application.setStatus("PENDING");
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        tenantApplicationMapper.insert(application);

        // 自动创建租户（待审核状态）
        Tenant tenant = createTenant(dto.getName(), dto.getLegalName(), dto.getLicenseNo(),
                dto.getLicenseUrl(), userId, dto.getContactPhone(), false);
        application.setTenantId(tenant.getId());
        application.setStatus("APPROVED");
        tenantApplicationMapper.updateById(application);

        // 更新租户为审核中
        tenant.setStatus("PENDING");
        tenantMapper.updateById(tenant);

        // 绑定 TA 角色到新建租户
        // 注册时已经插入 UserRole(role=TA, tenantId=null)，apply 时应该绑定 tenantId 而不是再插一行
        UserRole existing = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .isNull(UserRole::getTenantId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTenantId(tenant.getId());
            existing.setUpdatedAt(LocalDateTime.now());
            userRoleMapper.updateById(existing);
        } else {
            UserRole taRole = new UserRole();
            taRole.setId(snowflakeIdUtil.nextId());
            taRole.setUserId(userId);
            taRole.setRole("TA");
            taRole.setTenantId(tenant.getId());
            taRole.setStatus("ACTIVE");
            taRole.setPriority(10);
            taRole.setCreatedAt(LocalDateTime.now());
            taRole.setUpdatedAt(LocalDateTime.now());
            taRole.setCreatedBy(userId);
            userRoleMapper.insert(taRole);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId().toString());
        result.put("tenantId", tenant.getId().toString());
        result.put("status", "PENDING");
        return result;
    }

    @Override
    @Transactional
    public void audit(Long tenantId, Long opsUserId, TenantAuditDto dto) {
        // D-02(c) 角色鉴权：审核入驻仅限 OPS（以 user_roles 登录态为可信来源，不依赖客户端）
        requireOpsRole(opsUserId);

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        // D-05 状态机：仅 PENDING 可审核；已通过(ACTIVE)/已驳回(REJECTED)再操作一律拒绝
        if (!"PENDING".equals(tenant.getStatus())) {
            throw new BizException(ErrorCode.STATE_TENANT_001,
                    "租户当前状态为 " + tenant.getStatus() + "，不可重复审核");
        }

        // action 白名单
        if (!"APPROVED".equals(dto.getAction()) && !"REJECTED".equals(dto.getAction())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审核结果仅支持 APPROVED/REJECTED");
        }

        if ("APPROVED".equals(dto.getAction())) {
            tenant.setStatus("ACTIVE");
            // 通过后激活关联的 Store
            List<Store> stores = storeMapper.selectList(
                    new LambdaQueryWrapper<Store>().eq(Store::getTenantId, tenantId));
            for (Store store : stores) {
                store.setStatus("ACTIVE");
                storeMapper.updateById(store);
            }
        } else {
            tenant.setStatus("REJECTED");
        }
        tenant.setAuditUserId(opsUserId);
        tenant.setAuditedAt(LocalDateTime.now());
        tenant.setAuditRemark(dto.getRemark());
        tenantMapper.updateById(tenant);

        log.info("OPS {} {} tenant {}", opsUserId, dto.getAction(), tenantId);
    }

    @Override
    @Transactional
    public Map<String, Object> createByOps(Long opsUserId, TenantCreateDto dto) {
        // D-02(c) 角色鉴权：代建租户仅限 OPS
        requireOpsRole(opsUserId);

        // 检查手机号是否已注册用户
        String phoneHash = DigestUtil.sha256Hex(dto.getContactPhone());
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhoneHash, phoneHash));

        boolean isNewUser = false;
        if (user == null) {
            // 创建 TA 用户
            user = new User();
            user.setId(snowflakeIdUtil.nextId());
            user.setPhone(dto.getContactPhone());
            user.setPhoneHash(phoneHash);
            // 生成临时密码
            String tempPwd = RandomUtil.randomString(8);
            user.setPasswordHash(PASSWORD_ENCODER.encode(tempPwd));
            user.setNickname(dto.getContactPhone().substring(dto.getContactPhone().length() - 4));
            user.setStatus("ACTIVE");
            user.setRegisterSource("OPS_PROXY");
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(user);
            isNewUser = true;

            // TODO: 发送短信临时密码给 TA
            log.info("[OPS PROXY] 代建租户 TA 手机号={}, 临时密码={}", dto.getContactPhone(), tempPwd);
        }

        // 创建租户（直接通过）
        Tenant tenant = createTenant(dto.getName(), dto.getLegalName(), dto.getLicenseNo(),
                dto.getLicenseUrl(), user.getId(), dto.getContactPhone(), true);

        // 绑定 TA 角色
        boolean alreadyTa = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, user.getId())
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getTenantId, tenant.getId())) > 0;
        if (!alreadyTa) {
            UserRole taRole = new UserRole();
            taRole.setId(snowflakeIdUtil.nextId());
            taRole.setUserId(user.getId());
            taRole.setRole("TA");
            taRole.setTenantId(tenant.getId());
            taRole.setStatus("ACTIVE");
            taRole.setPriority(10);
            taRole.setCreatedAt(LocalDateTime.now());
            taRole.setUpdatedAt(LocalDateTime.now());
            taRole.setCreatedBy(opsUserId);
            userRoleMapper.insert(taRole);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.getId().toString());
        result.put("taUserId", user.getId().toString());
        result.put("isNewUser", isNewUser);
        result.put("status", "ACTIVE");
        return result;
    }

    @Override
    public TenantDetailVo getMyStore(Long userId) {
        // 查找用户的 TA 角色
        UserRole taRole = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (taRole == null || taRole.getTenantId() == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }

        return buildTenantDetail(taRole.getTenantId());
    }

    @Override
    @Transactional
    public void updateMyStore(Long userId, StoreSettingsDto dto) {
        UserRole taRole = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (taRole == null || taRole.getTenantId() == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        Long tenantId = taRole.getTenantId();

        // D-05 状态机：仅已审核通过(ACTIVE)的租户才可修改店铺设置；PENDING/REJECTED/冻结一律拒绝
        Tenant tenantForState = tenantMapper.selectById(tenantId);
        if (tenantForState == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        if (!"ACTIVE".equals(tenantForState.getStatus())) {
            throw new BizException(ErrorCode.STATE_TENANT_001,
                    "租户尚未审核通过（当前状态 " + tenantForState.getStatus() + "），暂不可修改店铺设置");
        }

        // 更新 Store
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().eq(Store::getTenantId, tenantId));
        if (store == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        if (dto.getName() != null) store.setName(dto.getName());
        if (dto.getLng() != null) store.setLng(dto.getLng());
        if (dto.getLat() != null) store.setLat(dto.getLat());
        if (dto.getTotalCapacityQty() != null) store.setTotalCapacityQty(dto.getTotalCapacityQty());
        if (dto.getTotalCapacityPallet() != null) store.setTotalCapacityPallet(dto.getTotalCapacityPallet());
        if (dto.getCapacityVisibility() != null) store.setCapacityVisibility(dto.getCapacityVisibility());
        if (dto.getCapacityPrecision() != null) store.setCapacityPrecision(dto.getCapacityPrecision());
        if (dto.getBusinessHours() != null) store.setBusinessHours(dto.getBusinessHours());
        if (dto.getIntro() != null) store.setIntro(dto.getIntro());
        storeMapper.updateById(store);

        // 更新 TenantSettings (5 开关)
        if (hasAnySwitch(dto)) {
            TenantSettings settings = tenantSettingsMapper.selectOne(
                    new LambdaQueryWrapper<TenantSettings>().eq(TenantSettings::getTenantId, tenantId));
            if (settings == null) {
                settings = new TenantSettings();
                settings.setId(snowflakeIdUtil.nextId());
                settings.setTenantId(tenantId);
                settings.setCreatedAt(LocalDateTime.now());
                settings.setUpdatedAt(LocalDateTime.now());
                settings.setUpdatedBy(userId);
                applySettingsDto(settings, dto, true);
                tenantSettingsMapper.insert(settings);
            } else {
                applySettingsDto(settings, dto, false);
                settings.setUpdatedBy(userId);
                tenantSettingsMapper.updateById(settings);
            }
        }

        // 更新 Tenant name
        if (dto.getName() != null) {
            Tenant tenant = tenantMapper.selectById(tenantId);
            if (tenant != null) {
                tenant.setName(dto.getName());
                tenantMapper.updateById(tenant);
            }
        }
    }

    @Override
    public Map<String, String> getStoreQr(Long userId) {
        UserRole taRole = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (taRole == null || taRole.getTenantId() == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        Tenant tenant = tenantMapper.selectById(taRole.getTenantId());
        // TODO: 实际对接二维码生成 SDK（如 ZXing）；MVP 返回签名 URL
        String qrUrl = "https://cangchu.com/store/" + tenant.getTenantSimpleCode();

        Map<String, String> result = new LinkedHashMap<>();
        result.put("tenantId", tenant.getId().toString());
        result.put("tenantSimpleCode", tenant.getTenantSimpleCode());
        result.put("qrUrl", qrUrl);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> generateInviteCode(Long userId, String targetRole, Integer maxUses, Integer expireDays) {
        UserRole taRole = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (taRole == null || taRole.getTenantId() == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        String code = RandomUtil.randomString(8).toUpperCase();

        InviteCode inviteCode = new InviteCode();
        inviteCode.setId(snowflakeIdUtil.nextId());
        inviteCode.setTenantId(taRole.getTenantId());
        inviteCode.setCode(code);
        inviteCode.setTargetRole(targetRole != null ? targetRole : "WK");
        inviteCode.setMaxUses(maxUses != null ? maxUses : 1);
        inviteCode.setUsedCount(0);
        inviteCode.setExpireAt(expireDays != null ? LocalDateTime.now().plusDays(expireDays) : null);
        inviteCode.setStatus("ACTIVE");
        inviteCode.setCreatedAt(LocalDateTime.now());
        inviteCode.setCreatedBy(userId);
        inviteCodeMapper.insert(inviteCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inviteCodeId", inviteCode.getId().toString());
        result.put("code", code);
        result.put("expireAt", inviteCode.getExpireAt());
        return result;
    }

    // ==================== 员工注册码（phase-1：解锁 WK 入库） ====================

    /** TA 生成员工内部角色(WK/ST)注册码。tenant_id 由登录态推导，role 白名单二次校验。 */
    @Override
    @Transactional
    public EmployeeInviteVo createEmployeeInvite(Long taUserId, EmployeeInviteCreateDto dto) {
        Long tenantId = requireTaRole(taUserId);

        // role 仅 WK/ST（仓库内部员工角色），其余一律拒绝
        String role = dto.getRole() != null ? dto.getRole().trim().toUpperCase() : "";
        if (!"WK".equals(role) && !"ST".equals(role)) {
            throw new BizException(ErrorCode.INVITE_ROLE_NOT_ALLOWED);
        }

        int maxUses = dto.getMaxUses() != null ? dto.getMaxUses() : 1;
        int expiresInDays = dto.getExpiresInDays() != null ? dto.getExpiresInDays() : 7;

        // 随机码：12 位大写字母数字，碰撞概率极低；uk_code 唯一索引兜底
        String code = RandomUtil.randomString("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", 12);

        InviteCode invite = new InviteCode();
        invite.setId(snowflakeIdUtil.nextId());
        invite.setTenantId(tenantId);
        invite.setCode(code);
        invite.setTargetRole(role);
        invite.setMaxUses(maxUses);
        invite.setUsedCount(0);
        invite.setExpireAt(LocalDateTime.now().plusDays(expiresInDays));
        invite.setStatus("ACTIVE");
        invite.setCreatedAt(LocalDateTime.now());
        invite.setCreatedBy(taUserId);
        inviteCodeMapper.insert(invite);

        log.info("[员工注册码] TA {} 为租户 {} 生成 {} 码 maxUses={} expiresInDays={}",
                taUserId, tenantId, role, maxUses, expiresInDays);
        return toEmployeeInviteVo(invite);
    }

    @Override
    public List<EmployeeInviteVo> listEmployeeInvites(Long taUserId) {
        Long tenantId = requireTaRole(taUserId);
        List<InviteCode> list = inviteCodeMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getTenantId, tenantId)
                .orderByDesc(InviteCode::getCreatedAt));
        return list.stream().map(this::toEmployeeInviteVo).toList();
    }

    @Override
    @Transactional
    public void revokeEmployeeInvite(Long taUserId, Long inviteId) {
        Long tenantId = requireTaRole(taUserId);
        InviteCode invite = inviteCodeMapper.selectById(inviteId);
        // 跨租户/不存在统一按不存在处理（不泄漏他租户码的存在性）
        if (invite == null || !tenantId.equals(invite.getTenantId())) {
            throw new BizException(ErrorCode.INVITE_CODE_NOT_FOUND);
        }
        invite.setStatus("REVOKED");
        inviteCodeMapper.updateById(invite);
        log.info("[员工注册码] TA {} 作废注册码 {}（租户 {}）", taUserId, inviteId, tenantId);
    }

    /**
     * 凭码注册消费：校验 + used_count+1（CAS 防并发超发）。
     * 校验顺序：存在 → 角色 WK/ST → 未作废 → 未过期 → 未超次。失败抛对应错误码。
     */
    @Override
    @Transactional
    public InviteCode consumeInviteForRegister(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.AUTH_INVITE_001);
        }
        InviteCode invite = inviteCodeMapper.selectOne(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getCode, code.trim()));
        if (invite == null) {
            throw new BizException(ErrorCode.AUTH_INVITE_001);
        }
        // 仅 WK/ST 员工码可用于员工注册（防止历史 WA 入驻码等被误用）
        String role = invite.getTargetRole();
        if (!"WK".equals(role) && !"ST".equals(role)) {
            throw new BizException(ErrorCode.AUTH_INVITE_004);
        }
        if ("REVOKED".equals(invite.getStatus())) {
            throw new BizException(ErrorCode.INVITE_CODE_REVOKED);
        }
        if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.AUTH_INVITE_002);
        }
        int used = invite.getUsedCount() != null ? invite.getUsedCount() : 0;
        int max = invite.getMaxUses() != null ? invite.getMaxUses() : 1;
        if (used >= max || "EXHAUSTED".equals(invite.getStatus())) {
            throw new BizException(ErrorCode.AUTH_INVITE_003);
        }

        // CAS 自增 used_count：仅当当前 used_count 仍等于读到的值才更新，避免并发重复消费同一名额
        InviteCode update = new InviteCode();
        update.setUsedCount(used + 1);
        if (used + 1 >= max) {
            update.setStatus("EXHAUSTED");
        }
        int affected = inviteCodeMapper.update(update, new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getId, invite.getId())
                .eq(InviteCode::getUsedCount, used));
        if (affected == 0) {
            // 并发抢占了最后名额
            throw new BizException(ErrorCode.AUTH_INVITE_003);
        }
        invite.setUsedCount(used + 1);
        return invite;
    }

    private EmployeeInviteVo toEmployeeInviteVo(InviteCode invite) {
        int used = invite.getUsedCount() != null ? invite.getUsedCount() : 0;
        int max = invite.getMaxUses() != null ? invite.getMaxUses() : 1;
        return EmployeeInviteVo.builder()
                .id(invite.getId())
                .tenantId(invite.getTenantId())
                .code(invite.getCode())
                .role(invite.getTargetRole())
                .maxUses(max)
                .usedCount(used)
                .remaining(Math.max(0, max - used))
                .expireAt(invite.getExpireAt())
                .status(invite.getStatus())
                .createdAt(invite.getCreatedAt())
                .build();
    }

    @Override
    public CapacityVo getCapacity(Long tenantId) {
        // 从 capacity_publish 表查最新快照
        CapacityPublish snapshot = capacityPublishMapper.selectOne(
                new LambdaQueryWrapper<CapacityPublish>()
                        .eq(CapacityPublish::getTenantId, tenantId)
                        .orderByDesc(CapacityPublish::getSnapshotAt)
                        .last("LIMIT 1"));

        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().eq(Store::getTenantId, tenantId));

        if (snapshot == null) {
            // 无快照，返回基于 store 的默认值
            return CapacityVo.builder()
                    .tenantId(tenantId)
                    .storeId(store != null ? store.getId() : null)
                    .visibility(store != null ? store.getCapacityVisibility() : "HIDDEN")
                    .precision("TIER")
                    .usedQty(0)
                    .usedPallet(0)
                    .totalQty(store != null ? store.getTotalCapacityQty() : null)
                    .totalPallet(store != null ? store.getTotalCapacityPallet() : null)
                    .utilization(BigDecimal.ZERO)
                    .tier("EMPTY")
                    .tierLabel("空闲")
                    .snapshotAt(LocalDateTime.now())
                    .expectedNextRefresh(LocalDateTime.now().plusMinutes(10))
                    .build();
        }

        String tierLabel = switch (snapshot.getTier() != null ? snapshot.getTier() : "EMPTY") {
            case "FULL" -> "接近满仓";
            case "HIGH" -> "余量紧张";
            case "MEDIUM" -> "余量适中";
            case "LOW" -> "余量充足";
            default -> "空闲";
        };

        String visibility = store != null ? store.getCapacityVisibility() : "PUBLIC";
        String precision = store != null ? store.getCapacityPrecision() : "TIER";

        // 精度脱敏（ADR-009）
        if ("TIER".equals(precision)) {
            return CapacityVo.builder()
                    .tenantId(tenantId)
                    .storeId(store != null ? store.getId() : null)
                    .visibility(visibility)
                    .precision("TIER")
                    .tier(snapshot.getTier())
                    .tierLabel(tierLabel)
                    .snapshotAt(snapshot.getSnapshotAt())
                    .expectedNextRefresh(snapshot.getSnapshotAt().plusMinutes(10))
                    .build();
        }

        return CapacityVo.builder()
                .tenantId(tenantId)
                .storeId(store != null ? store.getId() : null)
                .visibility(visibility)
                .precision(precision)
                .usedQty(snapshot.getUsedQty())
                .usedPallet(snapshot.getUsedPallet())
                .totalQty(snapshot.getTotalQty())
                .totalPallet(snapshot.getTotalPallet())
                .utilization(snapshot.getUtilization())
                .tier(snapshot.getTier())
                .tierLabel(tierLabel)
                .snapshotAt(snapshot.getSnapshotAt())
                .expectedNextRefresh(snapshot.getSnapshotAt().plusMinutes(10))
                .build();
    }

    @Override
    @Transactional
    public Long createPendingTenantShell(Long taUserId, String tenantName, String contactPhone) {
        // 创建 PENDING 租户壳（tenant + 默认 store + settings）
        Tenant tenant = createTenant(tenantName, null, null, null, taUserId, contactPhone, false);
        tenant.setStatus("PENDING");
        tenantMapper.updateById(tenant);

        // 绑定 tenantId 到该 TA 的 user_roles（注册时已插入 TA 角色 tenantId=null，这里回填）
        UserRole existing = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, taUserId)
                .eq(UserRole::getRole, "TA")
                .isNull(UserRole::getTenantId)
                .eq(UserRole::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTenantId(tenant.getId());
            existing.setUpdatedAt(LocalDateTime.now());
            userRoleMapper.updateById(existing);
        } else {
            UserRole taRole = new UserRole();
            taRole.setId(snowflakeIdUtil.nextId());
            taRole.setUserId(taUserId);
            taRole.setRole("TA");
            taRole.setTenantId(tenant.getId());
            taRole.setStatus("ACTIVE");
            taRole.setPriority(10);
            taRole.setCreatedAt(LocalDateTime.now());
            taRole.setUpdatedAt(LocalDateTime.now());
            taRole.setCreatedBy(taUserId);
            userRoleMapper.insert(taRole);
        }

        log.info("[D-16] 注册建仓：TA {} 创建 PENDING 租户壳 {} ({})", taUserId, tenant.getId(), tenantName);
        return tenant.getId();
    }

    /**
     * D-16：apply 命中注册阶段已建的 PENDING 租户壳 → 完善详细资料（不新建第二个租户）。
     * 同时补写一条 APPROVED 的 tenant_applications 记录，关联到该既有租户，供 OPS 审核队列追溯。
     */
    private Map<String, Object> completePendingTenant(Long userId, Tenant existing, TenantApplyDto dto) {
        // 完善租户主表资料
        if (dto.getName() != null) existing.setName(dto.getName());
        if (dto.getLegalName() != null) existing.setLegalName(dto.getLegalName());
        if (dto.getLicenseNo() != null) existing.setLicenseNo(dto.getLicenseNo());
        if (dto.getLicenseUrl() != null) existing.setLicenseUrl(dto.getLicenseUrl());
        if (dto.getContactPhone() != null) existing.setContactPhone(dto.getContactPhone());
        existing.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(existing);

        // 完善默认 store（地址/经纬度/名称）
        Store store = storeMapper.selectOne(
                new LambdaQueryWrapper<Store>().eq(Store::getTenantId, existing.getId()));
        if (store != null) {
            if (dto.getName() != null) store.setName(dto.getName());
            if (dto.getLng() != null) store.setLng(dto.getLng());
            if (dto.getLat() != null) store.setLat(dto.getLat());
            store.setUpdatedAt(LocalDateTime.now());
            storeMapper.updateById(store);
        }

        // 记录入驻申请（APPROVED，关联既有租户）
        TenantApplication application = new TenantApplication();
        application.setId(snowflakeIdUtil.nextId());
        application.setApplicantUserId(userId);
        application.setName(dto.getName());
        application.setLegalName(dto.getLegalName());
        application.setLicenseNo(dto.getLicenseNo());
        application.setLicenseUrl(dto.getLicenseUrl());
        application.setContactPhone(dto.getContactPhone());
        application.setAddressText(dto.getAddressText());
        application.setLng(dto.getLng());
        application.setLat(dto.getLat());
        application.setTenantId(existing.getId());
        application.setStatus("APPROVED");
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        tenantApplicationMapper.insert(application);

        log.info("[D-16] apply 完善既有 PENDING 租户 {}（注册建仓壳），未新建重复租户", existing.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId().toString());
        result.put("tenantId", existing.getId().toString());
        result.put("status", "PENDING");
        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * D-02(c) 角色鉴权：校验用户具备有效 OPS 角色，否则抛越权。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参。
     */
    private void requireOpsRole(Long userId) {
        long opsCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "OPS")
                .eq(UserRole::getStatus, "ACTIVE"));
        if (opsCount == 0) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
    }

    /**
     * 校验用户具备有效 TA 角色且已绑定租户，返回其可信 tenant_id（员工注册码生成/管理鉴权）。
     * 以 user_roles（登录态推导）为唯一可信来源，不信任客户端传参（G-1.3 / G-2.1）。
     * 非 TA → 越权(42001)；TA 未绑定租户(尚未建仓) → 租户不存在(50210)。
     */
    private Long requireTaRole(Long userId) {
        UserRole taRole = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRole, "TA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getTenantId)
                .last("LIMIT 1"));
        if (taRole == null) {
            // 区分：有 TA 角色但未绑租户 → 50210；完全无 TA 角色 → 42001 越权
            long anyTa = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                    .eq(UserRole::getUserId, userId)
                    .eq(UserRole::getRole, "TA")
                    .eq(UserRole::getStatus, "ACTIVE"));
            if (anyTa > 0) {
                throw new BizException(ErrorCode.TENANT_NOT_FOUND, "请先完成建仓后再生成员工注册码");
            }
            throw new BizException(ErrorCode.PERMISSION_ROLE_001);
        }
        return taRole.getTenantId();
    }

    /** 创建租户 + 店铺 + 设置 */
    private Tenant createTenant(String name, String legalName, String licenseNo, String licenseUrl,
                                 Long contactUserId, String contactPhone, boolean createdByOps) {
        Tenant tenant = new Tenant();
        tenant.setId(snowflakeIdUtil.nextId());
        tenant.setTenantSimpleCode(generateSimpleCode());
        tenant.setName(name);
        tenant.setLegalName(legalName);
        tenant.setLicenseNo(licenseNo);
        tenant.setLicenseUrl(licenseUrl);
        tenant.setContactUserId(contactUserId);
        tenant.setContactPhone(contactPhone);
        tenant.setStatus(createdByOps ? "ACTIVE" : "PENDING");
        tenant.setCreatedByOps(createdByOps ? 1 : 0);
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenant.setCreatedBy(contactUserId);
        tenantMapper.insert(tenant);

        // 默认店铺
        Store store = new Store();
        store.setId(snowflakeIdUtil.nextId());
        store.setTenantId(tenant.getId());
        store.setName(name);
        store.setStatus(createdByOps ? "ACTIVE" : "PENDING");
        store.setCapacityVisibility("PUBLIC");
        store.setCapacityPrecision("TIER");
        store.setCoordinateSystem("GCJ02");
        store.setCreatedAt(LocalDateTime.now());
        store.setUpdatedAt(LocalDateTime.now());
        store.setCreatedBy(contactUserId);
        storeMapper.insert(store);

        // 默认租户设置
        TenantSettings settings = new TenantSettings();
        settings.setId(snowflakeIdUtil.nextId());
        settings.setTenantId(tenant.getId());
        settings.setBatchEnabled(0);
        settings.setPhotoMode("NONE");
        settings.setBillingDim("QTY");
        settings.setExpiryThresholdDays(30);
        settings.setDisplayImageSource("STANDARD");
        settings.setCreatedAt(LocalDateTime.now());
        settings.setUpdatedAt(LocalDateTime.now());
        tenantSettingsMapper.insert(settings);

        return tenant;
    }

    /** 生成4位租户简码 */
    private String generateSimpleCode() {
        long count = tenantMapper.selectCount(null);
        return String.format("CC%02d", (count + 1) % 100);
    }

    /** 判断是否有开关字段变更 */
    private boolean hasAnySwitch(StoreSettingsDto dto) {
        return dto.getBatchEnabled() != null || dto.getPhotoMode() != null
                || dto.getBillingDim() != null || dto.getExpiryThresholdDays() != null
                || dto.getDisplayImageSource() != null;
    }

    /** 应用开关值到 settings */
    private void applySettingsDto(TenantSettings settings, StoreSettingsDto dto, boolean isInsert) {
        if (dto.getBatchEnabled() != null) settings.setBatchEnabled(dto.getBatchEnabled());
        else if (isInsert) settings.setBatchEnabled(0);

        if (dto.getPhotoMode() != null) settings.setPhotoMode(dto.getPhotoMode());
        else if (isInsert) settings.setPhotoMode("NONE");

        if (dto.getBillingDim() != null) settings.setBillingDim(dto.getBillingDim());
        else if (isInsert) settings.setBillingDim("QTY");

        if (dto.getExpiryThresholdDays() != null) settings.setExpiryThresholdDays(dto.getExpiryThresholdDays());
        else if (isInsert) settings.setExpiryThresholdDays(30);

        if (dto.getDisplayImageSource() != null) settings.setDisplayImageSource(dto.getDisplayImageSource());
        else if (isInsert) settings.setDisplayImageSource("STANDARD");
    }

    /** 构建租户详情 VO */
    private TenantDetailVo buildTenantDetail(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) throw new BizException(ErrorCode.TENANT_NOT_FOUND);

        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>().eq(Store::getTenantId, tenantId));
        TenantSettings settings = tenantSettingsMapper.selectOne(
                new LambdaQueryWrapper<TenantSettings>().eq(TenantSettings::getTenantId, tenantId));

        return TenantDetailVo.builder()
                .tenantId(tenantId)
                .tenantSimpleCode(tenant.getTenantSimpleCode())
                .name(tenant.getName())
                .legalName(tenant.getLegalName())
                .status(tenant.getStatus())
                .storeId(store != null ? store.getId() : null)
                .storeName(store != null ? store.getName() : null)
                .lng(store != null ? store.getLng() : null)
                .lat(store != null ? store.getLat() : null)
                .totalCapacityQty(store != null ? store.getTotalCapacityQty() : null)
                .totalCapacityPallet(store != null ? store.getTotalCapacityPallet() : null)
                .capacityVisibility(store != null ? store.getCapacityVisibility() : "PUBLIC")
                .capacityPrecision(store != null ? store.getCapacityPrecision() : "TIER")
                .businessHours(store != null ? store.getBusinessHours() : null)
                .intro(store != null ? store.getIntro() : null)
                .batchEnabled(settings != null ? settings.getBatchEnabled() : 0)
                .photoMode(settings != null ? settings.getPhotoMode() : "NONE")
                .billingDim(settings != null ? settings.getBillingDim() : "QTY")
                .expiryThresholdDays(settings != null ? settings.getExpiryThresholdDays() : 30)
                .displayImageSource(settings != null ? settings.getDisplayImageSource() : "STANDARD")
                .createdAt(tenant.getCreatedAt())
                .build();
    }
}
