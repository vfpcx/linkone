package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.dto.InboundWithdrawDto;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.document.vo.InboundStockPreviewVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WA 侧入库单 Controller（P3 BE-W1，12 §2.3/§6.1；P3b T1 扩正向申请链，13 §5.1）。
 * 路径 /api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段（P2 Wave1）。
 * 归属/授权校验（WA 本人或持 INBOUND_CONFIRM / INBOUND_SUBMIT 的 WE）在 Service 内以 user_roles 登录态推导。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/inbound-requests")
public class WholesalerInboundController {

    private final InboundRequestService inboundRequestService;

    /**
     * P3b T1：WA/持 INBOUND_SUBMIT 的 WE 提交入库申请（D-5 多行拆 N 单，共享 batch_submit_id；
     * 单事务，任一行非法整批回滚；全程零库存——登记时才 addStock）。
     */
    @PostMapping
    public R<List<InboundRequestVo>> submit(@Valid @RequestBody InboundSubmitDto dto) {
        return R.ok(inboundRequestService.submitByWa(dto, StpUtil.getLoginIdAsLong()));
    }

    /** 我的入库单队列（status=PENDING_WA_CONFIRM 时按 72h 倒计时升序；P3b T1 扩 source 过滤）。 */
    @GetMapping
    public R<Page<InboundRequestVo>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String source,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(inboundRequestService.listForWa(StpUtil.getLoginIdAsLong(), status, source, page, size));
    }

    /** P3b T1 R1：撤回自己提交的入库申请（仅 SUBMITTED，受理后 50350；reason 必填）。 */
    @PostMapping("/{id}/withdraw")
    public R<InboundRequestVo> withdraw(@PathVariable Long id,
                                        @Valid @RequestBody InboundWithdrawDto dto) {
        return R.ok(inboundRequestService.withdrawByWa(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 确认代建入库（CAS；超窗已自动确认 → 50332）。 */
    @PostMapping("/{id}/confirm")
    public R<InboundRequestVo> confirm(@PathVariable Long id) {
        return R.ok(inboundRequestService.confirmByWa(id, StpUtil.getLoginIdAsLong()));
    }

    /** 异议前在库预览（M3，PRD 09 §6.2）：实时在库/预计冲销/预计差额三数字，供 WA 提交异议前展示。 */
    @GetMapping("/{id}/stock-preview")
    public R<InboundStockPreviewVo> stockPreview(@PathVariable Long id) {
        return R.ok(inboundRequestService.stockPreview(id, StpUtil.getLoginIdAsLong()));
    }

    /** 异议（单事务：CAS→封顶冲销→建 YY- 仲裁单→通知 TA/WK）。 */
    @PostMapping("/{id}/dispute")
    public R<InboundDisputeResultVo> dispute(@PathVariable Long id,
                                             @Valid @RequestBody InboundDisputeDto dto) {
        return R.ok(inboundRequestService.disputeByWa(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
