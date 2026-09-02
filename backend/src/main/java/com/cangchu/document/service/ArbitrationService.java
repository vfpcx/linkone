package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundRequest;
import com.cangchu.document.entity.OutboundRequest;
import com.cangchu.document.vo.ArbitrationVo;

import java.util.List;

/**
 * 仲裁服务（P3 BE-W1，12 §2.6 / §4：入库异议 TA 仲裁最小闭环）。
 *
 * <p>结论枚举与副作用按 biz_type 注册表引擎化（12 §4.2）：新增仲裁类型只加注册项不改流程。
 * OUTBOUND_COMPLAINT（OPS 裁）注册项 BE-W2 补充。
 */
public interface ArbitrationService {

    /**
     * 建入库异议仲裁单（供 {@code InboundRequestService.disputeByWa} 同事务调用）。
     * doc_no=YY-（统一单据号体系）；reversed/shortfall 为异议时刻快照，落单后不可变。
     */
    Arbitration createInboundDispute(InboundRequest inbound, Long initiatorUserId, String initiatorRole,
                                     String reason, List<String> attachments,
                                     int reversedQty, int shortfallQty);

    /**
     * TA 仲裁列表（审批中心，12 §6.1）。tenantId=登录态推导；bizType/status 可空过滤。
     * 鉴权：操作人须为该租户 ACTIVE TA。
     */
    Page<ArbitrationVo> listForTa(Long tenantId, Long taUserId, String bizType, String status,
                                  int page, int size);

    /**
     * TA 裁决（12 §2.6，单事务）：
     * <ul>
     *   <li>结论校验：biz_type × conclusion 错配 / REJECTED 缺 remark → 50333；</li>
     *   <li>liability 三态校验（必填/必空/枚举非法）→ 50342；</li>
     *   <li>仲裁单 CAS PENDING→DECIDED（防并发双裁；不存在/已裁/跨租户按不存在 → 50334）；</li>
     *   <li>APPROVED=异议不成立：恢复流水（biz_time=原入库时间戳 G10）+ 入库单 DISPUTED→CONFIRMED；</li>
     *   <li>REJECTED=异议成立：保留冲销 + 入库单 DISPUTED→REVOKED；</li>
     *   <li>双方站内信（WA+WK）。</li>
     * </ul>
     */
    ArbitrationVo decideByTa(Long arbitrationId, Long taUserId, ArbitrationDecideDto dto);

    // ==================== P3 BE-W2：出库客诉 OPS 仲裁（12 §3.4 / PRD 09 §3） ====================

    /**
     * 建出库客诉仲裁单（供 {@code OutboundRequestService.complainByWa} 同事务调用）。
     * doc_no=KS-；无 reversed/shortfall（客诉仅判责，不动库存）。
     * 一单一诉（PRD 09 §1.1）：该出库单已存在任意仲裁单（含已裁决）→ 50330。
     */
    Arbitration createOutboundComplaint(OutboundRequest outbound, Long initiatorUserId, String initiatorRole,
                                        String reason, List<String> attachments);

    /**
     * OPS 客诉仲裁列表（跨租户，12 §3.4）。鉴权：操作人须为平台 OPS；
     * bizType 缺省/仅允许 OUTBOUND_COMPLAINT；status 可空过滤。
     */
    Page<ArbitrationVo> listForOps(Long opsUserId, String bizType, String status, int page, int size);

    /**
     * OPS 裁决（单事务，仅判责 D43）：结论四选 WK_LIABLE/WA_LIABLE/NEGOTIATED/NO_LIABILITY（错配 50333）；
     * remark 必填（PRD 09 §1.1 结论备注必填）；liability 不适用（传入 50342）；
     * 仲裁单 CAS PENDING→DECIDED（50334）+ 出库单 CAS COMPLAINED→COMPLETED（库存/流水/账单一概不动）
     * + 双方站内信（WA+WK）。
     */
    ArbitrationVo decideByOps(Long arbitrationId, Long opsUserId, ArbitrationDecideDto dto);

    /**
     * R13 退驻前置出口（12 §8.2）：该商户 PENDING 仲裁单数（争议中/客诉中商户不能退驻）。
     */
    long countPendingForWholesaler(Long wholesalerId);

    /**
     * TA 工作台「申诉处理」计数（P5-C dashboard，19 §3）：该租户全部 PENDING 仲裁单数。
     * 与审批中心角标同口径（listForTa 不分 bizType，含入库异议 + 出库客诉）。
     */
    long countPendingForTa(Long tenantId);

    // ==================== P5-C：OPS 控制台（21 §3，由 OpsDashboardService 统一 gate） ====================

    /**
     * OPS 控制台「客诉仲裁」待办计数：OUTBOUND_COMPLAINT 且 PENDING（与 listForOps 同口径）。
     * 平台级无租户维度。
     */
    long countPendingForOps();

    /**
     * OPS 控制台今日新增客诉计数：OUTBOUND_COMPLAINT 且 created_at ≥ 今日 0 点。
     */
    long countComplaintsCreatedToday();
}
