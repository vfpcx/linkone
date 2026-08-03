package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.ClearanceCreateDto;
import com.cangchu.document.dto.ClearanceDecideDto;
import com.cangchu.document.dto.ClearanceUpdateDto;
import com.cangchu.document.service.ClearanceRequestService;
import com.cangchu.document.vo.ClearanceRequestVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户侧清库单 Controller（P3b T4-W2，13 §5.3：QK- 全链）。
 * 鉴权（WK 作业 / TA 审批 / WK·TA 查看）在 Service 内以 user_roles 登录态推导；
 * tenantId 取登录态推导的可信租户（不信任客户端传参）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/clearance-requests")
public class TenantClearanceController {

    private final ClearanceRequestService clearanceRequestService;

    /**
     * WK 建草稿（一单一批次）：{batchId, qty(现场核数，空=推算剩余), reason, reasonRemark?,
     * palletRelease?, attachments 必填 ≥1 ≤3, remark?}；前置 50365、照片 50366。
     */
    @PostMapping
    public R<ClearanceRequestVo> create(@RequestBody ClearanceCreateDto dto) {
        return R.ok(clearanceRequestService.createByWk(dto, requireTenantId(), StpUtil.getLoginIdAsLong()));
    }

    /** WK 编辑（DRAFT 直接改 / REJECTED 改回 DRAFT 重提）。batchId 不可变。 */
    @PutMapping("/{id}")
    public R<ClearanceRequestVo> update(@PathVariable Long id, @RequestBody ClearanceUpdateDto dto) {
        return R.ok(clearanceRequestService.updateByWk(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** WK 删除草稿（仅 DRAFT；硬删并释放同批次在途唯一位）。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        clearanceRequestService.deleteByWk(id, StpUtil.getLoginIdAsLong());
        return R.ok(null);
    }

    /** WK 提交（CAS DRAFT→PENDING_APPROVAL）→ 通知租户管理员。 */
    @PostMapping("/{id}/submit")
    public R<ClearanceRequestVo> submit(@PathVariable Long id) {
        return R.ok(clearanceRequestService.submitByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** 列表（WK/TA；status=PENDING_APPROVAL 队列创建升序先到先审）。 */
    @GetMapping
    public R<List<ClearanceRequestVo>> list(@RequestParam(required = false) Long wholesalerId,
                                            @RequestParam(required = false) String status) {
        return R.ok(clearanceRequestService.listByTenant(
                requireTenantId(), StpUtil.getLoginIdAsLong(), wholesalerId, status));
    }

    /** 详情（WK/TA）：附批次只读信息 + currentStock/suggestedPalletRelease 封顶预览。 */
    @GetMapping("/{id}")
    public R<ClearanceRequestVo> detail(@PathVariable Long id) {
        return R.ok(clearanceRequestService.getDetail(id, StpUtil.getLoginIdAsLong()));
    }

    /**
     * TA 审批：{conclusion: APPROVED|REJECTED, remark(REJECTED 必填)}。
     * APPROVED 锁内 clearStock 封顶 + EXPIRY_CLEARANCE 流水 + 批次 CLEARED + 商户凭证通知。
     */
    @PostMapping("/{id}/decide")
    public R<ClearanceRequestVo> decide(@PathVariable Long id, @RequestBody ClearanceDecideDto dto) {
        return R.ok(clearanceRequestService.decideByTa(id, dto, StpUtil.getLoginIdAsLong()));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
