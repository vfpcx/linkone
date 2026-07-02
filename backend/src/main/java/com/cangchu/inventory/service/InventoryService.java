package com.cangchu.inventory.service;

import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
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
