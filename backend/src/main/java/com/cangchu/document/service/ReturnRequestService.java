package com.cangchu.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cangchu.document.dto.ReturnCreateDto;
import com.cangchu.document.dto.ReturnRegisterDto;
import com.cangchu.document.dto.ReturnWithdrawDto;
import com.cangchu.document.vo.ReturnRequestVo;

import java.util.List;

/**
 * 退货单服务（P3b T3-W1，13 §2.1/§5.2）。
 *
 * <p>核心口径：
 * <ul>
 *   <li><b>D-7 登记时扣</b>：提交/受理/撤回全程零库存零流水零计费（退货前货仍可售）；
 *       登记瞬间 returnStock 扣件数+释放托盘，不足抛 STOCK_NOT_ENOUGH(50251) 整体回滚。</li>
 *   <li><b>D-9</b>：发起/撤回仅 WA（WE 不开放，未开授权位，42004）；受理/登记=WK。</li>
 *   <li>通知两类（13 §5.3）：发起 → 库管（RETURN_CREATED）；登记完成 → 商户（RETURN_COMPLETED，
 *       含实退件数与释放托盘）。收件人以 user_roles 推导（先例），文案零角色码。</li>
 * </ul>
 */
public interface ReturnRequestService {

    /**
     * WA 发起退货申请（wholesalerId/tenantId 由 sku 真实归属推导；提交时 assertStockEnough
     * 软校验——仅拒超量提交，不锁定库存；退货不影响意向单流转）。
     */
    ReturnRequestVo createByWa(ReturnCreateDto dto, Long userId);

    /** WA 撤回（仅 PENDING_ACCEPT；reason 必填；受理后 → 50330）。 */
    ReturnRequestVo withdrawByWa(Long returnId, ReturnWithdrawDto dto, Long userId);

    /** 商户侧列表（WA 全量 / WE 只读，13 §5.2；status 可选过滤）。 */
    Page<ReturnRequestVo> listForWa(Long userId, String status, int page, int size);

    /** 租户侧受理队列/历史（WK/TA；PENDING_ACCEPT 按创建升序先到先受理，其余倒序）。 */
    List<ReturnRequestVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status);

    /** WK 受理（CAS PENDING_ACCEPT→ACCEPTED 锁单防撤回；仍不动库存）。 */
    ReturnRequestVo acceptByWk(Long returnId, Long userId);

    /**
     * WK 现场出货登记（CAS ACCEPTED→COMPLETED + returnStock 同事务；D-7 此刻才扣）。
     * actualQty 可按实覆写（remark 自动留痕）；palletRelease 覆盖默认建议值（含 0），封顶不打负。
     * 在库不足 → STOCK_NOT_ENOUGH(50251)，单据保持 ACCEPTED（联系商户改单，撤回后重提）。
     */
    ReturnRequestVo registerByWk(Long returnId, ReturnRegisterDto dto, Long userId);

    /** R13 未结计数（13 §7.1 终版枚举：退货 PENDING_ACCEPT/ACCEPTED 阻退驻）。 */
    long countOpenForWholesaler(Long wholesalerId);
}
