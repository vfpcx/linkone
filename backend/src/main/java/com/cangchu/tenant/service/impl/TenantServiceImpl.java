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

        // 绑定 TA 角色
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId().toString());
        result.put("tenantId", tenant.getId().toString());
        result.put("status", "PENDING");
        return result;
    }

    @Override
    @Transactional
    public void audit(Long tenantId, Long opsUserId, TenantAuditDto dto) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
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
                .last("LIMIT 1"));
        if (taRole == null || taRole.getTenantId() == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        Long tenantId = taRole.getTenantId();

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

    // ==================== 私有方法 ====================

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
