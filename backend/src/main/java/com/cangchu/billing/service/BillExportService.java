package com.cangchu.billing.service;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 账单/对账单导出服务（P4 W5，14 §7 W5 / PRD 13-p4 §6，D-P4-8=A 同步流式不落存储）。
 *
 * <p>鉴权 requireStOrTa（W1-W3 gate 同构）；预览稿（DRAFT）水印/标记；明细超 5000 行
 * 降级按货品聚合；导出审计走结构化操作日志（谁何时导了哪张，actor_role=ST 先例）。
 */
public interface BillExportService {

    /** 明细降级护栏：超过该行数按货品聚合导出（D-P4-8） */
    int DETAIL_DEGRADE_THRESHOLD = 5000;

    /**
     * 账单导出（requireStOrTa）：format=pdf|excel，同步流式写响应；
     * 跨租户/不存在 50370；格式非法 40101。
     */
    void exportBill(Long userId, Long billId, String format, HttpServletResponse response);

    /**
     * 对账单导出（requireStOrTa）：某商户某月按日明细 Excel；
     * 商户跨租户/不存在 50230；月份格式非法 40101。
     */
    void exportSnapshots(Long userId, Long wholesalerId, String month, HttpServletResponse response);
}
