package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.CountSheetCreateDto;
import com.cangchu.document.dto.CountSheetDecideDto;
import com.cangchu.document.dto.CountSheetUpdateDto;
import com.cangchu.document.service.CountSheetService;
import com.cangchu.document.vo.CountSheetVo;
import com.cangchu.document.vo.StocktakeInTransitHintVo;
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
 * 租户侧盘点单 Controller（P3b T3-W2，13 §5.2：PD- 全链）。
 * 鉴权（WK 作业 / TA 审批 / WK·TA 查看）在 Service 内以 user_roles 登录态推导；
 * tenantId 取登录态推导的可信租户（不信任客户端传参）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/count-sheets")
public class TenantStocktakeController {

    private final CountSheetService countSheetService;

    /** WK 建草稿（同商户在途至多一张 50356；明细 50355；system_qty 预填当刻账面）。 */
    @PostMapping
    public R<CountSheetVo> create(@RequestBody CountSheetCreateDto dto) {
        return R.ok(countSheetService.createByWk(dto, StpUtil.getLoginIdAsLong()));
    }

    /** WK 编辑（DRAFT 直接改 / REJECTED 改回 DRAFT 重提）。items 全量替换。 */
    @PutMapping("/{id}")
    public R<CountSheetVo> update(@PathVariable Long id, @RequestBody CountSheetUpdateDto dto) {
        return R.ok(countSheetService.updateByWk(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** WK 删除草稿（仅 DRAFT；硬删并释放同商户在途唯一位）。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        countSheetService.deleteByWk(id, StpUtil.getLoginIdAsLong());
        return R.ok(null);
    }

    /** WK 提交（CAS DRAFT→PENDING_APPROVAL；system_qty 提交时刻快照定格）→ 通知 TA。 */
    @PostMapping("/{id}/submit")
    public R<CountSheetVo> submit(@PathVariable Long id) {
        return R.ok(countSheetService.submitByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** 列表（WK/TA；status=PENDING_APPROVAL 队列创建升序先到先审）。 */
    @GetMapping
    public R<List<CountSheetVo>> list(@RequestParam(required = false) Long wholesalerId,
                                      @RequestParam(required = false) String status) {
        return R.ok(countSheetService.listByTenant(
                requireTenantId(), StpUtil.getLoginIdAsLong(), wholesalerId, status));
    }

    /**
     * 在途提示条（盘点录入页/审批弹窗护栏，13 §2.2）：已确认未出库 N 张 M 件 +
     * 已受理未登记退货 X 张 Y 件，按 SKU 聚合。注意声明顺序在 /{id} 之前防路径吞并。
     */
    @GetMapping("/in-transit-hint")
    public R<StocktakeInTransitHintVo> inTransitHint(@RequestParam Long wholesalerId) {
        return R.ok(countSheetService.inTransitHint(
                requireTenantId(), StpUtil.getLoginIdAsLong(), wholesalerId));
    }

    /** 详情（WK/TA）：含明细（currentStock/suggestedPalletRelease 封顶预览）+ 在途提示条。 */
    @GetMapping("/{id}")
    public R<CountSheetVo> detail(@PathVariable Long id) {
        return R.ok(countSheetService.getDetail(id, StpUtil.getLoginIdAsLong()));
    }

    /**
     * TA 审批：{conclusion: APPROVED|REJECTED, remark(REJECTED 必填)}。
     * APPROVED 逐 SKU 锁内 GAIN/LOSS（盘亏 D-10 按审批时刻在库封顶，差额备注+通知）。
     */
    @PostMapping("/{id}/decide")
    public R<CountSheetVo> decide(@PathVariable Long id, @RequestBody CountSheetDecideDto dto) {
        return R.ok(countSheetService.decideByTa(id, dto, StpUtil.getLoginIdAsLong()));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
