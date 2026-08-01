package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.OutboundComplainDto;
import com.cangchu.document.dto.OutboundSubmitDto;
import com.cangchu.document.dto.WkOutboundCreateDto;
import com.cangchu.document.vo.OutboundRequestVo;

/**
 * 出库单服务（P3 BE-W2，12 §1/§3：状态机补拆 + R4/R8 异常链 + 代建出库 + 30 天客诉）。
 *
 * <p>库存不变量（拍板二 B）：扣减时点唯一且不动——INQUIRY_AUTO/WA_SUBMIT 在生成瞬间已扣、
 * WK_CREATED 在代建提交瞬间已扣；<b>任何</b> WITHDRAWN/CANCELLED 迁移必伴随一条
 * OUTBOUND_REVERSAL 回补流水（reversal_of_id 配对原 OUTBOUND）；
 * print/revert/register/complain/decide 均为纯作业/争议记录，不动库存。
 */
public interface OutboundRequestService {

    // ==================== WA 侧（/api/v1/wholesaler/outbound-requests） ====================

    /**
     * WA 手动出库申请（12 §1.3 WA_SUBMIT）：requireWholesalerActive(50313) + deductStock
     * （不足 STOCK_NOT_ENOUGH 整体回滚）+ 建单 PENDING_ACCEPT，单事务、提交即扣无超卖窗口。
     * 鉴权：操作人须为该 wholesaler 的 ACTIVE WA。
     */
    OutboundRequestVo submitByWa(OutboundSubmitDto dto, Long userId);

    /** WA 出库单列表（status/source 可选过滤；「已确认（代建）」队列=source=WK_CREATED）。 */
    Page<OutboundRequestVo> listForWa(Long userId, String status, String source, int page, int size);

    /**
     * R4 撤回（12 §3.1，分状态行为）：
     * <ul>
     *   <li>PENDING_ACCEPT：单事务 CAS→WITHDRAWN + reverseOutbound 回补 + 意向单联动 + 通知 WK；</li>
     *   <li>PRINTED：不迁移状态，置 withdraw_requested flag + 通知 WK 二次确认（重复申请 50331）；</li>
     *   <li>COMPLETED/COMPLAINED/终态：50335（已出库走退货 R5——T3 波）。</li>
     * </ul>
     */
    OutboundRequestVo withdrawByWa(Long outboundId, Long userId);

    /**
     * 30 天客诉（12 §3.4 / PRD 09 §3，WA 发起；WE 不开放——PRD 09 §5 权限矩阵）：
     * 前置 status=COMPLETED ∧ source=WK_CREATED（否则 50330）∧ completed_at ≥ now−30d（超窗 50339）；
     * 单事务：CAS COMPLETED→COMPLAINED + 建 KS- 仲裁单（一单一诉）+ 通知 WK。
     */
    OutboundRequestVo complainByWa(Long outboundId, Long userId, OutboundComplainDto dto);

    // ==================== WK/TA 侧（/api/v1/tenant/outbound-requests） ====================

    /** 本租户出库作业列表（WK/TA；status 可选过滤）。 */
    Page<OutboundRequestVo> listByTenant(Long tenantId, Long userId, String status, int page, int size);

    /** WK 打印：PENDING_ACCEPT→PRINTED（printed_at 首打、print_count++）；已打印补打 count++ 不迁移。 */
    OutboundRequestVo printByWk(Long outboundId, Long wkUserId);

    /** WK 重新核对回退：PRINTED→PENDING_ACCEPT（清撤回 flag，防悬挂）。 */
    OutboundRequestVo revertToPendingByWk(Long outboundId, Long wkUserId);

    /**
     * WK 登记出库：PRINTED→COMPLETED（completed_at=now，客诉窗口锚点）。
     * 询价终态联动（12 §1.4）：该询价名下全部出库单终态且至少一单 COMPLETED → 询价 CONFIRMED→COMPLETED。
     * 旧签名兼容：等价 {@code registerByWk(outboundId, null, wkUserId)}（托盘按默认建议值释放）。
     */
    OutboundRequestVo registerByWk(Long outboundId, Long wkUserId);

    /**
     * WK 登记出库（P3b T3-W1 改造，13 §2.4-3 / §5.2）：同上，且同事务追加出库托盘释放——
     * 件数创建即扣不变，托盘此刻经独立 PALLET_RELEASE 流水释放（qty=0、pallet_delta=−n）。
     * dto 可空/字段可空=默认建议值 ceil(池 pallet × 件数 / 变动前在库)；palletRelease 为 WK 覆盖
     * （含 0=托盘未腾空），落库前对在库托盘封顶不打负。
     */
    OutboundRequestVo registerByWk(Long outboundId, com.cangchu.document.dto.OutboundRegisterDto dto, Long wkUserId);

    /** WK 确认撤回（R4 已打印二次确认）：无 flag → 50336；CAS PRINTED→CANCELLED + 回补 + 联动 + 通知 WA。 */
    OutboundRequestVo confirmWithdrawByWk(Long outboundId, Long wkUserId);

    /** WK 拒绝撤回：无 flag → 50336；清 flag + 通知 WA，状态不变继续履约。 */
    OutboundRequestVo rejectWithdrawByWk(Long outboundId, Long wkUserId);

    /**
     * WK 代建出库（12 §3.3 US-WK-02b）：requireWholesalerActive + confirmed=true 凭据 +
     * 大额校验（qty > 在库×50% 须复述 restatedQty==qty，否则 50338）；
     * 单事务：deductStock + 建单 COMPLETED/WK_CREATED/completed_at=now + 通知归属 WA。
     */
    OutboundRequestVo createByWk(WkOutboundCreateDto dto, Long wkUserId);

    // ==================== 跨域出口（R13，12 §8.2） ====================

    /** 未结出库单数：PENDING_ACCEPT ∪ PRINTED ∪ COMPLAINED（终态与 COMPLETED 视为已结）。 */
    long countOpenForWholesaler(Long wholesalerId);
}
