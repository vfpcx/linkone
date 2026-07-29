package com.cangchu.account.service;

/**
 * 账号域统一鉴权/角色查询出口（G-S1/G-S2）。
 *
 * <p>user_roles 表归属 account 域。各业务域（tenant/product/document 等）此前直连
 * {@code UserRoleMapper} 做 requireXxRole 鉴权，违反数据自治（一张表只被其归属域读写）。
 * 本接口把这些跨域直连收敛为账号域 Service 调用；语义与原直连逐一等价
 * （角色 / 维度 / status=ACTIVE / 错误码不变），拆分为微服务时可平滑替换为远程 API。
 *
 * <p>说明：account 自身（登录/注册/员工注册码消费）读写 user_roles 属域内自有，
 * 不经此接口。此接口仅服务于跨域调用方。
 */
public interface AuthService {

    /**
     * 存在性鉴权：用户在指定租户下是否具备某 ACTIVE 角色。
     * 等价于 {@code count(user_id=? AND role=? AND tenant_id=? AND status='ACTIVE') > 0}。
     */
    boolean hasRole(Long userId, String role, Long tenantId);

    /**
     * 存在性鉴权（无租户维度）：用户是否具备某 ACTIVE 角色（如 OPS 平台角色不绑租户）。
     * 等价于 {@code count(user_id=? AND role=? AND status='ACTIVE') > 0}。
     */
    boolean hasRole(Long userId, String role);

    /**
     * 存在性鉴权（批发商维度）：用户在指定批发商下是否具备某 ACTIVE 角色（WA）。
     * 等价于 {@code count(user_id=? AND role=? AND wholesaler_id=? AND status='ACTIVE') > 0}。
     */
    boolean hasWholesalerRole(Long userId, String role, Long wholesalerId);

    /**
     * 上下文查询：取用户某 ACTIVE 角色已绑定的租户 id（tenant_id 非空的第一条）。
     * 等价于 {@code selectOne(user_id=? AND role=? AND status='ACTIVE' AND tenant_id IS NOT NULL LIMIT 1).tenantId}。
     * 无匹配返回 {@code null}（调用方据此区分「未绑租户」与「无该角色」并抛各自错误码）。
     */
    Long findBoundTenantId(Long userId, String role);

    /**
     * 上下文查询：列出用户全部 ACTIVE 角色（只读视图，含 role/tenantId/wholesalerId/priority）。
     * 等价于 {@code selectList(user_id=? AND status='ACTIVE')} 的字段投影。
     * 供 common 租户拦截器按登录态推导可信租户 / 校验 X-Tenant-Id 归属。
     */
    java.util.List<com.cangchu.account.vo.UserRoleView> listActiveRoles(Long userId);

    /**
     * 上下文查询（批发商维度）：列出用户某 ACTIVE 角色绑定的全部 wholesaler_id。
     * 等价于 {@code selectList(user_id=? AND role=? AND status='ACTIVE').map(wholesalerId)}。
     * 供 document.listForWa 等按「我管的批发商集合」过滤。
     */
    java.util.List<Long> listActiveWholesalerIds(Long userId, String role);

    /**
     * 上下文查询（批发商维度反查，P3 缺陷修复）：列出绑定到该批发商的全部 ACTIVE <b>WA</b> 用户 id（去重）。
     * 与 {@link #listActiveUserIdsOfWholesaler} 同构但仅取 role=WA——「归属 WA」通知收件人
     * 以 user_roles 绑定为唯一可信来源：SELF_OPERATED 商户的 {@code wholesalers.owner_user_id}
     * 是 TA 操作人，按 owner 发通知会漏发真实 WA（listForWa 同源推导先例）。
     */
    java.util.List<Long> listActiveWaUserIdsOfWholesaler(Long wholesalerId);

    /**
     * 角色-租户绑定（幂等）：确保用户拥有一条 (role, tenantId, ACTIVE) 的角色记录。
     *
     * <p>行为与原 tenant 域直连逐一等价：优先复用一条 status=ACTIVE 且 tenant_id 为空的
     * 同角色记录（注册阶段预置的 tenantId=null 记录），回填 tenant_id；否则新建一条
     * (role, tenantId, ACTIVE, priority=10)。用于 TA 建仓/入驻绑定。
     *
     * @param createdBy 新建记录时写入的 created_by
     */
    void bindOrCreateTenantRole(Long userId, String role, Long tenantId, Long createdBy);

    /**
     * 角色-租户绑定（存在即跳过）：若用户尚无 (role, tenantId) 记录（不限 status）则新建一条
     * (role, tenantId, ACTIVE, priority=10)。用于 OPS 代建租户绑定 TA。
     * 与原 {@code createByOps} 的 {@code count(user_id,role,tenant_id)>0} 判定逐一等价。
     */
    void ensureTenantRole(Long userId, String role, Long tenantId, Long createdBy);

    /**
     * 上下文查询（批发商维度反查，P2 Wave2）：列出绑定到该批发商的全部 ACTIVE 角色用户 id
     * （去重；不限角色——WA 与 WE 一并返回）。
     * 等价于 {@code selectList(wholesaler_id=? AND status='ACTIVE').map(userId).distinct()}。
     * 供退驻/强制下架副作用链踢 token：只踢 WA 漏踢 WE 是已知高危漏点（WDR-S1-02），
     * 故此方法刻意不加 role 条件。
     */
    java.util.List<Long> listActiveUserIdsOfWholesaler(Long wholesalerId);

    /**
     * 授权位判定（P2 Wave3 WE 员工）：用户在指定批发商下是否为 ACTIVE 的 WE
     * 且 permissions 含指定授权位（{@link com.cangchu.common.util.WePermissions}）。
     * 仅覆盖 WE 路径——WA 本人/TA 不受授权位约束，调用方自行先判 WA/TA。
     */
    boolean hasWholesalerPermission(Long userId, Long wholesalerId, String permission);

    /**
     * 上下文查询（P2 Wave3）：列出用户某角色（WE）绑定的全部 wholesaler_id，
     * 与 {@link #listActiveWholesalerIds} 同构但供 WE 只读视图（如询价列表）使用。
     * 语义等价于 listActiveWholesalerIds(userId, "WE")——单独列出以明确调用意图。
     */
    java.util.List<Long> listActiveWeWholesalerIds(Long userId);

    /**
     * 角色-批发商绑定（幂等，返回角色记录 id）：确保用户拥有一条
     * (role, tenantId, wholesalerId, ACTIVE) 记录；已存在则返回其 id，否则新建
     * (priority=5) 后返回新 id。用于 WA 账号开通绑定。
     *
     * @return 该角色记录的 user_roles.id
     */
    Long ensureWholesalerRole(Long userId, String role, Long tenantId, Long wholesalerId, Long createdBy);
}
