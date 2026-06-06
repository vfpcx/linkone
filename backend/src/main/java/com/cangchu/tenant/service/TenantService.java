package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.*;
import com.cangchu.tenant.vo.CapacityVo;
import com.cangchu.tenant.vo.TenantDetailVo;

import java.util.Map;

/**
 * 租户服务接口
 */
public interface TenantService {

    /** TA 自助注册仓库（待审核） */
    Map<String, Object> apply(Long userId, TenantApplyDto dto);

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

    /** 查实时容量 */
    CapacityVo getCapacity(Long tenantId);
}
