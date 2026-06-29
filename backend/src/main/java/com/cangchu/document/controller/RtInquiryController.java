package com.cangchu.document.controller;

import com.cangchu.common.response.R;
import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.service.InquiryService;
import com.cangchu.document.vo.InquiryVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT 提交询价 Controller（phase-1 C2 · 公开端点）。
 *
 * <p>路径前缀 {@code /api/v1/rt/**}：不在 SaInterceptor include 列表内，默认开放（与 B2 RtStoreController 同口径，G-1.2）。
 * RT 无登录态/无 TenantContext，tenantId 由 service 内 storeId/code→tenant 解析推导，<b>不</b>取客户端（防跨店）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rt")
public class RtInquiryController {

    private final InquiryService inquiryService;

    /** RT 提交询价（单事务：解析店铺 + 校验归属 + 建询价单 + 价格快照）。 */
    @PostMapping("/inquiry")
    public R<InquiryVo> submit(@Valid @RequestBody SubmitInquiryDto dto) {
        return R.ok(inquiryService.submitByRt(dto));
    }
}
