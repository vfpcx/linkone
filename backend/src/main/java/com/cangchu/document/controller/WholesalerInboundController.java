package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WA 侧入库单 Controller（P3 BE-W1，12 §2.3/§6.1）。
 * 路径 /api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段（P2 Wave1）。
 * 归属/授权校验（WA 本人或持 INBOUND_CONFIRM 的 WE）在 Service 内以 user_roles 登录态推导。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/inbound-requests")
public class WholesalerInboundController {

    private final InboundRequestService inboundRequestService;

    /** 我的入库单队列（status=PENDING_WA_CONFIRM 时按 72h 倒计时升序）。 */
    @GetMapping
    public R<Page<InboundRequestVo>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(inboundRequestService.listForWa(StpUtil.getLoginIdAsLong(), status, page, size));
    }

    /** 确认代建入库（CAS；超窗已自动确认 → 50332）。 */
    @PostMapping("/{id}/confirm")
    public R<InboundRequestVo> confirm(@PathVariable Long id) {
        return R.ok(inboundRequestService.confirmByWa(id, StpUtil.getLoginIdAsLong()));
    }

    /** 异议（单事务：CAS→封顶冲销→建 YY- 仲裁单→通知 TA/WK）。 */
    @PostMapping("/{id}/dispute")
    public R<InboundDisputeResultVo> dispute(@PathVariable Long id,
                                             @Valid @RequestBody InboundDisputeDto dto) {
        return R.ok(inboundRequestService.disputeByWa(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
