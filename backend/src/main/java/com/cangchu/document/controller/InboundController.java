package com.cangchu.document.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.response.R;
import com.cangchu.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.InboundCorrectionCreateDto;
import com.cangchu.document.dto.InboundCorrectionDecideDto;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.InboundRejectDto;
import com.cangchu.document.service.InboundCorrectionService;
import com.cangchu.document.service.InboundRequestService;
import com.cangchu.document.vo.InboundCorrectionVo;
import com.cangchu.document.vo.InboundRequestVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 入库单 Controller（phase-1 C1：WK 登记入库 + 本租户入库单查询；
 * P3b T1 扩正向申请链受理/驳回/登记/打印 + R3 纠错，13 §5.1）。
 * 路径前缀 /api/v1/tenant/inbound，已被 SaInterceptor 登录拦截覆盖（G-1.1）。
 * 登记鉴权（该租户 WK / 纠错审批 TA）在 Service 内以 user_roles 登录态推导，不信任客户端传参；
 * tenantId 由 wholesaler 真实归属推导，列表查询取登录态推导的可信租户。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/inbound")
public class InboundController {

    private final InboundRequestService inboundRequestService;
    private final InboundCorrectionService inboundCorrectionService;

    /** WK 登记入库（代建链，单事务：建单 + 增库存）。 */
    @PostMapping
    public R<InboundRequestVo> register(@Valid @RequestBody InboundRegisterDto dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(inboundRequestService.registerByWk(dto, userId));
    }

    /** 列出本租户入库单（wholesalerId 可选过滤；P3b T1 扩 status：SUBMITTED=待受理队列升序）。 */
    @GetMapping
    public R<List<InboundRequestVo>> list(@RequestParam(required = false) Long wholesalerId,
                                          @RequestParam(required = false) String status) {
        return R.ok(inboundRequestService.listByTenant(requireTenantId(), wholesalerId, status));
    }

    // ==================== P3b T1 正向申请链（13 §5.1） ====================

    /** WK 受理（CAS SUBMITTED→ACCEPTED 锁单防撤回；商户非 ACTIVE → 50313）。 */
    @PostMapping("/{id}/accept")
    public R<InboundRequestVo> accept(@PathVariable Long id) {
        return R.ok(inboundRequestService.acceptByWk(id, StpUtil.getLoginIdAsLong()));
    }

    /** R2 驳回（reason 单选 + remark 必填 + 举证附件 ≤5；零库存影响）。 */
    @PostMapping("/{id}/reject")
    public R<InboundRequestVo> reject(@PathVariable Long id,
                                      @Valid @RequestBody InboundRejectDto dto) {
        return R.ok(inboundRequestService.rejectByWk(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** WK 登记正向链入库（CAS ACCEPTED→CONFIRMED + addStock；5% 差异边界 50351）。 */
    @PostMapping("/{id}/register")
    public R<InboundRequestVo> registerForward(@PathVariable Long id,
                                               @Valid @RequestBody InboundForwardRegisterDto dto) {
        return R.ok(inboundRequestService.registerForwardByWk(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 打印（T1-7，非状态节点）：printed_at/print_count++，登记前后均可（补打）。 */
    @PostMapping("/{id}/print")
    public R<InboundRequestVo> print(@PathVariable Long id) {
        return R.ok(inboundRequestService.printByWk(id, StpUtil.getLoginIdAsLong()));
    }

    // ==================== P3b T1 R3 登记纠错（13 §1.3/§5.1） ====================

    /** WK 发起纠错（登记后 ≤24h 50352；同单在途防重 50353；件数非法 50354）。 */
    @PostMapping("/{id}/corrections")
    public R<InboundCorrectionVo> createCorrection(@PathVariable Long id,
                                                   @Valid @RequestBody InboundCorrectionCreateDto dto) {
        return R.ok(inboundCorrectionService.create(id, dto, StpUtil.getLoginIdAsLong()));
    }

    /** 纠错列表（TA 审批中心 / WK 查看；status 可选过滤）。 */
    @GetMapping("/corrections")
    public R<Page<InboundCorrectionVo>> listCorrections(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(inboundCorrectionService.listByTenant(
                requireTenantId(), StpUtil.getLoginIdAsLong(), status, page, size));
    }

    /** TA 审批纠错（APPROVED → 13 §1.3 封顶事务；REJECTED → remark 必填）。 */
    @PostMapping("/corrections/{id}/decide")
    public R<InboundCorrectionVo> decideCorrection(@PathVariable Long id,
                                                   @Valid @RequestBody InboundCorrectionDecideDto dto) {
        return R.ok(inboundCorrectionService.decide(id, dto, StpUtil.getLoginIdAsLong()));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND, "未找到您的租户");
        }
        return tenantId;
    }
}
