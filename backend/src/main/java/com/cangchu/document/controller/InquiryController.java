package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.document.dto.ConfirmInquiryDto;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * WA 询价确认 Controller（phase-1 C2）。
 *
 * <p>路径前缀 {@code /api/v1/tenant/inquiry}，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 确认鉴权（该 wholesaler 的 WA）在 InquiryService 内以 user_roles 登录态推导，不信任客户端传参；
 * 列表查询取登录态推导的可信租户 + 该用户归属的 wholesaler。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/inquiry")
public class InquiryController {

    private final InquiryService inquiryService;

    /** WA 列出本人归属 wholesaler 的询价单。 */
    @GetMapping
    public R<List<InquiryVo>> list() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(inquiryService.listForWa(tenantId, userId));
    }

    /**
     * WA 确认询价 → 自动转出库扣库存（库存不足整体回滚）。
     *
     * <p>P2 定价 Wave 3a：请求体可空。dto.items 逐条议价、dto.settleAsCustomerPrice 议价沉淀。
     * 无请求体沿用 phase-1 行为（成交价=公开价快照，不沉淀）。
     */
    @PostMapping("/{id}/confirm")
    public R<InquiryVo> confirm(@PathVariable("id") Long id,
                               @RequestBody(required = false) ConfirmInquiryDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(inquiryService.confirmByWa(id, dto, userId));
    }

    /**
     * R8 已确认意向单作废（P3 BE-W2，12 §3.2，WA 发起）：
     * 前置询价 CONFIRMED 且名下无已出库单（否则 50337）；整单联动撤销出库 + 回补流水。
     */
    @PostMapping("/{id}/void")
    public R<InquiryVo> voidInquiry(@PathVariable("id") Long id) {
        return R.ok(inquiryService.voidByWa(id, StpUtil.getLoginIdAsLong()));
    }
}
