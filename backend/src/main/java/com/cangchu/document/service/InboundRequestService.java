package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundForwardRegisterDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.dto.InboundRejectDto;
import com.cangchu.document.dto.InboundSubmitDto;
import com.cangchu.document.dto.InboundWithdrawDto;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;
import com.cangchu.document.vo.InboundStockPreviewVo;

import java.util.List;

/**
 * 入库单服务（P3 BE-W1 改造，12 §2：代建 72h 确认链）。
 *
 * <p>WK 登记单事务内 生成 docNo → 建单 PENDING_WA_CONFIRM（72h deadline 显式落列，可售不冻结）
 * → addStock 增库存/写 INBOUND 流水 → 通知归属 WA。WA 侧 confirm/dispute，超时 Job 自动确认。
 */
public interface InboundRequestService {

    /**
     * WK 登记入库（单事务）。
     *
     * @param dto      入库登记参数（wholesalerId/skuId/qty/palletQty?）
     * @param wkUserId 操作人（WK）user id（来自登录态，由 Controller 传入）
     * @return 入库单 VO（含 docNo + 登记后最新库存）
     */
    InboundRequestVo registerByWk(InboundRegisterDto dto, Long wkUserId);

    /**
     * WA/被授权 WE 确认代建入库（12 §2.3）。CAS PENDING_WA_CONFIRM→CONFIRMED
     * （wa_confirm_at=now, auto_accepted=0）；已自动确认 → 50332，其余并发被抢占 → 50331。
     */
    InboundRequestVo confirmByWa(Long inboundId, Long userId);

    /**
     * WA/被授权 WE 异议（12 §2.3，单事务）：CAS PENDING_WA_CONFIRM→DISPUTED →
     * 封顶冲销（12 §2.4）→ 建 YY- 仲裁单（快照 reversed/shortfall）→ 通知 TA+WK。
     */
    InboundDisputeResultVo disputeByWa(Long inboundId, Long userId, InboundDisputeDto dto);

    /**
     * WA 侧入库单队列（12 §6.1；P3b T1 扩 source 过滤，13 §5.1）：按「我管的商户」（WA∪WE）过滤；
     * status=PENDING_WA_CONFIRM 时按 deadline 升序（倒计时队列），否则按创建时间倒序。
     *
     * @param source 可空；WA_SUBMIT / WK_CREATED 过滤
     */
    Page<InboundRequestVo> listForWa(Long userId, String status, String source, int page, int size);

    /**
     * 72h 自动确认任务体（12 §2.5，供 {@code InboundAutoConfirmJob} 与测试直驱）：
     * 扫描 PENDING_WA_CONFIRM 且 deadline≤数据库 NOW() 的单，逐行 CAS 迁 CONFIRMED
     * （auto_accepted=1）并通知 WA。与手动操作竞态由 CAS 决出唯一赢家，天然幂等。
     *
     * @return 本次自动确认单数
     */
    int autoConfirmExpired();

    /**
     * 列出本租户入库单（按创建时间倒序）。tenantId 由调用方从登录态推导后传入。
     *
     * @param tenantId     租户 id
     * @param wholesalerId 可空；非空则只列该商户的入库单
     */
    List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId);

    /**
     * 列出本租户入库单（P3b T1 扩 status 过滤：status=SUBMITTED 即 WK 待受理队列，13 §5.1）。
     * SUBMITTED 队列按创建时间升序（先到先受理），其余按创建时间倒序。
     */
    List<InboundRequestVo> listByTenant(Long tenantId, Long wholesalerId, String status);

    /**
     * M3（PRD 09 §6.2）：异议前在库预览——WA 提交异议前展示实时在库/预计冲销/预计差额三数字。
     * 轻量只读，无锁语义要求（允许轻微过期）；实际冲销以 disputeByWa 锁内计算为准。
     *
     * @param inboundId 入库单 id（须属当前 WA 管辖商户，否则 50330/50334）
     * @param userId    操作人（WA 或持 INBOUND_CONFIRM 的 WE）
     */
    InboundStockPreviewVo stockPreview(Long inboundId, Long userId);

    /**
     * R13 退驻前置出口（P3 BE-W2，12 §8.2；P3b T1 扩 13 §7.1）：该商户未结入库单数——
     * status IN (PENDING_WA_CONFIRM, DISPUTED, SUBMITTED, ACCEPTED) + 在途纠错 PENDING。
     * CONFIRMED/REVOKED/WITHDRAWN/REJECTED 视为已结。
     * 供 tenant 域退驻链调用（不直连 document Mapper，G-S1/G-S2）。
     */
    long countOpenForWholesaler(Long wholesalerId);

    // ==================== P3b T1 正向申请链（13 §1/§5.1） ====================

    /**
     * WA/持 INBOUND_SUBMIT 的 WE 提交入库申请（单事务，D-5 多行拆单）：
     * 每行建一张 SUBMITTED/source=WA_SUBMIT 单（requested_qty=qty 落值后不可变，
     * wa_confirm_deadline 恒 NULL → 72h Job 天然不命中），共享 batch_submit_id；
     * 全程零库存/零流水/零计费；R14 前置 requireWholesalerActive（50313）；通知库管。
     *
     * @return 拆单后的入库单列表（与 items 同序）
     */
    List<InboundRequestVo> submitByWa(InboundSubmitDto dto, Long userId);

    /**
     * R1 撤回（WA/持 INBOUND_SUBMIT 的 WE，WE 仅可撤自己提交的）：仅 SUBMITTED 可撤
     * （其余状态 50350），CAS SUBMITTED→WITHDRAWN + withdraw_reason 必填。零库存影响。
     */
    InboundRequestVo withdrawByWa(Long inboundId, InboundWithdrawDto dto, Long userId);

    /**
     * WK 受理（锁单防撤回）：CAS SUBMITTED→ACCEPTED；R14——商户非 ACTIVE 时存量
     * SUBMITTED 单受理同样拒绝（50313，货未入仓不属保护客户权益）。零库存影响；通知商户。
     */
    InboundRequestVo acceptByWk(Long inboundId, Long userId);

    /**
     * R2 驳回（WK）：CAS SUBMITTED→REJECTED，reason 单选 + remark 必填 + 举证附件 ≤5
     * （N2 白名单，独立 reject_attachments 列）。零库存影响；通知商户（可一键复制重建）。
     */
    InboundRequestVo rejectByWk(Long inboundId, InboundRejectDto dto, Long userId);

    /**
     * WK 登记（单事务，13 §1.1 登记事务口径）：CAS ACCEPTED→CONFIRMED（D-3 直落，
     * extraSet qty=实登、registered_at、attachments）→ addStock（INBOUND 流水，
     * biz_time=登记时刻，计费次日起算）→ 通知商户。5% 差异边界（50351，含等于放行）；
     * 差异非 0 时 remark 必填。
     */
    InboundRequestVo registerForwardByWk(Long inboundId, InboundForwardRegisterDto dto, Long userId);

    /**
     * 打印（T1-7，非状态节点）：printed_at=now、print_count++；登记前后均可调（补打）。
     */
    InboundRequestVo printByWk(Long inboundId, Long userId);
}
