package com.cangchu.document.service;

import com.cangchu.document.dto.ConfirmInquiryDto;
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
 *       PENDING→CONFIRMED；遍历 items 生成出库单（P3 BE-W2 起 PENDING_ACCEPT，12 §1.4）+
 *       调 inventoryService.deductStock 扣库存（确认即扣不动）。<b>任一 item 库存不足 → 整个事务回滚</b>（S5）。
 *       确认后停在 CONFIRMED；名下全部出库单登记出库后由出库链联动迁 COMPLETED。</li>
 *   <li>{@link #voidByWa}：R8 已确认意向单作废（P3 BE-W2，12 §3.2）。</li>
 * </ul>
 */
public interface InquiryService {

    /** RT 提交询价（公开端点，单事务）。 */
    InquiryVo submitByRt(SubmitInquiryDto dto);

    /**
     * WA 确认询价 → 自动转出库扣库存（编排单事务，库存不足整体回滚）。
     *
     * <p>P2 定价 Wave 3a：dto 可空（无请求体沿用 phase-1 成交价=公开价快照）。dto.items 逐条覆盖成交价；
     * dto.settleAsCustomerPrice=true 时，凡成交价≠提交时公开价快照的明细在同一事务内沉淀为客户专属价。
     */
    InquiryVo confirmByWa(Long inquiryId, ConfirmInquiryDto dto, Long waUserId);

    /** 列出某租户下的询价单（WA 视角，按归属 wholesaler 过滤）。 */
    List<InquiryVo> listForWa(Long tenantId, Long waUserId);

    /**
     * R8 已确认意向单作废（P3 BE-W2，12 §3.2，WA 发起，单事务）：
     * 前置——询价 CONFIRMED 且名下出库单均非 COMPLETED/COMPLAINED（否则 50337）；
     * 询价 CAS CONFIRMED→VOIDED（voided_at）→ 名下 PENDING_ACCEPT/PRINTED 出库单逐张
     * CAS→CANCELLED + reverseOutbound 回补（每张一条 OUTBOUND_REVERSAL 配对）→ 通知 WK。
     * 已打印单在 R8 下不需 WK 二次确认（作废是整单意思表示），但通知 WK 收回纸单。
     */
    InquiryVo voidByWa(Long inquiryId, Long waUserId);

    /**
     * 未结单据计数（P2 入驻 Wave2 R13 前置校验出口，P3 BE-W2 扩展 12 §8.2）：
     * 询价单 status IN (PENDING, CONFIRMED) + 出库单 status IN (PENDING_ACCEPT, PRINTED, COMPLAINED)。
     * （入库单/仲裁单未结数由 InboundRequestService / ArbitrationService 各自出口提供。）
     *
     * <p>供 tenant 域退驻链调用（tenant 域不直连 document Mapper，G-S1/G-S2）。
     * 计数 &gt; 0 时调用方拒绝退驻（50314）。
     */
    long countOpenDocsForWholesaler(Long wholesalerId);
}
