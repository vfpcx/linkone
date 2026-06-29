package com.cangchu.document.service;

import com.cangchu.document.dto.SubmitInquiryDto;
import com.cangchu.document.vo.InquiryVo;

import java.util.List;

/**
 * 询价服务（phase-1 C2：RT 提交询价 → WA 确认 → 自动转出库扣库存）。
 *
 * <p>交易闭环核心：
 * <ul>
 *   <li>{@link #submitByRt}：RT 进店提交询价（公开端点，无登录态）。单事务内解析 store→tenant、
 *       校验 wholesaler 属该店、sku 属该 wholesaler，建 inquiry_requests(PENDING) + inquiry_items(价格快照)。
 *       tenantId 由 store 解析推导，<b>不取客户端</b>（G-2.1）。</li>
 *   <li>{@link #confirmByWa}：WA 确认（编排单事务）。校验 WA 归属该 inquiry 的 wholesaler；
 *       PENDING→CONFIRMED；遍历 items 生成出库单 + 调 inventoryService.deductStock 扣库存；
 *       全部成功后 CONFIRMED→COMPLETED。<b>任一 item 库存不足 → 整个事务回滚</b>（S5）。</li>
 * </ul>
 */
public interface InquiryService {

    /** RT 提交询价（公开端点，单事务）。 */
    InquiryVo submitByRt(SubmitInquiryDto dto);

    /** WA 确认询价 → 自动转出库扣库存（编排单事务，库存不足整体回滚）。 */
    InquiryVo confirmByWa(Long inquiryId, Long waUserId);

    /** 列出某租户下的询价单（WA 视角，按归属 wholesaler 过滤）。 */
    List<InquiryVo> listForWa(Long tenantId, Long waUserId);
}
