package com.cangchu.document.service;

import com.cangchu.document.dto.CountSheetCreateDto;
import com.cangchu.document.dto.CountSheetDecideDto;
import com.cangchu.document.dto.CountSheetUpdateDto;
import com.cangchu.document.vo.CountSheetVo;
import com.cangchu.document.vo.StocktakeInTransitHintVo;

import java.util.List;

/**
 * 盘点单服务（P3b T3-W2，13 §2.2/§5.2）。
 *
 * <p>口径：账面为准（system_qty=提交时刻快照，已扣后）+ 在途提示条护栏 + 盘亏封顶
 * （D-10：生效量以审批时刻锁内 onhand 封顶，差额备注+通知，不驳回重盘）。
 * 权限：建/编/提/删=WK；审批=TA；查看=WK/TA。零金额（GAIN/LOSS 仅留 biz_time/pallet_delta 锚点）。
 */
public interface CountSheetService {

    /** WK 建草稿（同商户在途至多一张 50356；明细校验 50355；system_qty 预填当刻账面）。 */
    CountSheetVo createByWk(CountSheetCreateDto dto, Long userId);

    /** WK 编辑（DRAFT 直接改；REJECTED 先 CAS 回 DRAFT 重提——pending_flag 回置撞唯一 50356）。items 全量替换。 */
    CountSheetVo updateByWk(Long sheetId, CountSheetUpdateDto dto, Long userId);

    /** WK 删除草稿（仅 DRAFT，PRD §2.2 状态机「草稿可删除」；硬删两表，释放 pending 唯一位）。 */
    void deleteByWk(Long sheetId, Long userId);

    /** WK 提交（CAS DRAFT→PENDING_APPROVAL；明细 system_qty/diff 以提交时刻重快照定格）→ 通知 TA。 */
    CountSheetVo submitByWk(Long sheetId, Long userId);

    /** 列表（WK/TA；status/wholesalerId 过滤；待审批队列创建升序先到先审）。不含明细。 */
    List<CountSheetVo> listByTenant(Long tenantId, Long userId, Long wholesalerId, String status);

    /** 详情（WK/TA）：含明细（currentStock/suggestedPalletRelease 封顶预览）+ 在途提示条。 */
    CountSheetVo getDetail(Long sheetId, Long userId);

    /** 在途提示条聚合（WK/TA；盘点录入页护栏，13 §2.2）。 */
    StocktakeInTransitHintVo inTransitHint(Long tenantId, Long userId, Long wholesalerId);

    /**
     * TA 审批（CAS PENDING_APPROVAL→APPROVED|REJECTED，并发双裁败方 50331）：
     * APPROVED=逐 SKU（skuId 升序防死锁）锁内 GAIN/LOSS（盘亏 D-10 封顶，差额写明细/单据备注
     * 并通知 TA+WK）；REJECTED=remark 必填、保留记录零库存零流水（可改回 DRAFT 重提）。
     */
    CountSheetVo decideByTa(Long sheetId, CountSheetDecideDto dto, Long userId);

    /** R13 未结计数（13 §7.1 终版枚举）：DRAFT/PENDING_APPROVAL 在途阻退驻。 */
    long countOpenForWholesaler(Long wholesalerId);

    /** TA 工作台「待审批盘点单」计数（P5-C dashboard 编排合入，G-S1：不跨域直连）。 */
    long countPendingApprovalForTenant(Long tenantId);
}
