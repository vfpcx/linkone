package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.*;
import com.cangchu.tenant.entity.InviteCode;
import com.cangchu.tenant.vo.CapacityVo;
import com.cangchu.tenant.vo.EmployeeInviteVo;
import com.cangchu.tenant.vo.TenantDetailVo;

import java.util.List;
import java.util.Map;

/**
 * 租户服务接口
 */
public interface TenantService {

    /** TA 自助注册仓库（待审核） */
    Map<String, Object> apply(Long userId, TenantApplyDto dto);

    /**
     * D-16：注册时按仓库名创建 PENDING 租户壳（tenant + 默认 store + settings），并把 tenantId 绑定到该 TA 的 user_roles。
     * 仅创建「壳」，详细资料（营业执照/地址/经纬度）后续由 {@link #apply} 完善（apply 会复用已绑定的 PENDING 租户，避免重复建仓）。
     *
     * @return 新建租户 id
     */
    Long createPendingTenantShell(Long taUserId, String tenantName, String contactPhone);

    /** OPS 审核入驻（通过/驳回） */
    void audit(Long tenantId, Long opsUserId, TenantAuditDto dto);

    /** OPS 代建租户 */
    Map<String, Object> createByOps(Long opsUserId, TenantCreateDto dto);

    /** 查当前 TA 的本店设置 */
    TenantDetailVo getMyStore(Long userId);

    /** 改店铺设置 */
    void updateMyStore(Long userId, StoreSettingsDto dto);

    /** 生成/查看店铺码 */
    Map<String, String> getStoreQr(Long userId);

    /** 生成员工注册码 */
    Map<String, Object> generateInviteCode(Long userId, String targetRole, Integer maxUses, Integer expireDays);

    // ==================== 员工注册码（phase-1：解锁 WK 入库） ====================

    /**
     * TA 生成员工注册码（role 仅 WK/ST）。tenant_id 由登录态(TA 绑定租户)推导，不取客户端。
     * 需 TA 登录态（requireTaRole）；非 TA / 未绑定租户拒绝。
     */
    EmployeeInviteVo createEmployeeInvite(Long taUserId, EmployeeInviteCreateDto dto);

    /** 列出本租户的员工注册码（按创建时间倒序）。 */
    List<EmployeeInviteVo> listEmployeeInvites(Long taUserId);

    /** 作废某员工注册码（置 status=REVOKED）；仅本租户、仅 TA。 */
    void revokeEmployeeInvite(Long taUserId, Long inviteId);

    /**
     * 凭码注册时消费员工注册码：校验(存在/未作废/未过期/未超次/角色 WK-ST)，
     * used_count+1（到 maxUses 置 EXHAUSTED），返回该码用于绑定 user_roles。
     * 校验失败抛 BizException（AUTH_INVITE_001..004 / INVITE_*）。
     */
    InviteCode consumeInviteForRegister(String code);

    /** 查实时容量 */
    CapacityVo getCapacity(Long tenantId);
}
