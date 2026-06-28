package com.cangchu.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.inventory.dto.InboundContext;
import com.cangchu.inventory.dto.OutboundContext;
import com.cangchu.inventory.entity.Inventory;
import com.cangchu.inventory.entity.StockMovement;
import com.cangchu.inventory.mapper.InventoryMapper;
import com.cangchu.inventory.mapper.StockMovementMapper;
import com.cangchu.inventory.service.InventoryService;
import com.cangchu.inventory.vo.InventoryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务实现（phase-1 B1：批次关闭，单 sku 维度）。
 *
 * <p>安全/并发规约（05-secure-coding-guardrails）：
 * <ul>
 *   <li>S2（G-3.1）：qty 必须 >0，否则 STOCK_QTY_INVALID（不产生流水/库存变动）。</li>
 *   <li>S5（G-4.x）：出库前校验库存足够，不足 STOCK_NOT_ENOUGH 拒绝、不写流水。</li>
 *   <li>S7（G-6.1）：扣减全程在 Redisson 锁 {@code lock:inv:{wholesalerId}:{skuId}} 内串行化，
 *       「校验→扣减→写流水」为单事务，杜绝超卖（TOCTOU 竞态）。</li>
 *   <li>租户隔离（G-2.2）：inventories/stock_movements 已纳入 TenantLine 白名单兜底。</li>
 * </ul>
 *
 * <p>事务/锁顺序：<b>先获取分布式锁、再开启事务</b>（事务体经 self 代理调用，保证 @Transactional 生效），
 * 事务提交后才释放锁，确保锁覆盖整个 commit 窗口，避免读到未提交库存导致超卖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final StockMovementMapper stockMovementMapper;
    private final RedissonClient redissonClient;
    private final SnowflakeIdUtil snowflakeIdUtil;

    /** 自注入代理：用于在锁内调用带 @Transactional 的事务体（避免 this 自调用使事务失效）。 */
    @Lazy
    @Autowired
    private InventoryService self;

    private static final long LOCK_WAIT_SECONDS = 30L;
    private static final long LOCK_LEASE_SECONDS = 15L;

    // ==================== 入库 ====================

    @Override
    @Transactional
    public InventoryVo addStock(InboundContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());
        int palletDelta = ctx.getPalletQty() != null ? ctx.getPalletQty() : 0;
        if (palletDelta < 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }

        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            // 首次入库：新建库存行（唯一索引 uk_inv_wholesaler_sku 双保险）
            inv = new Inventory();
            inv.setId(snowflakeIdUtil.nextId());
            inv.setTenantId(ctx.getTenantId());
            inv.setWholesalerId(ctx.getWholesalerId());
            inv.setSkuId(ctx.getSkuId());
            inv.setQty(ctx.getQty());
            inv.setPalletQty(palletDelta);
            inventoryMapper.insert(inv);
        } else {
            inv.setQty(inv.getQty() + ctx.getQty());
            inv.setPalletQty(inv.getPalletQty() + palletDelta);
            inv.setUpdatedAt(LocalDateTime.now());
            inventoryMapper.updateById(inv);
        }

        writeMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_INBOUND, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());

        log.info("[B1] addStock wholesaler={} sku={} +{} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo());
        return toVo(inv);
    }

    // ==================== 出库（Redisson 锁 + 单事务） ====================

    @Override
    public InventoryVo deductStock(OutboundContext ctx) {
        validateCtx(ctx.getWholesalerId(), ctx.getTenantId(), ctx.getSkuId(), ctx.getQty());

        String lockKey = "lock:inv:" + ctx.getWholesalerId() + ":" + ctx.getSkuId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        if (!acquired) {
            throw new BizException(ErrorCode.INVENTORY_LOCK_FAILED);
        }
        try {
            // 事务体经 self 代理调用，保证 @Transactional 生效；提交后才在 finally 释放锁
            return self.doDeductInTx(ctx);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 出库事务体（仅供 {@link #deductStock} 在持锁状态下经代理调用）。
     * 校验库存足够（不足 STOCK_NOT_ENOUGH，不写流水）→ 扣减 → 写 OUTBOUND 流水。
     */
    @Override
    @Transactional
    public InventoryVo doDeductInTx(OutboundContext ctx) {
        Inventory inv = lockRowForUpdate(ctx.getWholesalerId(), ctx.getSkuId());
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        if (inv.getQty() < ctx.getQty()) {
            // S5：库存不足，拒绝且不产生流水
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        inv.setQty(inv.getQty() - ctx.getQty());
        inv.setUpdatedAt(LocalDateTime.now());
        inventoryMapper.updateById(inv);

        writeMovement(ctx.getSkuId(), ctx.getWholesalerId(), ctx.getTenantId(),
                StockMovement.TYPE_OUTBOUND, ctx.getQty(), ctx.getRefDocNo(), ctx.getOperatorUserId());

        log.info("[B1] deductStock wholesaler={} sku={} -{} -> qty={} (doc={})",
                ctx.getWholesalerId(), ctx.getSkuId(), ctx.getQty(), inv.getQty(), ctx.getRefDocNo());
        return toVo(inv);
    }

    // ==================== 查询 ====================

    @Override
    public void assertStockEnough(Long wholesalerId, Long skuId, int qty) {
        if (qty <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
        Inventory inv = findRow(wholesalerId, skuId);
        if (inv == null) {
            throw new BizException(ErrorCode.INVENTORY_NOT_FOUND);
        }
        if (inv.getQty() < qty) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
    }

    @Override
    public List<InventoryVo> queryInventory(Long wholesalerId, Long skuId) {
        LambdaQueryWrapper<Inventory> qw = new LambdaQueryWrapper<Inventory>()
                .eq(wholesalerId != null, Inventory::getWholesalerId, wholesalerId)
                .eq(skuId != null, Inventory::getSkuId, skuId)
                .orderByDesc(Inventory::getUpdatedAt);
        return inventoryMapper.selectList(qw).stream().map(this::toVo).toList();
    }

    @Override
    public List<InventoryVo> listInStockSkusFor(Long wholesalerId) {
        LambdaQueryWrapper<Inventory> qw = new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .gt(Inventory::getQty, 0)
                .orderByDesc(Inventory::getUpdatedAt);
        return inventoryMapper.selectList(qw).stream().map(this::toVo).toList();
    }

    // ==================== 私有 ====================

    private void validateCtx(Long wholesalerId, Long tenantId, Long skuId, Integer qty) {
        if (wholesalerId == null || tenantId == null || skuId == null) {
            throw new BizException(ErrorCode.VALIDATION_BASIC_003);
        }
        if (qty == null || qty <= 0) {
            throw new BizException(ErrorCode.STOCK_QTY_INVALID);
        }
    }

    /** 读取库存行（受 TenantLine 兜底隔离）。 */
    private Inventory findRow(Long wholesalerId, Long skuId) {
        return inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getWholesalerId, wholesalerId)
                .eq(Inventory::getSkuId, skuId));
    }

    /**
     * 读取库存行用于变更。串行化由外层 Redisson 锁（出库）/单事务（入库 upsert）保证；
     * 单 sku 维度下同一 (wholesaler, sku) 的写已被锁互斥，故此处无需 DB 行级 FOR UPDATE。
     */
    private Inventory lockRowForUpdate(Long wholesalerId, Long skuId) {
        return findRow(wholesalerId, skuId);
    }

    private void writeMovement(Long skuId, Long wholesalerId, Long tenantId,
                               String type, Integer qty, String refDocNo, Long operatorUserId) {
        StockMovement mv = new StockMovement();
        mv.setId(snowflakeIdUtil.nextId());
        mv.setSkuId(skuId);
        mv.setWholesalerId(wholesalerId);
        mv.setTenantId(tenantId);
        mv.setType(type);
        mv.setQty(qty);
        mv.setRefDocNo(refDocNo);
        mv.setOperatorUserId(operatorUserId);
        stockMovementMapper.insert(mv);
    }

    private InventoryVo toVo(Inventory inv) {
        return InventoryVo.builder()
                .id(inv.getId())
                .wholesalerId(inv.getWholesalerId())
                .tenantId(inv.getTenantId())
                .skuId(inv.getSkuId())
                .qty(inv.getQty())
                .palletQty(inv.getPalletQty())
                .updatedAt(inv.getUpdatedAt())
                .build();
    }
}
