package com.cangchu.account.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 账号域对外暴露的用户角色只读视图（G-S2：跨域只依赖 Service 的 VO，不依赖 account 的 entity/mapper）。
 *
 * <p>仅承载跨域鉴权/租户上下文推导所需字段（role / tenantId / wholesalerId / priority），
 * 供 common 拦截器等按登录态推导可信租户使用。
 */
@Data
@AllArgsConstructor
public class UserRoleView {

    private String role;

    private Long tenantId;

    private Long wholesalerId;

    private Integer priority;
}
