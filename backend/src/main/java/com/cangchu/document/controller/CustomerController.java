package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.response.R;
import com.cangchu.document.dto.CustomerReminderDto;
import com.cangchu.document.dto.CustomerRemarkDto;
import com.cangchu.document.service.CustomerFollowupService;
import com.cangchu.document.vo.CustomerDetailVo;
import com.cangchu.document.vo.CustomerListItemVo;
import com.cangchu.document.vo.FollowupReminderVo;
import jakarta.validation.Valid;
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

/**
 * wa 客户跟进（C3 · US-WE-04，24-p5-c-c3 §4，document 域；前缀同 InquiryController /api/v1/tenant）。
 *
 * <p>客户 = 当前工作空间 tenant + 登录人归属 wholesaler（WA 全部 + WE 只读全量，同 listForWa 口径）
 * 的询价买家（按 rt_phone_hmac 归并）。customerKey = URL-safe Base64(hmac)，wholesalerId 回传供收敛校验；
 * 越权一律 50840 假装不存在（K-7）。查全号复用 PII-REVEAL 既有链路（前端持 lastInquiryId 调 /pii/phone-reveal）。
 */
@RestController
@RequestMapping("/api/v1/tenant/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerFollowupService customerFollowupService;

    /** 客户列表（4.1）：分页，页内按最近询价倒序；仅回打码号。 */
    @GetMapping
    public R<Page<CustomerListItemVo>> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return R.ok(customerFollowupService.listCustomers(
                page, size, StpUtil.getLoginIdAsLong(), TenantContext.getTenantId()));
    }

    /** 客户详情（4.2）：统计 + 档案备注 + 全部提醒。 */
    @GetMapping("/{customerKey}/detail")
    public R<CustomerDetailVo> detail(@PathVariable String customerKey,
                                      @RequestParam Long wholesalerId) {
        return R.ok(customerFollowupService.detailCustomer(
                customerKey, wholesalerId, StpUtil.getLoginIdAsLong(), TenantContext.getTenantId()));
    }

    /** 备注覆盖保存（4.3）：remark 空串=清除（无提醒时清档）。 */
    @PutMapping("/{customerKey}/remark")
    public R<Void> saveRemark(@PathVariable String customerKey,
                              @Valid @RequestBody CustomerRemarkDto dto) {
        customerFollowupService.saveRemark(
                customerKey, dto, StpUtil.getLoginIdAsLong(), TenantContext.getTenantId());
        return R.ok();
    }

    /** 新建跟进提醒（4.4）：remindAt 须晚于 now（50841）；无档案自动建档。 */
    @PostMapping("/{customerKey}/reminders")
    public R<FollowupReminderVo> addReminder(@PathVariable String customerKey,
                                             @Valid @RequestBody CustomerReminderDto dto) {
        return R.ok(customerFollowupService.addReminder(
                customerKey, dto, StpUtil.getLoginIdAsLong(), TenantContext.getTenantId()));
    }

    /** 删除跟进提醒（4.5）。 */
    @DeleteMapping("/{customerKey}/reminders/{reminderId}")
    public R<Void> deleteReminder(@PathVariable String customerKey,
                                  @RequestParam Long wholesalerId,
                                  @PathVariable Long reminderId) {
        customerFollowupService.deleteReminder(
                customerKey, wholesalerId, reminderId, StpUtil.getLoginIdAsLong(), TenantContext.getTenantId());
        return R.ok();
    }
}
