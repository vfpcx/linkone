package com.cangchu.tenant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.response.R;
import com.cangchu.tenant.dto.*;
import com.cangchu.tenant.service.TenantService;
import com.cangchu.tenant.vo.CapacityVo;
import com.cangchu.tenant.vo.EmployeeInviteVo;
import com.cangchu.tenant.vo.TenantDetailVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 租户 Controller（8 个 API）
 */
@RestController
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    /** TA 自助注册仓库（状态待审核） */
    @PostMapping("/api/v1/tenant/apply")
    public R<Map<String, Object>> apply(@Valid @RequestBody TenantApplyDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> result = tenantService.apply(userId, dto);
        return R.ok(result);
    }

    /** OPS 审核入驻（通过/驳回） */
    @PostMapping("/api/v1/admin/tenant/{id}/audit")
    public R<Void> audit(@PathVariable Long id, @Valid @RequestBody TenantAuditDto dto) {
        Long opsUserId = StpUtil.getLoginIdAsLong();
        tenantService.audit(id, opsUserId, dto);
        return R.ok();
    }

    /** OPS 租户列表（P2 Wave3；status ∈ PENDING/ACTIVE/REJECTED 可选过滤，返回 PageData 形状） */
    @GetMapping("/api/v1/admin/tenants")
    public R<Map<String, Object>> listTenantsForAdmin(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size) {
        Long opsUserId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.pageTenantsForAdmin(opsUserId, status, page, size));
    }

    /** OPS 代建租户（直接通过 + 短信临时密码） */
    @PostMapping("/api/v1/admin/tenant/create")
    public R<Map<String, Object>> createByOps(@Valid @RequestBody TenantCreateDto dto) {
        Long opsUserId = StpUtil.getLoginIdAsLong();
        Map<String, Object> result = tenantService.createByOps(opsUserId, dto);
        return R.ok(result);
    }

    /** 老板多仓：已登录 TA 新建一个仓库 */
    @PostMapping("/api/v1/tenant/warehouses")
    public R<Map<String, Object>> createWarehouse(@Valid @RequestBody TenantApplyDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.createWarehouse(userId, dto));
    }

    /** 老板多仓：当前账号名下所有仓库（顶栏切换器用） */
    @GetMapping("/api/v1/tenant/warehouses")
    public R<List<com.cangchu.tenant.vo.WarehouseVo>> listMyWarehouses() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.listMyWarehouses(userId));
    }

    /** 查当前 TA 的本店设置 */
    @GetMapping("/api/v1/tenant/me")
    public R<TenantDetailVo> getMyStore() {
        Long userId = StpUtil.getLoginIdAsLong();
        TenantDetailVo vo = tenantService.getMyStore(userId);
        return R.ok(vo);
    }

    /** 改店铺设置 */
    @PutMapping("/api/v1/tenant/me")
    public R<Void> updateMyStore(@Valid @RequestBody StoreSettingsDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        tenantService.updateMyStore(userId, dto);
        return R.ok();
    }

    /** 生成/查看店铺码 */
    @PostMapping("/api/v1/tenant/store-qr")
    public R<Map<String, String>> getStoreQr() {
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, String> result = tenantService.getStoreQr(userId);
        return R.ok(result);
    }

    /** 生成员工注册码 */
    @PostMapping("/api/v1/tenant/invite-code")
    public R<Map<String, Object>> generateInviteCode(@RequestParam(defaultValue = "WK") String targetRole,
                                                      @RequestParam(defaultValue = "1") Integer maxUses,
                                                      @RequestParam(required = false) Integer expireDays) {
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> result = tenantService.generateInviteCode(userId, targetRole, maxUses, expireDays);
        return R.ok(result);
    }

    /**
     * 公开租户目录（P2 Wave6 DEF-1，WA 注册页选择目标仓库）。
     * 匿名可访问（SaTokenConfig 已显式声明公开），仅返回 ACTIVE 租户的 id+name，
     * limit 上限 20；IP 维度 Redisson 限流防枚举（服务层，超限 43001）。
     */
    @GetMapping("/api/v1/tenants/directory")
    public R<List<com.cangchu.tenant.vo.TenantDirectoryItemVo>> directory(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit,
            jakarta.servlet.http.HttpServletRequest request) {
        return R.ok(tenantService.directory(keyword, limit,
                com.cangchu.common.util.IpUtil.getClientIp(request)));
    }

    /** 查实时容量 */
    @GetMapping("/api/v1/tenant/capacity")
    public R<CapacityVo> getCapacity(@RequestParam Long tenantId) {
        CapacityVo vo = tenantService.getCapacity(tenantId);
        return R.ok(vo);
    }

    // ==================== 员工注册码（TA 生成/管理 → 解锁 WK/ST 入驻） ====================

    /** TA 生成员工注册码（role 仅 WK/ST）。tenant_id 由登录态推导。 */
    @PostMapping("/api/v1/tenant/employee-invites")
    public R<EmployeeInviteVo> createEmployeeInvite(@Valid @RequestBody EmployeeInviteCreateDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.createEmployeeInvite(userId, dto));
    }

    /** 列出本租户的员工注册码 */
    @GetMapping("/api/v1/tenant/employee-invites")
    public R<List<EmployeeInviteVo>> listEmployeeInvites() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(tenantService.listEmployeeInvites(userId));
    }

    /** 作废某员工注册码 */
    @DeleteMapping("/api/v1/tenant/employee-invites/{id}")
    public R<Void> revokeEmployeeInvite(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        tenantService.revokeEmployeeInvite(userId, id);
        return R.ok();
    }
}
