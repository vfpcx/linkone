package com.cangchu.inventory.service;

import com.cangchu.inventory.dto.DisputeReversalResult;
import com.cangchu.inventory.dto.DisputeRestoreContext;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.InboundDisputeContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.dto.OutboundReversalContext;
import com.cangchu.inventory.dto.InboundCorrectionContext;
import com.cangchu.inventory.dto.InboundCorrectionResult;
import com.cangchu.inventory.vo.InventoryVo;

import java.util.List;

/**
 * 库存服务（phase-1 B1：批次关闭，单 sku 维度）。
 *
 * <p>phase-1 子集：addStock / deductStock / queryInventory / listInStockSkusFor / assertStockEnough。
 * 不做 FIFO / 批次 / 临期 / 盘点 / 退货 / 快照。入/出库由 C document 单据调用本 service，
 * 不暴露公开的加/扣库存 HTTP（避免绕过单据）。
 */
public interface InventoryService {

    /**
     * 入库：在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行 upsert 增量 + 写 INBOUND 流水。
     * 与出库对称加锁，防并发首入撞唯一索引 / 累加 lost-update。
     * @return 入库后该 sku 的最新库存
     */
    InventoryVo addStock(InboundContext ctx);

    /**
     * 入库事务体（内部用）：仅由 {@link #addStock} 在持有 Redisson 锁后经代理调用，
     * 以保证 {@code @Transactional} 生效。<b>勿直接调用</b>（绕过锁会有并发写冲突）。
     */
    InventoryVo doAddInTx(InboundContext ctx);

    /**
     * 出库：在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内单事务执行——
     * 校验库存足够（不足抛 STOCK_NOT_ENOUGH，且不产生流水）→ 扣减 → 写 OUTBOUND 流水。
     * @return 出库后该 sku 的最新库存
     */
    InventoryVo deductStock(OutboundContext ctx);

    /**
     * 出库事务体（内部用）：仅由 {@link #deductStock} 在持有 Redisson 锁后经代理调用，
     * 以保证 {@code @Transactional} 生效。<b>勿直接调用</b>（绕过锁会超卖）。
     */
    InventoryVo doDeductInTx(OutboundContext ctx);

    // ==================== P3 反向流水（12 §1.5 / §2.4 / §2.6） ====================

    /**
     * R4/R8 出库回补：锁内 {@code qty += ctx.qty}，写 OUTBOUND_REVERSAL 流水
     * （reversal_of_id=原 OUTBOUND 流水，biz_time=原出库 biz_time → P4 配对抵消，计费视同从未出库）。
     * @return 回补后最新库存
     */
    InventoryVo reverseOutbound(OutboundReversalContext ctx);

    /** 出库回补事务体（内部用）：仅由 {@link #reverseOutbound} 持锁后经代理调用。<b>勿直接调用</b>。 */
    InventoryVo doReverseOutboundInTx(OutboundReversalContext ctx);

    /**
     * 异议冲销：锁内按剩余在库封顶（12 §2.4 精确口径：reversedQty=min(Q, max(onhand,0))，
     * 锁内消灭「计算→扣减」TOCTOU 间隙），写 DISPUTE_REVERSAL（biz_time=异议时刻）；
     * reversedQty=0（售罄）时不写流水。托盘按比例、双重封顶，冲销托盘数落流水 remark 供仲裁恢复还原。
     * @return 实际冲销/差额/托盘/剩余在库
     */
    DisputeReversalResult reverseInboundForDispute(InboundDisputeContext ctx);

    /** 异议冲销事务体（内部用）：仅由 {@link #reverseInboundForDispute} 持锁后经代理调用。<b>勿直接调用</b>。 */
    DisputeReversalResult doReverseInboundForDisputeInTx(InboundDisputeContext ctx);

    /**
     * 仲裁通过恢复：锁内 {@code qty += ctx.qty(=reversedQty)}，写 DISPUTE_RESTORE 流水
     * （biz_time=原入库时间戳 G10；reversal_of_id 回指配对 DISPUTE_REVERSAL；冲销托盘一并还原）。
     * ctx.qty<=0（售罄 0 冲销的 APPROVED）时为无操作，不写流水。
     * @return 恢复后最新库存
     */
    InventoryVo restoreInboundAfterArbitration(DisputeRestoreContext ctx);

    /** 仲裁恢复事务体（内部用）：仅由 {@link #restoreInboundAfterArbitration} 持锁后经代理调用。<b>勿直接调用</b>。 */
    InventoryVo doRestoreInboundInTx(DisputeRestoreContext ctx);

    /**
     * R3 登记纠错联动（P3b T1-BE，13 §1.3）：锁内单事务——
     * delta&gt;0 改大：qty += delta，写 CORRECTION_IN 流水；
     * delta&lt;0 改小：applied = min(|delta|, max(onhand,0))（12 §2.4 封顶），qty −= applied，
     * 写 CORRECTION_OUT 流水（applied=0 不写流水）；shortfall 由调用方写纠错单备注定责。
     * 两类流水 biz_time=原 INBOUND biz_time、reversal_of_id=原 INBOUND 流水 id（refDocNo 锁内解析）；
     * 托盘 ±ceil(原入库 pallet × applied / 原登记 qty)，释放侧对在库托盘二次封顶。
     */
    InboundCorrectionResult applyInboundCorrection(InboundCorrectionContext ctx);

    /** 纠错联动事务体（内部用）：仅由 {@link #applyInboundCorrection} 持锁后经代理调用。<b>勿直接调用</b>。 */
    InboundCorrectionResult doApplyInboundCorrectionInTx(InboundCorrectionContext ctx);

    /**
     * 退货登记联动（P3b T3-W1，13 §2.1：D-7 登记时扣）：锁内单事务——
     * 在库不足抛 STOCK_NOT_ENOUGH（不写流水，调用方事务整体回滚）；足则 qty −= n、
     * 托盘按 13 §2.4-2 释放（默认 ceil(池 pallet × n / 池 qty)，WK 覆盖含 0，
     * min(·, 在库托盘) 封顶不打负；全出清零：扣后 qty=0 → 默认释放全部托盘），
     * 写 RETURN 流水（biz_time=登记日=计费当日截止锚点，pallet_delta=−释放数）。
     */
    com.cangchu.inventory.dto.ReturnStockResult returnStock(com.cangchu.inventory.dto.ReturnStockContext ctx);

    /** 退货登记事务体（内部用）：仅由 {@link #returnStock} 持锁后经代理调用。<b>勿直接调用</b>。 */
    com.cangchu.inventory.dto.ReturnStockResult doReturnStockInTx(com.cangchu.inventory.dto.ReturnStockContext ctx);

    /**
     * 出库登记托盘释放（P3b T3-W1，13 §2.4-3：D-8=A 出库处补齐）：锁内单事务——
     * 默认建议值分母取「变动前在库」=当前池 qty + docQty（件数创建时已扣）；WK 覆盖含 0；
     * min(·, 在库托盘) 封顶。释放>0 时 pallet_qty −= n 并写 PALLET_RELEASE 流水
     * （qty=0 恒定不进件数公式、pallet_delta=−n、biz_time=登记时刻）；释放=0 不写流水。
     * R4/R8 撤回只发生在 COMPLETED 之前 → 从未释放 → 无需托盘回补路径（对称性红利）。
     *
     * @return 实际释放托盘数（≥0）
     */
    int releaseOutboundPallet(com.cangchu.inventory.dto.PalletReleaseContext ctx);

    /** 出库托盘释放事务体（内部用）：仅由 {@link #releaseOutboundPallet} 持锁后经代理调用。<b>勿直接调用</b>。 */
    int doReleaseOutboundPalletInTx(com.cangchu.inventory.dto.PalletReleaseContext ctx);

    /**
     * 盘盈联动（P3b T3-W2，13 §2.2：审批通过逐 SKU 锁内）：qty += diff（无行 upsert 建行，
     * addStock 同构——盘出账外货），写 GAIN 流水（biz_time=审批通过日，盘盈次日起算锚点；
     * pallet_delta=+M 可选）。盘盈并入 SKU 池（US-TA-12 无批次期口径）。
     */
    InventoryVo gainStock(com.cangchu.inventory.dto.GainStockContext ctx);

    /** 盘盈事务体（内部用）：仅由 {@link #gainStock} 持锁后经代理调用。<b>勿直接调用</b>。 */
    InventoryVo doGainStockInTx(com.cangchu.inventory.dto.GainStockContext ctx);

    /**
     * 盘亏联动（P3b T3-W2，13 §2.2：D-10 封顶）：applied = min(qty, max(onhand,0))
     * （onhand=审批时刻锁内重读，非提交快照——G9）；applied&gt;0 写 LOSS 流水
     * （biz_time=审批通过日计费当日截止；pallet_delta=−释放，默认比例/WK 覆盖双重封顶不打负）；
     * applied=0（售罄）零冲销不写流水。shortfall 由调用方写备注+通知定责，禁止打负。
     */
    com.cangchu.inventory.dto.LossStockResult lossStock(com.cangchu.inventory.dto.LossStockContext ctx);

    /** 盘亏事务体（内部用）：仅由 {@link #lossStock} 持锁后经代理调用。<b>勿直接调用</b>。 */
    com.cangchu.inventory.dto.LossStockResult doLossStockInTx(com.cangchu.inventory.dto.LossStockContext ctx);

    /**
     * 断言库存足够；不足抛 STOCK_NOT_ENOUGH，库存行不存在抛 INVENTORY_NOT_FOUND。
     * 只读校验，不加锁（真正扣减以 deductStock 锁内再校验为准）。
     */
    void assertStockEnough(Long wholesalerId, Long skuId, int qty);

    /**
     * 查询库存。wholesalerId / skuId 均可空作过滤；二者皆空返回当前可见范围全部。
     * 受 TenantLine 兜底隔离（inventories 在白名单内）。
     */
    List<InventoryVo> queryInventory(Long wholesalerId, Long skuId);

    /**
     * 列出某商户当前有货（qty>0）的库存（供 B2 store-front 聚合在售）。
     */
    List<InventoryVo> listInStockSkusFor(Long wholesalerId);
}
