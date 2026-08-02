package com.cangchu.inventory.service;

import com.cangchu.inventory.dto.BatchBackfillDto;
import com.cangchu.inventory.dto.BatchToggleDto;
import com.cangchu.inventory.dto.InboundBatchContext;
import com.cangchu.inventory.entity.Batch;
import com.cangchu.inventory.vo.BatchListVo;
import com.cangchu.inventory.vo.BatchRecalcResultVo;
import com.cangchu.inventory.vo.BatchToggleVo;

import java.util.List;

/**
 * 批次登记簿服务（P3b T4-W1，13 §3；D-11=C：批次登记 + FIFO 离线推算，交易路径零改动）。
 *
 * <p>铁律：本服务<b>不进出入库交易路径</b>——{@code InventoryService} 的 addStock/deductStock
 * 等对批次零感知；登记簿追加与流水 batch_id 回填均为单据事务内的<b>后置钩子</b>。
 */
public interface BatchService {

    /**
     * TA 批次开关专用端点（13 §3.5，D-13 禁改在通用设置接口保留）。
     * 24h ≤2 次（Redis 计数 {@code batch:toggle:{tenantId}}，超限 50361）；目标状态=当前状态时
     * 幂等空转（不计次、不生成默认批次、不动 batch_enabled_at）；实际翻转须 confirmed=true。
     * 关→启：batch_enabled_at=now + 为全部 in_stock>0 的 (w,sku) 生成默认批次 DEFAULT-{YYYYMMDD}
     * （initial_qty=当刻池 qty 快照；再启用生成新默认批次，同日冲突追加序号后缀，不复活 CLOSED）。
     * 启→关：全部非终态批次标 CLOSED（登记簿冻结）。
     */
    BatchToggleVo toggle(Long taUserId, BatchToggleDto dto);

    /** 批次号占用预检（提交时友好拦截，50362；登记 insert 撞 uk 为权威兜底）。 */
    void assertBatchNoAvailable(Long wholesalerId, Long skuId, String batchNo);

    /**
     * 入库登记后置钩子（正向/代建登记事务内、addStock 之后调用）：
     * 追加登记簿行（一批一行，uk 冲突 50362 整体回滚）+ 回填该单 INBOUND 流水 batch_id。
     * 初始状态：到效期−今日 ≤ 租户阈值 → EXPIRING（临期警告放行、立即进入临期列表），否则 IN_STOCK。
     */
    Batch registerInboundBatch(InboundBatchContext ctx);

    /**
     * R3 纠错流水批次标识回填（方案 C：CORRECTION_IN/OUT 落 batch_id 供 FIFO 直扣）。
     * 原入库单无批次或批次行不存在时静默跳过（不阻断纠错主链）。
     */
    void tagCorrectionMovement(Long movementId, Long wholesalerId, Long skuId, String batchNo);

    /**
     * 单租户 FIFO 离线推算（13 §3.2 六步；纯读流水、不加锁、幂等重跑）。
     * 覆写各批次 remaining_qty 并联动 IN_STOCK/EXPIRING/SOLD_OUT 三态（不触
     * PENDING_CLEARANCE/CLEARED/CLOSED）；批次功能未启用的租户空转返回。
     * <b>T4-W2 契约</b>：BatchRecalcJob（02:00）对每租户调用本方法（或 {@link #recalcAll}），
     * 用返回的 {@code newlyExpiringBatchIds} 发 BATCH_EXPIRING 首发通知并落 expiring_notified_at。
     */
    BatchRecalcResultVo recalcTenant(Long tenantId);

    /** 全平台推算：对全部 batch_enabled=1 租户逐个 {@link #recalcTenant}（单租户失败记日志不阻断）。 */
    List<BatchRecalcResultVo> recalcAll();

    /** 租户侧批次列表/下钻（WK/TA；wholesalerId+skuId 齐时附「无批次在池量」）。 */
    BatchListVo listForTenant(Long tenantId, Long userId, Long wholesalerId, Long skuId, String status);

    /** 商户侧批次列表（WA/WE 只读，跨自己名下全部商户绑定）。 */
    BatchListVo listForWholesaler(Long userId, Long skuId, String status);

    /** 默认批次补录 production/expiry（WK/TA；仅 source=DEFAULT 且未终态，50363/40205/40206）。 */
    com.cangchu.inventory.vo.BatchVo backfillDefaultBatch(Long batchId, BatchBackfillDto dto, Long userId);
}
