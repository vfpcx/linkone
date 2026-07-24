package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.InboundDisputeDto;
import com.cangchu.document.dto.InboundRegisterDto;
import com.cangchu.document.vo.InboundDisputeResultVo;
import com.cangchu.document.vo.InboundRequestVo;

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
     * WA 侧入库单队列（12 §6.1）：按「我管的商户」（WA∪WE）过滤；
     * status=PENDING_WA_CONFIRM 时按 deadline 升序（倒计时队列），否则按创建时间倒序。
     */
    Page<InboundRequestVo> listForWa(Long userId, String status, int page, int size);

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
}
