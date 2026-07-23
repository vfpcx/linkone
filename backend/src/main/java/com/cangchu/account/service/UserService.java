package com.cangchu.account.service;

import java.util.Collection;
import java.util.Map;

/**
 * 账号域用户查询/开通出口（G-S1/G-S2 架构债偿还，2026-07-23）。
 *
 * <p>收敛 tenant 模块此前对 {@code UserMapper}/{@code User} entity 的跨域直连
 * （TenantServiceImpl 代建 TA / WholesalerServiceImpl 开通 WA / WholesalerApplicationServiceImpl
 * 取申请人手机号 / pageTenantsForAdmin 批量取申请人显示名）。每个方法与原直连点
 * 查询/写入逐一等价（字段、幂等语义、临时密码生成均不变），仅把访问点挪回 users 表的归属域。
 * 不暴露 User entity——出参只用基础类型与 record 视图。
 */
public interface UserService {

    /** 幂等查/建结果视图（不暴露 User entity）。 */
    record EnsuredUser(Long userId, boolean isNew) {}

    /**
     * 按手机号幂等查/建用户（不绑任何角色）：已存在则原样返回；不存在则创建
     * ACTIVE 用户（昵称=手机号后 4 位，随机 8 位临时密码 BCrypt 落库——临时密码
     * 只存在于短信通道，本方法不返回、不打日志）。
     *
     * @param phone          手机号（内部 trim）
     * @param registerSource 注册来源留痕（如 WA_PROVISION / OPS_PROXY）
     */
    EnsuredUser ensureUserByPhone(String phone, String registerSource);

    /** 按用户 id 取手机号；用户不存在返回 null。 */
    String getPhone(Long userId);

    /**
     * 批量取用户显示名（realName 优先，空则回落 nickname；均空为 null），
     * key=userId。入参空集合返回空 Map（调用方防 N+1 批量取）。
     */
    Map<Long, String> getDisplayNames(Collection<Long> userIds);
}
