package com.cangchu.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.account.entity.User;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.service.AuthService;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.tenant.dto.OpsWholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.dto.WholesalerApplyDto;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.entity.Wholesaler;
import com.cangchu.tenant.entity.WholesalerApplication;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.mapper.WholesalerApplicationMapper;
import com.cangchu.tenant.mapper.WholesalerMapper;
import com.cangchu.tenant.service.BlacklistService;
import com.cangchu.tenant.service.WholesalerApplicationService;
import com.cangchu.tenant.service.WholesalerService;
import com.cangchu.tenant.vo.WholesalerApplicationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批发商入驻申请服务实现（P2 Wave1）。
 *
 * <p>安全自检（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S4 越权：TA 审批/列表 Service 内显式 {@code authService.hasRole(userId,"TA",tenantId)}；
 *       OPS 代建显式 {@code hasRole(userId,"OPS")}；不信任客户端角色。</li>
 *   <li>租户隔离：wholesaler_applications 已入 TenantLine 白名单兜底 + 本类查询显式 eq(tenant_id)
 *       双保险；跨租户审批按「不可见即不存在」返回 50203，不泄漏他租户申请存在性。</li>
 *   <li>D-05 状态机：仅 PENDING 可审（50203）；驳回必填 remark（40003）。</li>
 *   <li>S6 唯一性：一个 WA 账号仅一个 ACTIVE 入驻（50204）/一个 PENDING 申请（50201）；
 *       通过建主体撞 uk_wholesaler_tenant_id_name → 50231。</li>
 *   <li>R-04 黑名单：自助申请与 OPS 代建同检（决策 O-2），命中 50205。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WholesalerApplicationServiceImpl implements WholesalerApplicationService {

    private final WholesalerApplicationMapper applicationMapper;
    private final WholesalerMapper wholesalerMapper;
    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final BlacklistService blacklistService;
    private final WholesalerService wholesalerService;
    private final SnowflakeIdUtil snowflakeIdUtil;

    @Override
    @Transactional
    public Map<String, Object> selfApply(Long userId, WholesalerApplyDto dto) {
        Long tenantId = parseTenantId(dto.getTargetTenantId());
        requireTenantExists(tenantId);

        // 黑名单全检：申请账号手机号 + 表单联系电话 + 执照号（任一命中即拒，R-04）
        User applicant = userMapper.selectById(userId);
        String accountPhone = applicant != null ? applicant.getPhone() : null;
        String contactPhone = dto.getContactPhone() != null && !dto.getContactPhone().isBlank()
                ? dto.getContactPhone().trim() : accountPhone;
        if (blacklistService.isBlacklisted(accountPhone, dto.getLicense())
                || blacklistService.isBlacklisted(contactPhone, null)) {
            throw new BizException(ErrorCode.BLACKLIST_HIT);
        }

        WholesalerApplication app = doCreatePending(userId, tenantId, dto.getName().trim(),
                dto.getContactName(), contactPhone, dto.getLicense());
        log.info("[入驻] WA {} 向租户 {} 提交入驻申请 {}（{}）", userId, tenantId, app.getId(), app.getName());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId().toString());
        result.put("status", app.getStatus());
        return result;
    }

    @Override
    @Transactional
    public Long createFromRegister(Long userId, String targetTenantId, String wholesalerName, String phone) {
        Long tenantId = parseTenantId(targetTenantId);
        requireTenantExists(tenantId);

        // 黑名单命中直接拒绝（随注册事务整体回滚，不留半截账号入驻）
        if (blacklistService.isBlacklisted(phone, null)) {
            throw new BizException(ErrorCode.BLACKLIST_HIT);
        }

        String name = wholesalerName != null && !wholesalerName.isBlank()
                ? wholesalerName.trim()
                : "商户-" + phone.substring(phone.length() - 4);
        WholesalerApplication app = doCreatePending(userId, tenantId, name, null, phone, null);
        log.info("[入驻][D-16] WA 注册直申自动建单：user={} application={} tenant={}", userId, app.getId(), tenantId);
        return app.getId();
    }

    @Override
    public Map<String, Object> pageForTenant(Long tenantId, Long taUserId, int page, int size, String status) {
        requireTaRole(tenantId, taUserId);

        Page<WholesalerApplication> p = applicationMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<WholesalerApplication>()
                        // 显式 eq(tenant_id)（与 TenantLine 兜底叠加，双保险）
                        .eq(WholesalerApplication::getTenantId, tenantId)
                        .eq(status != null && !status.isBlank(), WholesalerApplication::getStatus, status)
                        .orderByDesc(WholesalerApplication::getCreatedAt));

        List<WholesalerApplicationVo> records = p.getRecords().stream().map(this::toVo).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", p.getTotal());
        result.put("page", p.getCurrent());
        result.put("size", p.getSize());
        return result;
    }

    @Override
    public List<WholesalerApplicationVo> listMine(Long userId) {
        // 仅本人：applicant_user_id=登录用户（S4——过滤条件取自登录态，不接受客户端参数），
        // 含 status 与驳回理由 audit_remark；量小不分页，创建时间倒序。
        return applicationMapper.selectList(new LambdaQueryWrapper<WholesalerApplication>()
                        .eq(WholesalerApplication::getApplicantUserId, userId)
                        .orderByDesc(WholesalerApplication::getCreatedAt)
                        .orderByDesc(WholesalerApplication::getId)).stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    @Transactional
    public Map<String, Object> audit(Long tenantId, Long taUserId, Long applicationId,
                                     WholesalerApplicationAuditDto dto) {
        requireTaRole(tenantId, taUserId);

        // TenantLine 兜底 + 显式租户归属校验：跨租户/不存在统一按不存在处理（不泄漏存在性）
        WholesalerApplication app = applicationMapper.selectById(applicationId);
        if (app == null || !tenantId.equals(app.getTenantId())) {
            throw new BizException(ErrorCode.WHOLESALER_APPLICATION_NOT_AUDITABLE);
        }

        // D-05 状态机：仅 PENDING 可审
        if (!"PENDING".equals(app.getStatus())) {
            throw new BizException(ErrorCode.WHOLESALER_APPLICATION_NOT_AUDITABLE,
                    "申请当前状态为 " + app.getStatus() + "，不可重复审核");
        }

        String action = dto.getAction();
        if (!"APPROVED".equals(action) && !"REJECTED".equals(action)) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "审核结果仅支持 APPROVED/REJECTED");
        }
        // 驳回必填理由（产品规则），先于状态翻转校验
        if ("REJECTED".equals(action) && (dto.getRemark() == null || dto.getRemark().isBlank())) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "驳回必须填写理由");
        }

        // CON 并发防护（CAS）：状态翻转用数据库条件更新 where status='PENDING' 抢占，
        // 并发 approve/reject 只有一方 affected=1，另一方按 50203 拒绝——不依赖内存判断。
        // F1：终态同时置 pending_flag=NULL（释放 uk_applicant_pending 名额，允许驳回后重新申请）。
        LocalDateTime auditedAt = LocalDateTime.now();
        int affected = applicationMapper.update(null, new LambdaUpdateWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getId, app.getId())
                .eq(WholesalerApplication::getTenantId, tenantId)
                .eq(WholesalerApplication::getStatus, "PENDING")
                .set(WholesalerApplication::getStatus, action)
                .set(WholesalerApplication::getPendingFlag, null)
                .set(WholesalerApplication::getAuditUserId, taUserId)
                .set(WholesalerApplication::getAuditedAt, auditedAt)
                .set(WholesalerApplication::getAuditRemark, dto.getRemark()));
        if (affected == 0) {
            throw new BizException(ErrorCode.WHOLESALER_APPLICATION_NOT_AUDITABLE,
                    "申请已被并发审核，请刷新后重试");
        }
        app.setStatus(action);
        app.setAuditUserId(taUserId);
        app.setAuditedAt(auditedAt);
        app.setAuditRemark(dto.getRemark());

        if ("APPROVED".equals(action)) {
            // F4 复查：申请提交与审批之间该账号可能已被 OPS 代建/他仓通过——
            // 已有 ACTIVE 入驻绑定则拒绝通过（50204，抛出随事务回滚 CAS 翻转）
            if (!authService.listActiveWholesalerIds(app.getApplicantUserId(), "WA").isEmpty()) {
                throw new BizException(ErrorCode.WHOLESALER_ALREADY_ONBOARDED,
                        "该申请账号已有生效的入驻绑定，不可重复通过");
            }

            // 建主体：ACTIVE / SELF_APPLY，owner = 申请人（CAS 抢占成功后才落主体，失败随事务回滚）
            Wholesaler wholesaler = new Wholesaler();
            wholesaler.setId(snowflakeIdUtil.nextId());
            wholesaler.setTenantId(tenantId);
            wholesaler.setName(app.getName());
            wholesaler.setOwnerUserId(app.getApplicantUserId());
            wholesaler.setLicense(app.getLicense());
            wholesaler.setStatus("ACTIVE");
            wholesaler.setSource("SELF_APPLY");
            wholesaler.setCreatedBy(taUserId);
            try {
                wholesalerMapper.insert(wholesaler);
            } catch (DuplicateKeyException e) {
                // 撞 uk_wholesaler_tenant_id_name → 语义码（S6）
                throw new BizException(ErrorCode.WHOLESALER_NAME_DUPLICATED);
            }

            // 幂等开通 WA 角色绑定（申请人账号已存在，直接绑定 wholesaler 维度角色）
            authService.ensureWholesalerRole(app.getApplicantUserId(), "WA", tenantId,
                    wholesaler.getId(), taUserId);

            // 回填 wholesaler_id（CAS 已抢占，此处按 id 更新）
            app.setWholesalerId(wholesaler.getId());
            WholesalerApplication backfill = new WholesalerApplication();
            backfill.setId(app.getId());
            backfill.setWholesalerId(wholesaler.getId());
            applicationMapper.updateById(backfill);
        }

        log.info("[入驻] TA {} {} 申请 {}（租户 {}），wholesalerId={}",
                taUserId, action, applicationId, tenantId, app.getWholesalerId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.getId().toString());
        result.put("status", app.getStatus());
        result.put("wholesalerId", app.getWholesalerId() != null ? app.getWholesalerId().toString() : null);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> createByOps(Long opsUserId, OpsWholesalerCreateDto dto) {
        // D-02(c)：代建仅限 OPS
        if (!authService.hasRole(opsUserId, "OPS")) {
            throw new BizException(ErrorCode.PERMISSION_ROLE_002);
        }
        // authBasis 必填（DTO @NotBlank 已拦，此处防御性兜底）
        if (dto.getAuthBasis() == null || dto.getAuthBasis().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003, "代建必须提供租户管理员授权凭据或客诉单号");
        }

        Long tenantId = parseTenantId(dto.getTenantId());
        requireTenantExists(tenantId);

        // 决策 O-2：黑名单同样拦截 OPS 代建路径（防绕过 R-04）
        if (blacklistService.isBlacklisted(dto.getWaPhone(), dto.getLicense())) {
            throw new BizException(ErrorCode.BLACKLIST_HIT);
        }

        // 幂等查/建 WA 用户；已有 ACTIVE 入驻绑定则拒（一个 WA 账号只入驻一个仓库）
        Long waUserId = wholesalerService.ensureWaUser(dto.getWaPhone());
        if (!authService.listActiveWholesalerIds(waUserId, "WA").isEmpty()) {
            throw new BizException(ErrorCode.WHOLESALER_ALREADY_ONBOARDED);
        }

        Wholesaler wholesaler = new Wholesaler();
        wholesaler.setId(snowflakeIdUtil.nextId());
        wholesaler.setTenantId(tenantId);
        wholesaler.setName(dto.getName().trim());
        wholesaler.setOwnerUserId(waUserId);
        wholesaler.setLicense(dto.getLicense());
        wholesaler.setStatus("ACTIVE");
        wholesaler.setSource("OPS_CREATED");
        wholesaler.setCreatedBy(opsUserId);
        try {
            wholesalerMapper.insert(wholesaler);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.WHOLESALER_NAME_DUPLICATED);
        }

        // 复用幂等 WA 账号开通（查/建用户 + 角色绑定）
        WholesalerService.WaAccount account = wholesalerService.provisionWaAccount(
                tenantId, wholesaler.getId(), dto.getWaPhone(), opsUserId);

        // F4：代建成功后自动关闭该账号存量 PENDING 申请（置 REJECTED + 释放 pending_flag），
        // 避免「已入驻账号还挂着待审申请」——若被 TA 后续通过将撞 50204 复查，但主动关闭留痕更清晰
        int closed = applicationMapper.update(null, new LambdaUpdateWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getApplicantUserId, waUserId)
                .eq(WholesalerApplication::getStatus, "PENDING")
                .set(WholesalerApplication::getStatus, "REJECTED")
                .set(WholesalerApplication::getPendingFlag, null)
                .set(WholesalerApplication::getAuditUserId, opsUserId)
                .set(WholesalerApplication::getAuditedAt, LocalDateTime.now())
                .set(WholesalerApplication::getAuditRemark, "平台运维代建入驻生效，存量待审申请自动关闭"));
        if (closed > 0) {
            log.info("[入驻][OPS代建] 自动关闭 WA 用户 {} 的存量 PENDING 申请 {} 条", waUserId, closed);
        }

        // 留痕：补 APPROVED 申请单（source=OPS_CREATED + auth_basis 授权依据）
        WholesalerApplication trace = new WholesalerApplication();
        trace.setId(snowflakeIdUtil.nextId());
        trace.setTenantId(tenantId);
        trace.setApplicantUserId(waUserId);
        trace.setName(wholesaler.getName());
        trace.setContactName(dto.getContactName());
        trace.setContactPhone(dto.getWaPhone());
        trace.setLicense(dto.getLicense());
        trace.setStatus("APPROVED");
        trace.setSource("OPS_CREATED");
        trace.setAuthBasis(dto.getAuthBasis().trim());
        trace.setAuditUserId(opsUserId);
        trace.setAuditedAt(LocalDateTime.now());
        trace.setWholesalerId(wholesaler.getId());
        applicationMapper.insert(trace);

        // 留痕日志（授权依据完整记录，审计可追溯）
        log.info("[入驻][OPS代建] OPS {} 代建批发商 {}（租户 {}，WA 用户 {}），授权依据：{}",
                opsUserId, wholesaler.getId(), tenantId, waUserId, dto.getAuthBasis());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wholesalerId", wholesaler.getId().toString());
        result.put("tenantId", tenantId.toString());
        result.put("waUserId", waUserId.toString());
        result.put("waRoleId", account.userRoleId() != null ? account.userRoleId().toString() : null);
        result.put("applicationId", trace.getId().toString());
        result.put("status", wholesaler.getStatus());
        result.put("source", wholesaler.getSource());
        return result;
    }

    // ==================== 私有方法 ====================

    /** 建 PENDING 申请单（自助/注册直申共用）：先做重复入驻/重复申请校验。 */
    private WholesalerApplication doCreatePending(Long applicantUserId, Long tenantId, String name,
                                                  String contactName, String contactPhone, String license) {
        // 一个 WA 账号只能入驻一个仓库：已有 ACTIVE WA 绑定即拒（50204）
        if (!authService.listActiveWholesalerIds(applicantUserId, "WA").isEmpty()) {
            throw new BizException(ErrorCode.WHOLESALER_ALREADY_ONBOARDED);
        }
        // 一个账号仅一个 PENDING 申请（50201）。申请人尚无租户绑定 → TenantContext 为空，
        // TenantLine 不注入条件，此计数为全平台维度（与「只能入驻一个仓库」规则一致）。
        long pending = applicationMapper.selectCount(new LambdaQueryWrapper<WholesalerApplication>()
                .eq(WholesalerApplication::getApplicantUserId, applicantUserId)
                .eq(WholesalerApplication::getStatus, "PENDING"));
        if (pending > 0) {
            throw new BizException(ErrorCode.WHOLESALER_APPLICATION_PENDING);
        }

        WholesalerApplication app = new WholesalerApplication();
        app.setId(snowflakeIdUtil.nextId());
        app.setTenantId(tenantId);
        app.setApplicantUserId(applicantUserId);
        app.setName(name);
        app.setContactName(contactName);
        app.setContactPhone(contactPhone);
        app.setLicense(license);
        app.setStatus("PENDING");
        app.setPendingFlag(1);
        app.setSource("SELF_APPLY");
        try {
            applicationMapper.insert(app);
        } catch (DuplicateKeyException e) {
            // F1：并发窗口内双双通过 selectCount 预检 → uk_applicant_pending 兜底，
            // 唯一键冲突即「已有 PENDING 申请」，转语义码 50201（CON-S7-01 恰一方成功）
            throw new BizException(ErrorCode.WHOLESALER_APPLICATION_PENDING);
        }
        return app;
    }

    /** S4：操作者必须是该租户的 TA（user_roles 登录态为唯一可信来源）。 */
    private void requireTaRole(Long tenantId, Long userId) {
        if (!authService.hasRole(userId, "TA", tenantId)) {
            throw new BizException(ErrorCode.PERMISSION_TENANT_001);
        }
    }

    /**
     * F5：入驻目标租户必须存在且 ACTIVE——PENDING 仓库尚未过审、FROZEN/下线仓库
     * 不应再接收入驻（自助申请 / 注册直申 / OPS 代建三路径同检）。
     * 错误码复用 STATE_TENANT 段：PENDING→50101、FROZEN→50102、其余非 ACTIVE→50103。
     */
    private void requireTenantExists(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "目标仓库不存在");
        }
        String status = tenant.getStatus();
        if ("ACTIVE".equals(status)) {
            return;
        }
        if ("PENDING".equals(status)) {
            throw new BizException(ErrorCode.STATE_TENANT_001, "目标仓库审核中，暂不可入驻");
        }
        if ("FROZEN".equals(status)) {
            throw new BizException(ErrorCode.STATE_TENANT_002, "目标仓库已冻结，暂不可入驻");
        }
        throw new BizException(ErrorCode.STATE_TENANT_003, "目标仓库已下线或不可用，无法入驻");
    }

    private Long parseTenantId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_001, "目标仓库ID非法");
        }
    }

    private WholesalerApplicationVo toVo(WholesalerApplication app) {
        return WholesalerApplicationVo.builder()
                .id(app.getId())
                .tenantId(app.getTenantId())
                .applicantUserId(app.getApplicantUserId())
                .name(app.getName())
                .contactName(app.getContactName())
                .contactPhone(app.getContactPhone())
                .license(app.getLicense())
                .status(app.getStatus())
                .source(app.getSource())
                .authBasis(app.getAuthBasis())
                .auditUserId(app.getAuditUserId())
                .auditedAt(app.getAuditedAt())
                .auditRemark(app.getAuditRemark())
                .wholesalerId(app.getWholesalerId())
                .createdAt(app.getCreatedAt())
                .build();
    }
}
