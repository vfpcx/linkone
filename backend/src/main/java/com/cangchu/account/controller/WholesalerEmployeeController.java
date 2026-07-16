package com.cangchu.account.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.account.dto.EmployeePermissionsUpdateDto;
import com.cangchu.account.service.WholesalerEmployeeService;
import com.cangchu.account.vo.WholesalerEmployeeVo;
import com.cangchu.common.response.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * WA 员工管理 Controller（P2 入驻 Wave3：列表 / 授权 / R17 禁用 / 30 天恢复）。
 *
 * <p>路径 /api/v1/wholesaler/employees，已被 SaInterceptor 登录拦截覆盖；
 * 置于 account 域——员工管理的本体是 user_roles 与登录会话（禁用即踢）。
 * {id} 为 user_roles.id（员工绑定记录），非 user id。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/employees")
public class WholesalerEmployeeController {

    private final WholesalerEmployeeService employeeService;

    /** 本商户 WE 员工列表（含已禁用；permissions/status/disabledAt） */
    @GetMapping
    public R<List<WholesalerEmployeeVo>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(employeeService.listEmployees(userId));
    }

    /** 调整授权位（整体替换；空数组=收回全部授权） */
    @PutMapping("/{id}/permissions")
    public R<WholesalerEmployeeVo> updatePermissions(@PathVariable Long id,
                                                     @Valid @RequestBody EmployeePermissionsUpdateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(employeeService.updatePermissions(userId, id, dto.getPermissions()));
    }

    /** R17 禁用：置 DISABLED + 即时踢出 token（30 天内可恢复） */
    @PostMapping("/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(employeeService.disableEmployee(userId, id));
    }

    /** 撤销禁用（30 天内；授权保持禁用前设置；逾期 50322） */
    @PostMapping("/{id}/restore")
    public R<Map<String, Object>> restore(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(employeeService.restoreEmployee(userId, id));
    }
}
