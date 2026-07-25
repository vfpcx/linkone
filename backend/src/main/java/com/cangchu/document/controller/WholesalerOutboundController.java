package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.document.dto.OutboundComplainDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.service.OutboundRequestService;
import com.cangchu.document.vo.OutboundRequestVo;
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
 * WA 侧出库单 Controller（P3 BE-W2，12 §3.1/§3.4/§6.1）。
 * 路径 /api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段（P2 Wave1）。
 * 归属鉴权（该 wholesaler 的 ACTIVE WA）在 Service 内以 user_roles 登录态推导。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/outbound-requests")
public class WholesalerOutboundController {

    private final OutboundRequestService outboundRequestService;

    /** WA 手动出库申请（提交即扣，PENDING_ACCEPT；不足 50251 整体回滚）。 */
    @PostMapping
    public R<OutboundRequestVo> submit(@Valid @RequestBody OutboundSubmitDto dto) {
        return R.ok(outboundRequestService.submitByWa(dto, StpUtil.getLoginIdAsLong()));
    }

    /** 我的出库单列表（status/source 可选过滤；「已确认（代建）」队列=source=WK_CREATED）。 */
    @GetMapping
    public R<Page<OutboundRequestVo>> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String source,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return R.ok(outboundRequestService.listForWa(StpUtil.getLoginIdAsLong(), status, source, page, size));
    }

    /** R4 撤回（分状态行为：待受理直撤 / 已打印置 flag 等 WK 二次确认 / 已出库 50335）。 */
    @PostMapping("/{id}/withdraw")
    public R<OutboundRequestVo> withdraw(@PathVariable Long id) {
        return R.ok(outboundRequestService.withdrawByWa(id, StpUtil.getLoginIdAsLong()));
    }

    /** 30 天客诉（仅代建出库；超窗 50339）：{reason, attachments?} → COMPLAINED + KS- 仲裁单。 */
    @PostMapping("/{id}/complain")
    public R<OutboundRequestVo> complain(@PathVariable Long id,
                                         @Valid @RequestBody OutboundComplainDto dto) {
        return R.ok(outboundRequestService.complainByWa(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
