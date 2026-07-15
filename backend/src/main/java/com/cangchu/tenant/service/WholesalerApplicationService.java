package com.cangchu.tenant.service;

import com.cangchu.tenant.dto.OpsWholesalerCreateDto;
import com.cangchu.tenant.dto.WholesalerApplicationAuditDto;
import com.cangchu.tenant.dto.WholesalerApplyDto;

import java.util.Map;

/**
 * 批发商入驻申请服务（P2 Wave1：申请 → TA 审批 → 黑名单 → OPS 代建）。
 *
 * <p>双轨模式（决策 O-1，复用 tenant_applications 审批先例）：
 * wholesaler_applications 申请表承载全部入驻路径留痕（SELF_APPLY/OPS_CREATED/TA_SELF_OPERATED），
 * 审批通过后创建 wholesalers 主体并回填 wholesaler_id。
 */
public interface WholesalerApplicationService {

    /**
     * WA 自助入驻申请（登录用户为申请人）。
     * 规则：黑名单命中拒绝(50205)；已有 ACTIVE 入驻拒绝(50204)；已有 PENDING 申请拒绝(50201)。
     *
     * @return { applicationId, status }
     */
    Map<String, Object> selfApply(Long userId, WholesalerApplyDto dto);

    /**
     * 注册接入（AccountServiceImpl WA 注册带 targetTenantId/wholesalerName 时调用）：
     * 自动创建 PENDING 申请单，校验规则与 {@link #selfApply} 一致（黑名单命中抛 50205 使注册整体回滚）。
     *
     * @param wholesalerName 可空；空时以手机号尾号生成兜底名称
     * @return 申请单 id
     */
    Long createFromRegister(Long userId, String targetTenantId, String wholesalerName, String phone);

    /**
     * TA 分页列表（TenantLine 隔离 + 显式 tenant_id 双保险）。
     *
     * @return { records: List<WholesalerApplicationVo>, total, page, size }
     */
    Map<String, Object> pageForTenant(Long tenantId, Long taUserId, int page, int size, String status);

    /**
     * TA 审批（仿 TenantServiceImpl.audit）：仅 PENDING 可审(50203)；驳回必填 remark；
     * 通过 → 建 Wholesaler(ACTIVE, SELF_APPLY) + 幂等开通 WA 角色绑定 + 回填 wholesaler_id。
     *
     * @return { applicationId, status, wholesalerId? }
     */
    Map<String, Object> audit(Long tenantId, Long taUserId, Long applicationId, WholesalerApplicationAuditDto dto);

    /**
     * OPS 代建（PRD R3）：authBasis 必填留痕；黑名单同样拦截（决策 O-2）；
     * 直接建 ACTIVE Wholesaler(source=OPS_CREATED) + 幂等开通 WA 账号 + 补 APPROVED 申请单留痕。
     *
     * @return { wholesalerId, tenantId, waUserId, waRoleId, applicationId, status, source }
     */
    Map<String, Object> createByOps(Long opsUserId, OpsWholesalerCreateDto dto);
}
