package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.common.response.R;
import com.cangchu.document.dto.ReturnCreateDto;
import com.cangchu.document.dto.ReturnWithdrawDto;
import com.cangchu.document.service.ReturnRequestService;
import com.cangchu.document.vo.ReturnRequestVo;
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
 * 商户侧退货单 Controller（P3b T3-W1，13 §5.2）。
 * 路径 /api/v1/wholesaler/** 已在 SaTokenConfig checkLogin 段；
 * 归属校验（D-9：发起/撤回仅 WA，WE 只读列表）在 Service 内以 user_roles 登录态推导。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wholesaler/return-requests")
public class WholesalerReturnController {

    private final ReturnRequestService returnRequestService;

    /** WA 发起退货申请（D-7：提交零库存，登记出货前货仍可售；软校验在库，超量拒绝）。 */
    @PostMapping
    public R<ReturnRequestVo> create(@Valid @RequestBody ReturnCreateDto dto) {
        return R.ok(returnRequestService.createByWa(dto, StpUtil.getLoginIdAsLong()));
    }

    /** 我的退货单列表（WA 全量 / WE 只读；status 可选过滤）。 */
    @GetMapping
    public R<Page<ReturnRequestVo>> list(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(returnRequestService.listForWa(StpUtil.getLoginIdAsLong(), status, page, size));
    }

    /** WA 撤回（仅待受理，reason 必填；受理后 50330 须走仓库流转）。 */
    @PostMapping("/{id}/withdraw")
    public R<ReturnRequestVo> withdraw(@PathVariable Long id,
                                       @Valid @RequestBody ReturnWithdrawDto dto) {
        return R.ok(returnRequestService.withdrawByWa(id, dto, StpUtil.getLoginIdAsLong()));
    }
}
