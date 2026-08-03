package com.cangchu.document.service;

import com.cangchu.document.dto.ClearanceCreateDto;
import com.cangchu.document.dto.ClearanceDecideDto;
import com.cangchu.document.dto.ClearanceUpdateDto;
import com.cangchu.document.vo.ClearanceRequestVo;

import java.util.List;

/**
 * 强制清库单服务（P3b T4-W2，13 §3.4/§5.3；QK-，状态机与盘点同构）。
 *
 * <p>链路：02:30 归零标记 PENDING_CLEARANCE → WK 从临期列表发起（一单一批次，现场核数）
 * → 提交 → TA 审批 → APPROVED 锁内 {@code clearStock} 封顶 + EXPIRY_CLEARANCE 流水
 * （batch_id 落值、biz_time=清库日仓储费当日截止、不计正常出库统计）+ 批次 CLEARED
 * + 商户凭证通知。R14 有意不接（存量库存治理，13 §3.6）。
 */
public interface ClearanceRequestService {

    /**
     * WK 建草稿：前置=批次 PENDING_CLEARANCE 且推算剩余&gt;0、同批次在途至多一张（违者 50365）；
     * qty 现场核数（null 默认=推算剩余，≤ 池当前在库 50251）；reason 三选（OTHER 备注必填）；
     * 实物照片 ≥1（50366）≤3；wholesalerId/skuId 随批次推导（S4）。
     */
    ClearanceRequestVo createByWk(ClearanceCreateDto dto, Long tenantId, Long userId);

    /** WK 编辑（DRAFT 直接改 / REJECTED 改回 DRAFT 重提——重提时复检批次仍待清理）。batchId 不可变。 */
    ClearanceRequestVo updateByWk(Long id, ClearanceUpdateDto dto, Long userId);

    /** WK 删除草稿（仅 DRAFT，硬删并释放同批次在途唯一位；盘点 DELETE 先例）。 */
    void deleteByWk(Long id, Long userId);

    /** WK 提交（CAS DRAFT→PENDING_APPROVAL）→ 通知租户管理员。 */
    ClearanceRequestVo submitByWk(Long id, Long userId);

    /** 列表（WK/TA；status=PENDING_APPROVAL 队列创建升序先到先审，其余倒序）。 */
    List<ClearanceRequestVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status);

    /** 详情（WK/TA）：附批次只读信息 + currentStock/suggestedPalletRelease 封顶预览。 */
    ClearanceRequestVo getDetail(Long id, Long userId);

    /**
     * TA 审批：APPROVED → 锁内 clearStock 封顶（applied=min(现场核数, 池在库)，差额写备注）
     * + EXPIRY_CLEARANCE 流水 + 批次 remaining 清零转 CLEARED + 商户凭证通知（含照片）+ 库管结论通知；
     * REJECTED → remark 必填，零库存零流水，保留记录可重提。
     */
    ClearanceRequestVo decideByTa(Long id, ClearanceDecideDto dto, Long userId);

    /** R13 未结计数（13 §7.1 终版枚举）：DRAFT/PENDING_APPROVAL 在途。 */
    long countOpenForWholesaler(Long wholesalerId);

    /** TA 临期看板「清库单待审批」卡（BatchController 编排合入，G-S1）。 */
    long countPendingApprovalForTenant(Long tenantId);
}
