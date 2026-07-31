package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.InboundCorrectionCreateDto;
import com.cangchu.document.dto.InboundCorrectionDecideDto;
import com.cangchu.document.vo.InboundCorrectionVo;

/**
 * R3 登记纠错服务（P3b T1-BE，13 §1.3）。
 *
 * <p>适用 source=WA_SUBMIT ∧ status=CONFIRMED 的正向链单据；发起=WK（登记后 ≤24h，
 * SQL 内比数据库时间，超窗 50352；同单在途至多一张 50353），审批=TA 单级。
 * APPROVED 走 12 §2.4 封顶事务（CORRECTION_IN/OUT 配对流水，改小遇已售封顶+差额备注）。
 */
public interface InboundCorrectionService {

    /**
     * WK 发起纠错：new_qty ≥0 且 ≠当前实登（违者 50354）；24h 窗口（50352）；
     * 同单 PENDING 防重（50353，pending_flag 部分唯一兜底）。同事务通知租户管理员待审。
     */
    InboundCorrectionVo create(Long inboundId, InboundCorrectionCreateDto dto, Long wkUserId);

    /**
     * 纠错列表（TA 审批中心 / WK 查看）：本租户按 status 过滤，创建时间倒序。
     */
    Page<InboundCorrectionVo> listByTenant(Long tenantId, Long userId, String status, int page, int size);

    /**
     * TA 审批（单事务）：APPROVED → 13 §1.3 封顶事务（锁内 CORRECTION_IN/OUT +
     * inbound_requests.qty 覆写 new_qty，shortfall 写纠错单备注）；REJECTED → decide_remark
     * 必填，零库存影响。并发双裁由 CAS 决出唯一赢家（50331）。同事务通知发起库管。
     */
    InboundCorrectionVo decide(Long correctionId, InboundCorrectionDecideDto dto, Long taUserId);
}
