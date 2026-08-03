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

    /**
     * 老板多仓：已登录的 TA(老板)直接新建一个仓库（PENDING）+ 默认 store/settings，
     * 并把新仓 TA 角色绑定到同一账号。要求调用者已是 ACTIVE TA（否则拒绝）。
     * @return tenantId / simpleCode / status
     */
    Map<String, Object> createWarehouse(Long userId, TenantApplyDto dto);

    /** 老板多仓：当前账号名下所有 TA 绑定的仓库列表（顶栏切换器用）。 */
    java.util.List<com.cangchu.tenant.vo.WarehouseVo> listMyWarehouses(Long userId);

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
     * 凭码注册时消费员工注册码：校验(存在/未作废/未过期/未超次/角色 WK-ST-WE)，
     * used_count+1（到 maxUses 置 EXHAUSTED），返回该码用于绑定 user_roles。
     * WE 码（P2 Wave3）额外携带 wholesalerId + 初始 permissions 供注册绑定。
     * 校验失败抛 BizException（AUTH_INVITE_001..004 / INVITE_*）。
     */
    InviteCode consumeInviteForRegister(String code);

    // ==================== WE 员工注册码（P2 入驻 Wave3，WA 端） ====================

    /**
     * WA 生成批发商员工(WE)注册码：targetRole 固定 WE，绑定登录 WA 的 wholesaler_id，
     * permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]（越界 50319）。非 WA / 未入驻拒绝。
     */
    EmployeeInviteVo createWeEmployeeInvite(Long waUserId, WholesalerEmployeeInviteCreateDto dto);

    /** 列出本商户的 WE 注册码（按创建时间倒序；只含本 wholesaler 的 WE 码）。 */
    List<EmployeeInviteVo> listWeEmployeeInvites(Long waUserId);

    /** 作废本商户的 WE 注册码（置 REVOKED）；跨商户/非 WE 码按不存在处理。 */
    void revokeWeEmployeeInvite(Long waUserId, Long inviteId);

    /**
     * OPS 租户列表（P2 Wave3 顺路补齐，前端契约先行 AdminTenantItem）：
     * 全平台分页，status 可选过滤（PENDING/ACTIVE/REJECTED）；仅 OPS（42002）。
     * 返回 PageData 形状 {list,total,page,pageSize,totalPages}。
     */
    Map<String, Object> pageTenantsForAdmin(Long opsUserId, String status, int page, int size);

    /**
     * 公开租户目录（P2 Wave6 DEF-1，WA 注册页选择目标仓库）：
     * 仅返回 ACTIVE 租户的 id+name（严禁敏感字段），keyword 按仓库名模糊匹配，
     * limit 上限 20（默认 10）。匿名可访问——IP 维度 Redisson 限流防枚举（G-6.1/G-6.2），
     * 超限抛 43001。
     *
     * @param clientIp 客户端 IP（IpUtil 解析，环回豁免同短信基建）
     */
    List<com.cangchu.tenant.vo.TenantDirectoryItemVo> directory(String keyword, int limit, String clientIp);

    /** 查实时容量 */
    CapacityVo getCapacity(Long tenantId);

    /**
     * 只读：取租户简码（供 document 等编排域生成单据号，替代跨域直连 TenantMapper，符合 G-S1/G-S2）。
     * 隔离/查找行为等同于原 {@code tenantMapper.selectById(tenantId).getTenantSimpleCode()}——
     * 内部同经 TenantMapper（受 TenantLine 兜底）。租户不存在或未设简码时返回 {@code null}，
     * 由调用方决定占位策略（如 "T"+tenantId）。
     *
     * @return 租户简码；不存在或未设置返回 null
     */
    String getSimpleCode(Long tenantId);

    /**
     * 只读：取租户联系人（TA）user id（P3 BE-W1：入库异议仲裁单创建时通知 TA 审批中心角标，
     * 替代跨域直连 TenantMapper，符合 G-S1/G-S2）。租户不存在返回 {@code null}
     * （调用方按通知降级处理，不阻断业务主链）。
     */
    Long getContactUserId(Long tenantId);

    // ==================== P3b T4-W1 批次开关（13 §3.5，供 BatchService/document 编排域调用） ====================

    /**
     * 只读：取租户批次配置（开关状态 / 启用时点 / 临期阈值天数）。
     * settings 行缺失按默认值（关闭 / null / 30 天）返回，不抛异常。
     */
    com.cangchu.tenant.vo.TenantBatchConfigVo getBatchConfig(Long tenantId);

    /**
     * 写批次开关（仅供 BatchService.toggle 专用端点编排调用——通用设置接口仍拒改，50360 语义保留）。
     * 启用时以 {@code enabledAt} 覆写 batch_enabled_at（FIFO 切割时点）；停用保留历史时点作锚点。
     * settings 行缺失时按默认值补建。
     */
    void setBatchEnabled(Long tenantId, boolean enabled, java.time.LocalDateTime enabledAt, Long operatorUserId);

    /** 只读：全平台批次功能已启用的租户 id（T4-W2 双 Job 按此过滤；Job 无 TenantContext 全平台扫描先例）。 */
    List<Long> listBatchEnabledTenantIds();
}
