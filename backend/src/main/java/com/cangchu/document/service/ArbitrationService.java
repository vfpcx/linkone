package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.ArbitrationDecideDto;
import com.cangchu.document.entity.Arbitration;
import com.cangchu.document.entity.InboundRequest;
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
}
