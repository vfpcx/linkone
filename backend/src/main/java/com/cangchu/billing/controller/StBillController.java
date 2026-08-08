package com.cangchu.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.billing.dto.BillAdjustDto;
import com.cangchu.billing.dto.BillDisputeResolveDto;
import com.cangchu.billing.dto.BillGenerateDto;
import com.cangchu.billing.dto.BillReverseItemDto;
import com.cangchu.billing.dto.PaymentRegisterDto;
import com.cangchu.billing.dto.PaymentReverseDto;
import com.cangchu.billing.service.BillExportService;
import com.cangchu.billing.service.BillingService;
import com.cangchu.billing.vo.BillDetailVo;
import com.cangchu.billing.vo.BillDisputeVo;
import com.cangchu.billing.vo.BillGenerateResultVo;
import com.cangchu.billing.vo.BillListVo;
import com.cangchu.billing.vo.BillVo;
import com.cangchu.billing.vo.DailyBreakdownRowVo;
import com.cangchu.common.response.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ST 账单 Controller（P4 W3，14 §6.2；/api/v1/tenant/st/** 已在 SaTokenConfig checkLogin 段）。
 *
 * <p>鉴权（ST 或 TA 兼岗，WE 42004 / WK·WA 42001）在 Service 内以 user_roles 登录态推导
 * （W1/W2 gate 同构）。ST 不可见库存明细（05 §5.4）：详情下钻只读 daily_snapshots 聚合。
 */
@RestController
@RequiredArgsConstructor
public class StBillController {

    private final BillingService billingService;
    private final BillExportService billExportService;

    /** 列表 + 汇总卡（应收/已收/未收合计，按当前筛选）；倒序 */
    @GetMapping("/api/v1/tenant/st/bills")
    public R<BillListVo> list(@RequestParam(required = false) String month,
                              @RequestParam(required = false) Long wholesalerId,
                              @RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        return R.ok(billingService.listBills(StpUtil.getLoginIdAsLong(),
                month, wholesalerId, status, page, size));
    }

    /** 详情：三金额+状态+items 全量+payments+disputes；skuName 冗余带出（L-5） */
    @GetMapping("/api/v1/tenant/st/bills/{id}")
    public R<BillDetailVo> detail(@PathVariable Long id) {
        return R.ok(billingService.getBill(StpUtil.getLoginIdAsLong(), id));
    }

    /** 按日下钻（读 daily_snapshots 聚合，当段单价现算；不暴露实时库存） */
    @GetMapping("/api/v1/tenant/st/bills/{id}/daily-breakdown")
    public R<List<DailyBreakdownRowVo>> dailyBreakdown(@PathVariable Long id) {
        return R.ok(billingService.dailyBreakdown(StpUtil.getLoginIdAsLong(), id));
    }

    /**
     * 账单导出（P4 W5，US-ST-05 · D-P4-8=A）：format=pdf|excel，同步流式下载不落存储；
     * DRAFT 亦可导（预览稿水印「未下发预览稿」）；明细超 5000 行降级按货品聚合；
     * 文件名 RFC 5987 中文编码。
     */
    @GetMapping("/api/v1/tenant/st/bills/{id}/export")
    public void export(@PathVariable Long id, @RequestParam String format,
                       HttpServletResponse response) {
        billExportService.exportBill(StpUtil.getLoginIdAsLong(), id, format, response);
    }

    /** 手动补跑（幂等；无规则 50380；月份须已结束） */
    @PostMapping("/api/v1/tenant/st/bills/generate")
    public R<BillGenerateResultVo> generate(@RequestBody BillGenerateDto dto) {
        return R.ok(billingService.generateForTenant(StpUtil.getLoginIdAsLong(),
                dto != null ? dto.getMonth() : null));
    }

    /** 调整（US-ST-02，仅待核对） */
    @PostMapping("/api/v1/tenant/st/bills/{id}/adjust")
    public R<BillDetailVo> adjust(@PathVariable Long id, @RequestBody BillAdjustDto dto) {
        return R.ok(billingService.adjust(StpUtil.getLoginIdAsLong(), id, dto));
    }

    /** 冲销（R10，仅待核对；不删原条目） */
    @PostMapping("/api/v1/tenant/st/bills/{id}/reverse-item")
    public R<BillDetailVo> reverseItem(@PathVariable Long id, @RequestBody BillReverseItemDto dto) {
        return R.ok(billingService.reverseItem(StpUtil.getLoginIdAsLong(), id, dto));
    }

    /** 下发（US-ST-03） */
    @PostMapping("/api/v1/tenant/st/bills/{id}/dispatch")
    public R<BillVo> dispatch(@PathVariable Long id) {
        return R.ok(billingService.dispatch(StpUtil.getLoginIdAsLong(), id));
    }

    /** 撤回（R11：0 回款且未确认） */
    @PostMapping("/api/v1/tenant/st/bills/{id}/withdraw")
    public R<BillVo> withdraw(@PathVariable Long id) {
        return R.ok(billingService.withdraw(StpUtil.getLoginIdAsLong(), id));
    }

    /** 回款登记（US-ST-04，不允许超收） */
    @PostMapping("/api/v1/tenant/st/bills/{id}/payments")
    public R<BillDetailVo> registerPayment(@PathVariable Long id, @RequestBody PaymentRegisterDto dto) {
        return R.ok(billingService.registerPayment(StpUtil.getLoginIdAsLong(), id, dto));
    }

    /** 回款冲销（R12：理由必填 + 二次确认） */
    @PostMapping("/api/v1/tenant/st/payments/{id}/reverse")
    public R<BillDetailVo> reversePayment(@PathVariable Long id, @RequestBody PaymentReverseDto dto) {
        return R.ok(billingService.reversePayment(StpUtil.getLoginIdAsLong(), id, dto));
    }

    /** 申诉队列（PENDING 升序先到先处理） */
    @GetMapping("/api/v1/tenant/st/bill-disputes")
    public R<List<BillDisputeVo>> listDisputes(@RequestParam(required = false) String status) {
        return R.ok(billingService.listDisputes(StpUtil.getLoginIdAsLong(), status));
    }

    /** 申诉处理（结论=成立/不成立 + 说明必填） */
    @PostMapping("/api/v1/tenant/st/bill-disputes/{id}/resolve")
    public R<BillDisputeVo> resolveDispute(@PathVariable Long id, @RequestBody BillDisputeResolveDto dto) {
        return R.ok(billingService.resolveDispute(StpUtil.getLoginIdAsLong(), id, dto));
    }
}
