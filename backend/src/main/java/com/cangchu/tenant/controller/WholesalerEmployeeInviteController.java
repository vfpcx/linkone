package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.WholesalerEmployeeInviteCreateDto;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.EmployeeInviteVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * WA 端批发商员工(WE)注册码 Controller（P2 入驻 Wave3，契约见 task_plan「接口契约」）。
 *
 * <p>路径 /api/v1/wholesaler/employee-invites，已被 SaInterceptor 登录拦截覆盖；
 * WA 归属校验在服务层（登录态推导 wholesaler，G-2.1）。角色固定 WE，客户端不可传。
 * 注意与 TA 端 /api/v1/tenant/employee-invites（仅 WK/ST）互不相通（WEM-S2-02）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/employee-invites")
public class WholesalerEmployeeInviteController {

    private final TenantService tenantService;

    /** 生成 WE 注册码：{expireDays, maxUses, permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]} */
    @PostMapping
    public R<EmployeeInviteVo> create(@Valid @RequestBody(required = false) WholesalerEmployeeInviteCreateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.createWeEmployeeInvite(userId,
                dto != null ? dto : new WholesalerEmployeeInviteCreateDto()));
    }

    /** 本商户 WE 注册码列表（倒序） */
    @GetMapping
    public R<List<EmployeeInviteVo>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.listWeEmployeeInvites(userId));
    }

    /** 作废注册码（置 REVOKED；跨商户按不存在处理） */
    @DeleteMapping("/{id}")
    public R<Void> revoke(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        tenantService.revokeWeEmployeeInvite(userId, id);
        return R.ok();
    }
}
